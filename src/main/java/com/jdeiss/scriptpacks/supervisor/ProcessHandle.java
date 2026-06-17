package com.jdeiss.scriptpacks.supervisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ProcessHandle {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/supervisor");

    private final String namespace;
    private final Path scriptpackDir;
    private final int rpcPort;
    private final String token;
    private Process process;
    private Thread stdoutThread;
    private Thread stderrThread;

    public ProcessHandle(String namespace, Path scriptpackDir, int rpcPort, String token) {
        this.namespace = namespace;
        this.scriptpackDir = scriptpackDir;
        this.rpcPort = rpcPort;
        this.token = token;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public void spawn(Path logsDir) throws IOException {
        Path entrypoint = scriptpackDir.resolve("src/main.ts");
        if (!Files.isRegularFile(entrypoint)) {
            LOGGER.warn("[{}] No src/main.ts found — skipping process spawn", namespace);
            return;
        }

        Files.createDirectories(logsDir);

        ProcessBuilder pb = new ProcessBuilder("bun", "run", entrypoint.toAbsolutePath().toString());
        pb.directory(scriptpackDir.toFile());

        Map<String, String> env = pb.environment();
        env.put("SCRIPTPACK_NAMESPACE", namespace);
        env.put("RPC_PORT", String.valueOf(rpcPort));
        env.put("SCRIPTPACK_TOKEN", token);
        env.put("SCRIPTPACK_RESOURCES_PATH", scriptpackDir.resolve("resources").toAbsolutePath().toString());
        env.put("SCRIPTPACK_DATA_PATH", scriptpackDir.resolve("data").toAbsolutePath().toString());

        pb.redirectErrorStream(false);
        process = pb.start();

        Path stdoutLog = logsDir.resolve(namespace + ".stdout.log");
        Path stderrLog = logsDir.resolve(namespace + ".stderr.log");

        stdoutThread = Thread.ofVirtual().name("scriptpacks-" + namespace + "-stdout").start(() ->
                pipeToFile(process, stdoutLog, false));
        stderrThread = Thread.ofVirtual().name("scriptpacks-" + namespace + "-stderr").start(() ->
                pipeToFile(process, stderrLog, true));

        LOGGER.info("[{}] Spawned Bun process (PID {})", namespace, process.pid());
    }

    public void kill() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            LOGGER.info("[{}] Process killed", namespace);
        }
    }

    private void pipeToFile(Process proc, Path logFile, boolean isStderr) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(isStderr ? proc.getErrorStream() : proc.getInputStream()))) {
            // Append to log file
            try (var writer = Files.newBufferedWriter(logFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            }
        } catch (IOException e) {
            if (proc.isAlive()) {
                LOGGER.error("[{}] Error reading {} stream", namespace, isStderr ? "stderr" : "stdout", e);
            }
        }
    }
}
