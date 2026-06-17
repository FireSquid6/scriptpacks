package com.jdeiss.scriptpacks.datapack;

import com.jdeiss.scriptpacks.manifest.ScriptpackManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

public class DatapackLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/datapack");
    private static final int PACK_FORMAT = 61; // MC 26.1.2

    /**
     * For each scriptpack with a data/ directory, create world/datapacks/scriptpacks_<ns>/
     * with a generated pack.mcmeta and symlinked data/ contents.
     * Vanilla discovers them naturally via PackRepository.
     */
    public static void installDatapacks(
            Map<String, ScriptpackManifest> manifests,
            Path scriptpacksRoot,
            Path worldDir) throws IOException {

        Path datapacksDir = worldDir.resolve("datapacks");
        Files.createDirectories(datapacksDir);

        for (Map.Entry<String, ScriptpackManifest> entry : manifests.entrySet()) {
            String ns = entry.getKey();
            ScriptpackManifest manifest = entry.getValue();
            Path dataDir = scriptpacksRoot.resolve(ns).resolve("data");

            if (!Files.isDirectory(dataDir)) {
                continue;
            }

            Path targetDir = datapacksDir.resolve("scriptpacks_" + ns);

            // Clean up existing if present
            if (Files.exists(targetDir)) {
                deleteRecursive(targetDir);
            }
            Files.createDirectories(targetDir);

            // Write pack.mcmeta
            String displayName = manifest.displayName() != null ? manifest.displayName() : ns;
            String packMcmeta = """
                    {
                      "pack": {
                        "pack_format": %d,
                        "description": "Scriptpack datapack: %s"
                      }
                    }
                    """.formatted(PACK_FORMAT, displayName);
            Files.writeString(targetDir.resolve("pack.mcmeta"), packMcmeta);

            // Symlink data/ directory
            Path targetData = targetDir.resolve("data");
            Files.createSymbolicLink(targetData, dataDir.toAbsolutePath());

            LOGGER.info("[{}] Installed datapack at {}", ns, targetDir);
        }
    }

    /**
     * Remove all scriptpacks_ datapacks from world/datapacks/.
     */
    public static void cleanDatapacks(Path worldDir) throws IOException {
        Path datapacksDir = worldDir.resolve("datapacks");
        if (!Files.isDirectory(datapacksDir)) return;

        try (Stream<Path> stream = Files.list(datapacksDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("scriptpacks_"))
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            deleteRecursive(dir);
                        } catch (IOException e) {
                            LOGGER.warn("Failed to clean datapack dir: {}", dir, e);
                        }
                    });
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
