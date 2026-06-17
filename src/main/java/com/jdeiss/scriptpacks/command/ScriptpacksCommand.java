package com.jdeiss.scriptpacks.command;

import com.jdeiss.scriptpacks.ScriptpackManager;
import com.jdeiss.scriptpacks.datapack.DatapackLoader;
import com.jdeiss.scriptpacks.manifest.ScriptpackManifest;
import com.jdeiss.scriptpacks.resource.ConflictResolver;
import com.jdeiss.scriptpacks.resource.PackHttpServer;
import com.jdeiss.scriptpacks.resource.ResourcePackBuilder;
import com.jdeiss.scriptpacks.resource.ResourcePackInjector;
import com.jdeiss.scriptpacks.supervisor.BunSupervisor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class ScriptpacksCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/command");

    private final ScriptpackManager scriptpackManager;
    private final BunSupervisor bunSupervisor;
    private final PackHttpServer packHttpServer;
    private final String publicUrlBase;
    private final int publicPort;

    public ScriptpacksCommand(
            ScriptpackManager scriptpackManager,
            BunSupervisor bunSupervisor,
            PackHttpServer packHttpServer,
            String publicUrlBase,
            int publicPort) {
        this.scriptpackManager = scriptpackManager;
        this.bunSupervisor = bunSupervisor;
        this.packHttpServer = packHttpServer;
        this.publicUrlBase = publicUrlBase;
        this.publicPort = publicPort;
    }

    /**
     * Register command tree with lazy delegation to the actual handler.
     * This allows registration during mod init before the handler is created.
     */
    public static void registerLazy(CommandDispatcher<CommandSourceStack> dispatcher,
                                     Supplier<ScriptpacksCommand> cmdSupplier) {
        dispatcher.register(Commands.literal("scriptpacks")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.literal("reload").executes(ctx -> {
                    ScriptpacksCommand cmd = cmdSupplier.get();
                    if (cmd == null) return 0;
                    return cmd.executeReload(ctx);
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    ScriptpacksCommand cmd = cmdSupplier.get();
                    if (cmd == null) return 0;
                    return cmd.executeList(ctx);
                }))
                .then(Commands.literal("status")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> {
                                    ScriptpacksCommand cmd = cmdSupplier.get();
                                    if (cmd == null) return 0;
                                    return cmd.executeStatus(ctx);
                                })))
        );
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("[Scriptpacks] Starting reload..."), true);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Re-discover and validate manifests
                scriptpackManager.discoverAndValidate(server.getServerDirectory());
                Map<String, ScriptpackManifest> manifests = scriptpackManager.getManifests();

                if (manifests.isEmpty()) {
                    server.execute(() -> source.sendSuccess(
                            () -> Component.literal("[Scriptpacks] No scriptpacks found"), true));
                    return;
                }

                // 2. Build new merged resource pack + conflict checks (off-thread)
                ResourcePackBuilder.BuildResult buildResult = buildResourcePack(manifests, server);

                // 3. Kill all Bun processes (off-thread)
                bunSupervisor.killAll().join();

                // 4. Install datapacks and reload (main thread)
                server.execute(() -> {
                    try {
                        // Install datapacks
                        Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                        DatapackLoader.cleanDatapacks(worldDir);
                        DatapackLoader.installDatapacks(manifests, scriptpackManager.getScriptpacksRoot(), worldDir);

                        // Reload datapacks
                        server.reloadResources(server.getPackRepository().getSelectedIds()).thenRun(() -> {
                            // Swap resource pack + push to clients
                            if (buildResult != null) {
                                packHttpServer.setCurrentPack(buildResult.zipPath());
                                String url = publicUrlBase + ":" + publicPort + "/pack.zip";
                                ResourcePackInjector.setResourcePack(url, buildResult.sha1());
                                ResourcePackInjector.pushToAllPlayers(server);
                            }

                            // 5. Respawn Bun processes (off-thread)
                            CompletableFuture.runAsync(() -> {
                                bunSupervisor.spawnAll(manifests,
                                        scriptpackManager.getScriptpacksRoot(),
                                        server.getServerDirectory());
                                server.execute(() -> source.sendSuccess(
                                        () -> Component.literal("[Scriptpacks] Reload complete — "
                                                + manifests.size() + " scriptpack(s)"), true));
                            });
                        });
                    } catch (Exception e) {
                        LOGGER.error("Reload failed during main-thread phase", e);
                        source.sendFailure(Component.literal("[Scriptpacks] Reload failed: " + e.getMessage()));
                    }
                });

            } catch (Exception e) {
                LOGGER.error("Reload aborted during build phase", e);
                server.execute(() -> source.sendFailure(
                        Component.literal("[Scriptpacks] Reload aborted: " + e.getMessage())));
            }
        });

        return 1;
    }

    private ResourcePackBuilder.BuildResult buildResourcePack(
            Map<String, ScriptpackManifest> manifests, MinecraftServer server) throws Exception {

        Path scriptpacksRoot = scriptpackManager.getScriptpacksRoot();
        Map<String, List<String>> contributors = ResourcePackBuilder.scanResources(manifests, scriptpacksRoot);

        if (contributors.isEmpty()) {
            return null;
        }

        // enforceSafe checks
        List<String> safeErrors = ConflictResolver.checkEnforceSafe(manifests, contributors);
        if (!safeErrors.isEmpty()) {
            throw new RuntimeException("enforceSafe violations:\n  " + String.join("\n  ", safeErrors));
        }

        // Parse conflict resolutions
        Map<String, String> resolutions = ConflictResolver.parseResolutions(
                scriptpacksRoot.resolve("conflict-resolve.txt"));

        // Resolve conflicts
        Map<String, String> winners = ConflictResolver.resolveConflicts(contributors, resolutions);

        // Build merged zip
        Path outputDir = server.getServerDirectory().resolve(".scriptpacks-cache");
        return ResourcePackBuilder.buildMergedPack(manifests, scriptpacksRoot, winners, outputDir);
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Map<String, ScriptpackManifest> manifests = scriptpackManager.getManifests();

        if (manifests.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[Scriptpacks] No scriptpacks loaded"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[Scriptpacks] " + manifests.size() + " scriptpack(s):"), false);
        for (var entry : manifests.entrySet()) {
            String ns = entry.getKey();
            ScriptpackManifest m = entry.getValue();
            boolean alive = bunSupervisor.isProcessAlive(ns);
            String displayName = m.displayName() != null ? m.displayName() : ns;
            String status = alive ? "running" : "stopped";
            source.sendSuccess(
                    () -> Component.literal("  " + displayName + " (" + ns + ") — " + status), false);
        }
        return manifests.size();
    }

    private int executeStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String ns = StringArgumentType.getString(ctx, "namespace");
        ScriptpackManifest manifest = scriptpackManager.getManifests().get(ns);

        if (manifest == null) {
            source.sendFailure(Component.literal("[Scriptpacks] Unknown scriptpack: " + ns));
            return 0;
        }

        boolean alive = bunSupervisor.isProcessAlive(ns);
        String displayName = manifest.displayName() != null ? manifest.displayName() : ns;

        source.sendSuccess(() -> Component.literal("[Scriptpacks] " + displayName), false);
        source.sendSuccess(() -> Component.literal("  Namespace: " + ns), false);
        source.sendSuccess(() -> Component.literal("  Author: " + manifest.author()), false);
        source.sendSuccess(() -> Component.literal("  Process: " + (alive ? "running" : "stopped")), false);
        source.sendSuccess(() -> Component.literal("  enforceSafe: " + manifest.enforceSafe()), false);
        if (manifest.description() != null) {
            source.sendSuccess(() -> Component.literal("  Description: " + manifest.description()), false);
        }

        return 1;
    }
}
