package com.jdeiss.scriptpacks.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SnapshotCollector {

    private final WorldSnapshot snapshot;

    public SnapshotCollector(WorldSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void onEndTick(MinecraftServer server) {
        snapshot.setRegistryAccess(server.registryAccess());

        Map<UUID, JsonObject> playerData = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            playerData.put(player.getUUID(), collectPlayerData(player));
        }
        snapshot.updatePlayers(playerData);
    }

    private JsonObject collectPlayerData(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", player.getUUID().toString());
        obj.addProperty("name", player.getGameProfile().name());
        obj.addProperty("health", (Number) player.getHealth());
        obj.addProperty("maxHealth", (Number) player.getMaxHealth());
        obj.addProperty("foodLevel", (Number) player.getFoodData().getFoodLevel());
        obj.addProperty("saturation", (Number) player.getFoodData().getSaturationLevel());
        obj.addProperty("experienceLevel", (Number) player.experienceLevel);
        obj.addProperty("totalExperience", (Number) player.totalExperience);
        obj.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());

        Vec3 pos = player.position();
        JsonObject position = new JsonObject();
        position.addProperty("x", (Number) pos.x);
        position.addProperty("y", (Number) pos.y);
        position.addProperty("z", (Number) pos.z);
        obj.add("position", position);

        obj.addProperty("dimension", player.level().dimension().identifier().toString());

        obj.addProperty("yaw", (Number) player.getYRot());
        obj.addProperty("pitch", (Number) player.getXRot());

        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject eff = new JsonObject();
            eff.addProperty("id", effect.getEffect().unwrapKey()
                    .map(k -> k.identifier().toString()).orElse("unknown"));
            eff.addProperty("duration", (Number) effect.getDuration());
            eff.addProperty("amplifier", (Number) effect.getAmplifier());
            effects.add(eff);
        }
        obj.add("activeEffects", effects);

        return obj;
    }
}
