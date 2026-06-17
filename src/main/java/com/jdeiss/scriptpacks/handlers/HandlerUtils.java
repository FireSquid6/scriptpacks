package com.jdeiss.scriptpacks.handlers;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class HandlerUtils {

    private HandlerUtils() {}

    public static ServerPlayer resolvePlayer(MinecraftServer server, JsonObject params) {
        String uuid = params.get("uuid").getAsString();
        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(uuid));
        if (player == null) {
            throw new IllegalArgumentException("Player not online: " + uuid);
        }
        return player;
    }

    public static ServerLevel resolveLevel(MinecraftServer server, JsonObject params) {
        String dimension = params.has("dimension") ? params.get("dimension").getAsString() : "minecraft:overworld";
        ResourceKey<Level> key = ResourceKey.create(
                ResourceKey.createRegistryKey(Identifier.parse("minecraft:dimension")),
                Identifier.parse(dimension)
        );
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            throw new IllegalArgumentException("Unknown dimension: " + dimension);
        }
        return level;
    }

    public static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        ResourceKey<Level> key = ResourceKey.create(
                ResourceKey.createRegistryKey(Identifier.parse("minecraft:dimension")),
                Identifier.parse(dimension)
        );
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            throw new IllegalArgumentException("Unknown dimension: " + dimension);
        }
        return level;
    }
}
