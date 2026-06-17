package com.jdeiss.scriptpacks.supervisor;

import com.jdeiss.scriptpacks.manifest.ScriptpackManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BunSupervisor {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/supervisor");

    private final Map<String, ProcessHandle> processes = new LinkedHashMap<>();
    private final int rpcPort;
    private Path logsDir;

    public BunSupervisor(int rpcPort) {
        this.rpcPort = rpcPort;
    }

    public static boolean isBunAvailable() {
        try {
            Process p = new ProcessBuilder("bun", "--version")
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            return code == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public void spawnAll(Map<String, ScriptpackManifest> manifests, Path scriptpacksRoot, Path serverRoot) {
        logsDir = serverRoot.resolve("logs").resolve("scriptpacks");

        if (!isBunAvailable()) {
            LOGGER.error("'bun' is not on PATH — scriptpack processes will not be spawned. Install Bun: https://bun.sh");
            return;
        }

        for (Map.Entry<String, ScriptpackManifest> entry : manifests.entrySet()) {
            String ns = entry.getKey();
            Path dir = scriptpacksRoot.resolve(ns);
            String token = UUID.randomUUID().toString();

            ProcessHandle handle = new ProcessHandle(ns, dir, rpcPort, token);
            processes.put(ns, handle);

            try {
                handle.spawn(logsDir);
            } catch (IOException e) {
                LOGGER.error("[{}] Failed to spawn Bun process", ns, e);
            }
        }
    }

    public CompletableFuture<Void> killAll() {
        return CompletableFuture.runAsync(() -> {
            for (ProcessHandle handle : processes.values()) {
                handle.kill();
            }
            processes.clear();
        });
    }

    public void respawnAll(Map<String, ScriptpackManifest> manifests, Path scriptpacksRoot, Path serverRoot) {
        killAll().join();
        spawnAll(manifests, scriptpacksRoot, serverRoot);
    }

    public Map<String, ProcessHandle> getProcesses() {
        return processes;
    }

    public boolean isProcessAlive(String namespace) {
        ProcessHandle handle = processes.get(namespace);
        return handle != null && handle.isAlive();
    }
}
