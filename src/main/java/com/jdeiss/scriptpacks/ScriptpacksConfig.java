package com.jdeiss.scriptpacks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ScriptpacksConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int rpcPort = 9736;
    public int publicPort = 9737;
    public String publicUrlBase = "http://localhost";
    public int maxRequestSizeBytes = 1048576; // 1MB
    public int maxConcurrentRequests = 64;
    public int watchdogThresholdSeconds = 30;

    public static ScriptpacksConfig load(Path serverRoot) {
        Path configPath = serverRoot.resolve("config").resolve("scriptpacks.json");

        if (Files.isRegularFile(configPath)) {
            try {
                String json = Files.readString(configPath);
                ScriptpacksConfig config = GSON.fromJson(json, ScriptpacksConfig.class);
                LOGGER.info("Loaded config from {}", configPath);
                return config;
            } catch (Exception e) {
                LOGGER.error("Failed to read config, using defaults", e);
            }
        }

        // Write defaults
        ScriptpacksConfig config = new ScriptpacksConfig();
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(config));
            LOGGER.info("Created default config at {}", configPath);
        } catch (IOException e) {
            LOGGER.warn("Failed to write default config", e);
        }
        return config;
    }
}
