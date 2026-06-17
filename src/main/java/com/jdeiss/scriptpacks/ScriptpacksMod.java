package com.jdeiss.scriptpacks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jdeiss.scriptpacks.command.ScriptpacksCommand;
import com.jdeiss.scriptpacks.datapack.DatapackLoader;
import com.jdeiss.scriptpacks.handlers.CommandHandler;
import com.jdeiss.scriptpacks.handlers.ContentHandlers;
import com.jdeiss.scriptpacks.handlers.PlayerHandlers;
import com.jdeiss.scriptpacks.handlers.WorldHandlers;
import com.jdeiss.scriptpacks.manifest.ScriptpackManifest;
import com.jdeiss.scriptpacks.resource.ConflictResolver;
import com.jdeiss.scriptpacks.resource.PackHttpServer;
import com.jdeiss.scriptpacks.resource.ResourcePackBuilder;
import com.jdeiss.scriptpacks.resource.ResourcePackInjector;
import com.jdeiss.scriptpacks.rpc.RpcDispatcher;
import com.jdeiss.scriptpacks.rpc.RpcServer;
import com.jdeiss.scriptpacks.snapshot.SnapshotCollector;
import com.jdeiss.scriptpacks.snapshot.WorldSnapshot;
import com.jdeiss.scriptpacks.supervisor.BunSupervisor;
import com.jdeiss.scriptpacks.watchdog.TickWatchdog;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ScriptpacksMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "scriptpacks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ScriptpacksConfig config;
    private final ScriptpackManager scriptpackManager = new ScriptpackManager();
    private final WorldSnapshot worldSnapshot = new WorldSnapshot();
    private final SnapshotCollector snapshotCollector = new SnapshotCollector(worldSnapshot);
    private final RpcDispatcher rpcDispatcher = new RpcDispatcher(worldSnapshot);
    private BunSupervisor bunSupervisor;
    private TickWatchdog watchdog;
    private PackHttpServer packHttpServer;
    private RpcServer rpcServer;
    private volatile ScriptpacksCommand scriptpacksCommand;

    @Override
    public void onInitializeServer() {
        registerBuiltinHandlers();

        // Register /scriptpacks command — the command object is created lazily
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ScriptpacksCommand.registerLazy(dispatcher, () -> scriptpacksCommand);
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // Load config
            config = ScriptpacksConfig.load(server.getServerDirectory());
            bunSupervisor = new BunSupervisor(config.rpcPort);
            watchdog = new TickWatchdog(rpcDispatcher);
            packHttpServer = new PackHttpServer(config.publicPort);

            // Create command handler
            scriptpacksCommand = new ScriptpacksCommand(
                    scriptpackManager, bunSupervisor, packHttpServer,
                    config.publicUrlBase, config.publicPort);

            scriptpackManager.discoverAndValidate(server.getServerDirectory());
            rpcDispatcher.setServer(server);

            // Start RPC server
            try {
                rpcServer = new RpcServer(rpcDispatcher, config.rpcPort);
                rpcServer.start();
            } catch (Exception e) {
                LOGGER.error("Failed to start RPC server", e);
            }

            // Build and host resource pack
            Map<String, ScriptpackManifest> manifests = scriptpackManager.getManifests();
            if (!manifests.isEmpty()) {
                try {
                    buildAndHostResourcePack(manifests, server.getServerDirectory());
                } catch (Exception e) {
                    LOGGER.error("Failed to build/host resource pack", e);
                }

                // Install datapacks
                try {
                    Path worldDir = server.getWorldPath(LevelResource.ROOT);
                    DatapackLoader.installDatapacks(manifests, scriptpackManager.getScriptpacksRoot(), worldDir);
                } catch (Exception e) {
                    LOGGER.error("Failed to install datapacks", e);
                }
            }

            // Start watchdog
            watchdog.start();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!scriptpackManager.getManifests().isEmpty()) {
                bunSupervisor.spawnAll(
                        scriptpackManager.getManifests(),
                        scriptpackManager.getScriptpacksRoot(),
                        server.getServerDirectory());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            snapshotCollector.onEndTick(server);
            if (watchdog != null) watchdog.onEndTick();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (watchdog != null) watchdog.stop();
            if (bunSupervisor != null) bunSupervisor.killAll().join();
            if (rpcServer != null) rpcServer.stop();
            if (packHttpServer != null) packHttpServer.stop();
            ResourcePackInjector.clearResourcePack();
        });
    }

    private void buildAndHostResourcePack(Map<String, ScriptpackManifest> manifests, Path serverRoot) throws Exception {
        Path scriptpacksRoot = scriptpackManager.getScriptpacksRoot();
        Map<String, List<String>> contributors = ResourcePackBuilder.scanResources(manifests, scriptpacksRoot);

        if (contributors.isEmpty()) {
            LOGGER.info("No resource files found in scriptpacks — skipping resource pack build");
            return;
        }

        List<String> safeErrors = ConflictResolver.checkEnforceSafe(manifests, contributors);
        if (!safeErrors.isEmpty()) {
            throw new RuntimeException("enforceSafe violations:\n  " + String.join("\n  ", safeErrors));
        }

        Map<String, String> resolutions = ConflictResolver.parseResolutions(
                scriptpacksRoot.resolve("conflict-resolve.txt"));
        Map<String, String> winners = ConflictResolver.resolveConflicts(contributors, resolutions);

        Path outputDir = serverRoot.resolve(".scriptpacks-cache");
        ResourcePackBuilder.BuildResult result = ResourcePackBuilder.buildMergedPack(
                manifests, scriptpacksRoot, winners, outputDir);

        packHttpServer.setCurrentPack(result.zipPath());
        packHttpServer.start();

        String url = config.publicUrlBase + ":" + config.publicPort + "/pack.zip";
        ResourcePackInjector.setResourcePack(url, result.sha1());
    }

    private void registerBuiltinHandlers() {
        rpcDispatcher.registerRead("listPlayers", (snapshot, params) -> {
            JsonArray arr = new JsonArray();
            for (Map.Entry<UUID, JsonObject> entry : snapshot.getPlayers().entrySet()) {
                arr.add(entry.getValue());
            }
            return arr;
        });

        rpcDispatcher.registerRead("getPlayerData", (snapshot, params) -> {
            String uuid = params.get("uuid").getAsString();
            JsonObject player = snapshot.getPlayers().get(UUID.fromString(uuid));
            if (player == null) {
                throw new IllegalArgumentException("Player not found: " + uuid);
            }
            return player;
        });

        PlayerHandlers.register(rpcDispatcher);
        WorldHandlers.register(rpcDispatcher);
        ContentHandlers.register(rpcDispatcher);
        CommandHandler.register(rpcDispatcher);
    }
}
