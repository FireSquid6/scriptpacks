package com.jdeiss.scriptpacks;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.jdeiss.scriptpacks.manifest.ScriptpackManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ScriptpackManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks");
    private static final Gson GSON = new Gson();

    private final Map<String, ScriptpackManifest> manifests = new LinkedHashMap<>();
    private Path scriptpacksRoot;

    public Map<String, ScriptpackManifest> getManifests() {
        return Collections.unmodifiableMap(manifests);
    }

    public Path getScriptpacksRoot() {
        return scriptpacksRoot;
    }

    public Path getScriptpackDir(String namespace) {
        return scriptpacksRoot.resolve(namespace);
    }

    public void discoverAndValidate(Path serverRoot) {
        manifests.clear();
        scriptpacksRoot = serverRoot.resolve("scriptpacks");

        if (!Files.isDirectory(scriptpacksRoot)) {
            LOGGER.info("No scriptpacks/ directory found — nothing to load");
            return;
        }

        List<String> errors = new ArrayList<>();
        List<Path> dirs;

        try (Stream<Path> stream = Files.list(scriptpacksRoot)) {
            dirs = stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list scriptpacks/ directory", e);
        }

        if (dirs.isEmpty()) {
            LOGGER.info("scriptpacks/ directory is empty — nothing to load");
            return;
        }

        for (Path dir : dirs) {
            String dirName = dir.getFileName().toString();
            Path manifestPath = dir.resolve("scriptpack.json");

            if (!Files.isRegularFile(manifestPath)) {
                errors.add("[" + dirName + "] Missing scriptpack.json");
                continue;
            }

            ScriptpackManifest manifest;
            try {
                String json = Files.readString(manifestPath);
                manifest = GSON.fromJson(json, ScriptpackManifest.class);
            } catch (IOException e) {
                errors.add("[" + dirName + "] Failed to read scriptpack.json: " + e.getMessage());
                continue;
            } catch (JsonSyntaxException e) {
                errors.add("[" + dirName + "] Malformed scriptpack.json: " + e.getMessage());
                continue;
            } catch (IllegalArgumentException e) {
                errors.add("[" + dirName + "] Invalid scriptpack.json: " + e.getMessage());
                continue;
            }

            if (!dirName.equals(manifest.name())) {
                errors.add("[" + dirName + "] name mismatch: scriptpack.json declares name='"
                        + manifest.name() + "' but directory is '" + dirName + "'");
                continue;
            }

            manifests.put(dirName, manifest);
        }

        if (!errors.isEmpty()) {
            String message = "Scriptpack validation failed:\n  " + String.join("\n  ", errors);
            LOGGER.error(message);
            throw new RuntimeException(message);
        }

        LOGGER.info("Discovered {} scriptpack(s): {}", manifests.size(),
                String.join(", ", manifests.keySet()));
    }
}
