package com.jdeiss.scriptpacks.resource;

import com.jdeiss.scriptpacks.manifest.ScriptpackManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConflictResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/resource");

    /**
     * Parse conflict-resolve.txt. Format: path = winning_namespace
     */
    public static Map<String, String> parseResolutions(Path conflictResolvePath) throws IOException {
        Map<String, String> resolutions = new LinkedHashMap<>();
        if (!Files.isRegularFile(conflictResolvePath)) {
            return resolutions;
        }

        List<String> lines = Files.readAllLines(conflictResolvePath);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "conflict-resolve.txt line " + (i + 1) + ": missing '=' separator: " + line);
            }

            String path = line.substring(0, eq).trim();
            String winner = line.substring(eq + 1).trim();
            if (path.isEmpty() || winner.isEmpty()) {
                throw new IllegalArgumentException(
                        "conflict-resolve.txt line " + (i + 1) + ": empty path or winner: " + line);
            }

            resolutions.put(path, winner);
        }
        return resolutions;
    }

    /**
     * Check enforceSafe violations — packs with enforceSafe=true must not have files under assets/minecraft/
     */
    public static List<String> checkEnforceSafe(
            Map<String, ScriptpackManifest> manifests,
            Map<String, List<String>> fileContributors) {

        List<String> errors = new ArrayList<>();
        Set<String> safePacks = new HashSet<>();
        for (var entry : manifests.entrySet()) {
            if (entry.getValue().enforceSafe()) {
                safePacks.add(entry.getKey());
            }
        }

        if (safePacks.isEmpty()) return errors;

        for (var entry : fileContributors.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("assets/minecraft/")) {
                for (String contributor : entry.getValue()) {
                    if (safePacks.contains(contributor)) {
                        errors.add("[" + contributor + "] enforceSafe violation: contributes file " + path);
                    }
                }
            }
        }

        return errors;
    }

    /**
     * Resolve conflicts. Returns map of path -> winning namespace.
     * Throws on unresolved conflicts with ready-to-paste fix lines.
     */
    public static Map<String, String> resolveConflicts(
            Map<String, List<String>> fileContributors,
            Map<String, String> resolutions) {

        Map<String, String> winners = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        // Find conflicts (paths with >1 contributor)
        Set<String> conflictPaths = new LinkedHashSet<>();
        for (var entry : fileContributors.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflictPaths.add(entry.getKey());
            }
        }

        // Apply resolutions
        for (String path : conflictPaths) {
            String winner = resolutions.get(path);
            if (winner == null) {
                List<String> contributors = fileContributors.get(path);
                StringBuilder sb = new StringBuilder();
                sb.append("ERROR: unresolved resource conflict at ").append(path).append("\n");
                sb.append("  contributors: ").append(String.join(", ", contributors)).append("\n");
                sb.append("  add ONE of the following to conflict-resolve.txt:");
                for (String c : contributors) {
                    sb.append("\n    ").append(path).append(" = ").append(c);
                }
                errors.add(sb.toString());
            } else if (!fileContributors.get(path).contains(winner)) {
                errors.add("ERROR: conflict-resolve.txt names winner '" + winner
                        + "' for path " + path + ", but it is not a contributor (contributors: "
                        + String.join(", ", fileContributors.get(path)) + ")");
            } else {
                winners.put(path, winner);
            }
        }

        // Warn about stale entries
        for (var entry : resolutions.entrySet()) {
            if (!conflictPaths.contains(entry.getKey())) {
                LOGGER.warn("conflict-resolve.txt: stale entry for '{}' (no longer contested)", entry.getKey());
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("\n\n", errors));
        }

        return winners;
    }
}
