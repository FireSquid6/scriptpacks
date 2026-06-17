package com.jdeiss.scriptpacks.resource;

import com.jdeiss.scriptpacks.manifest.ScriptpackManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ResourcePackBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/resource");
    private static final int PACK_FORMAT = 61; // MC 26.1.2

    public record BuildResult(Path zipPath, String sha1, int fileCount) {}

    /**
     * Scan all scriptpack resources/ directories and build contributor map.
     * Key: relative path within the pack (e.g. assets/coolpack/textures/foo.png)
     * Value: list of namespace(s) that contribute this path
     */
    public static Map<String, List<String>> scanResources(
            Map<String, ScriptpackManifest> manifests, Path scriptpacksRoot) throws IOException {

        Map<String, List<String>> contributors = new LinkedHashMap<>();

        for (String ns : manifests.keySet()) {
            Path resourcesDir = scriptpacksRoot.resolve(ns).resolve("resources");
            if (!Files.isDirectory(resourcesDir)) continue;

            try (Stream<Path> walk = Files.walk(resourcesDir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    String relativePath = resourcesDir.relativize(file).toString().replace('\\', '/');
                    contributors.computeIfAbsent(relativePath, k -> new ArrayList<>()).add(ns);
                });
            }
        }

        return contributors;
    }

    /**
     * Build the merged resource pack zip.
     */
    public static BuildResult buildMergedPack(
            Map<String, ScriptpackManifest> manifests,
            Path scriptpacksRoot,
            Map<String, String> conflictWinners,
            Path outputDir) throws IOException {

        Files.createDirectories(outputDir);
        Path zipPath = outputDir.resolve("scriptpacks-resources.zip");

        try (OutputStream fos = Files.newOutputStream(zipPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            // Write pack.mcmeta
            String packMcmeta = """
                    {
                      "pack": {
                        "pack_format": %d,
                        "description": "Scriptpacks merged resource pack"
                      }
                    }
                    """.formatted(PACK_FORMAT);
            zos.putNextEntry(new ZipEntry("pack.mcmeta"));
            zos.write(packMcmeta.getBytes());
            zos.closeEntry();

            int fileCount = 0;
            Set<String> writtenPaths = new HashSet<>();

            for (String ns : manifests.keySet()) {
                Path resourcesDir = scriptpacksRoot.resolve(ns).resolve("resources");
                if (!Files.isDirectory(resourcesDir)) continue;

                try (Stream<Path> walk = Files.walk(resourcesDir)) {
                    List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
                    for (Path file : files) {
                        String relativePath = resourcesDir.relativize(file).toString().replace('\\', '/');

                        // If this path has a conflict, check if this ns is the winner
                        String winner = conflictWinners.get(relativePath);
                        if (winner != null && !winner.equals(ns)) {
                            continue; // Skip — another pack wins this path
                        }

                        if (writtenPaths.contains(relativePath)) {
                            continue; // Already written by the winner
                        }

                        zos.putNextEntry(new ZipEntry(relativePath));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        writtenPaths.add(relativePath);
                        fileCount++;
                    }
                }
            }

            zos.finish();
            String sha1 = computeSha1(zipPath);
            LOGGER.info("Built merged resource pack: {} files, SHA-1: {}", fileCount, sha1);
            return new BuildResult(zipPath, sha1, fileCount);
        }
    }

    private static String computeSha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
