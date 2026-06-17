package com.jdeiss.scriptpacks.snapshot;

import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WorldSnapshot {
    private final ConcurrentHashMap<UUID, JsonObject> players = new ConcurrentHashMap<>();
    private volatile RegistryAccess registryAccess;

    public Map<UUID, JsonObject> getPlayers() {
        return players;
    }

    public RegistryAccess getRegistryAccess() {
        return registryAccess;
    }

    public void setRegistryAccess(RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;
    }

    public void updatePlayers(Map<UUID, JsonObject> newData) {
        players.clear();
        players.putAll(newData);
    }
}
