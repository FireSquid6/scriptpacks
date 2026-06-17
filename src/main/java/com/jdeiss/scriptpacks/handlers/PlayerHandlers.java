package com.jdeiss.scriptpacks.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jdeiss.scriptpacks.rpc.RpcDispatcher;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.Optional;

import static com.jdeiss.scriptpacks.handlers.HandlerUtils.resolvePlayer;

public final class PlayerHandlers {

    private PlayerHandlers() {}

    public static void register(RpcDispatcher dispatcher) {
        // getInventory — live read via MutatingHandler (safe, just doesn't mutate)
        dispatcher.registerMutating("getInventory", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            JsonArray inv = new JsonArray();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", i);
                if (stack.isEmpty()) {
                    slot.addProperty("empty", true);
                } else {
                    slot.addProperty("id", stack.getItem().builtInRegistryHolder().key().identifier().toString());
                    slot.addProperty("count", stack.getCount());
                }
                inv.add(slot);
            }
            return inv;
        });

        // givePlayerItem — give item built from ItemStack codec
        dispatcher.registerMutating("givePlayerItem", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            JsonObject itemSpec = params.getAsJsonObject("item");
            ItemStack stack = ContentHandlers.decodeItemStack(server, itemSpec);
            boolean added = player.getInventory().add(stack);
            JsonObject result = new JsonObject();
            result.addProperty("added", added);
            result.addProperty("remainingCount", stack.getCount());
            return result;
        });

        // removeItem — remove items from inventory
        dispatcher.registerMutating("removeItem", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            int slot = params.get("slot").getAsInt();
            int count = params.has("count") ? params.get("count").getAsInt() : 1;
            ItemStack removed = player.getInventory().removeItem(slot, count);
            JsonObject result = new JsonObject();
            result.addProperty("removedCount", removed.getCount());
            return result;
        });

        // clearInventory
        dispatcher.registerMutating("clearInventory", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            player.getInventory().clearContent();
            return JsonNull.INSTANCE;
        });

        // teleportPlayer
        dispatcher.registerMutating("teleportPlayer", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            double x = params.get("x").getAsDouble();
            double y = params.get("y").getAsDouble();
            double z = params.get("z").getAsDouble();

            if (params.has("dimension")) {
                ServerLevel level = HandlerUtils.resolveLevel(server, params.get("dimension").getAsString());
                float yaw = params.has("yaw") ? params.get("yaw").getAsFloat() : player.getYRot();
                float pitch = params.has("pitch") ? params.get("pitch").getAsFloat() : player.getXRot();
                player.teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, false);
            } else {
                player.teleportTo(x, y, z);
            }
            return JsonNull.INSTANCE;
        });

        // setGameMode
        dispatcher.registerMutating("setGameMode", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            String mode = params.get("gameMode").getAsString();
            GameType gameType = switch (mode.toLowerCase()) {
                case "survival" -> GameType.SURVIVAL;
                case "creative" -> GameType.CREATIVE;
                case "adventure" -> GameType.ADVENTURE;
                case "spectator" -> GameType.SPECTATOR;
                default -> throw new IllegalArgumentException("Unknown game mode: " + mode);
            };
            player.setGameMode(gameType);
            return JsonNull.INSTANCE;
        });

        // setHealth
        dispatcher.registerMutating("setHealth", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            float health = params.get("health").getAsFloat();
            player.setHealth(health);
            return JsonNull.INSTANCE;
        });

        // setHunger
        dispatcher.registerMutating("setHunger", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            int foodLevel = params.get("foodLevel").getAsInt();
            player.getFoodData().setFoodLevel(foodLevel);
            if (params.has("saturation")) {
                player.getFoodData().setSaturation(params.get("saturation").getAsFloat());
            }
            return JsonNull.INSTANCE;
        });

        // setXp
        dispatcher.registerMutating("setXp", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            if (params.has("levels")) {
                player.giveExperienceLevels(params.get("levels").getAsInt());
            }
            if (params.has("points")) {
                player.giveExperiencePoints(params.get("points").getAsInt());
            }
            return JsonNull.INSTANCE;
        });

        // applyEffect
        dispatcher.registerMutating("applyEffect", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            String effectId = params.get("effect").getAsString();
            int duration = params.has("duration") ? params.get("duration").getAsInt() : 600; // 30 seconds default
            int amplifier = params.has("amplifier") ? params.get("amplifier").getAsInt() : 0;

            Optional<Holder.Reference<MobEffect>> effectHolder = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(effectId));
            if (effectHolder.isEmpty()) {
                throw new IllegalArgumentException("Unknown effect: " + effectId);
            }

            player.addEffect(new MobEffectInstance(effectHolder.get(), duration, amplifier));
            return JsonNull.INSTANCE;
        });

        // clearEffect
        dispatcher.registerMutating("clearEffect", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            if (params.has("effect")) {
                String effectId = params.get("effect").getAsString();
                Optional<Holder.Reference<MobEffect>> effectHolder = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(effectId));
                if (effectHolder.isEmpty()) {
                    throw new IllegalArgumentException("Unknown effect: " + effectId);
                }
                player.removeEffect(effectHolder.get());
            } else {
                player.removeAllEffects();
            }
            return JsonNull.INSTANCE;
        });

        // setAttribute
        dispatcher.registerMutating("setAttribute", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            String attrId = params.get("attribute").getAsString();
            double value = params.get("value").getAsDouble();

            var attrHolder = BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(attrId));
            if (attrHolder.isEmpty()) {
                throw new IllegalArgumentException("Unknown attribute: " + attrId);
            }

            AttributeInstance instance = player.getAttribute(attrHolder.get());
            if (instance == null) {
                throw new IllegalArgumentException("Player does not have attribute: " + attrId);
            }
            instance.setBaseValue(value);
            return JsonNull.INSTANCE;
        });

        // sendMessage
        dispatcher.registerMutating("sendMessage", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            String message = params.get("message").getAsString();
            boolean actionBar = params.has("actionBar") && params.get("actionBar").getAsBoolean();
            if (actionBar) {
                player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(message)));
            } else {
                player.sendSystemMessage(Component.literal(message));
            }
            return JsonNull.INSTANCE;
        });

        // sendTitle
        dispatcher.registerMutating("sendTitle", (server, params) -> {
            ServerPlayer player = resolvePlayer(server, params);
            if (params.has("title")) {
                player.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal(params.get("title").getAsString())));
            }
            if (params.has("subtitle")) {
                player.connection.send(new ClientboundSetSubtitleTextPacket(
                        Component.literal(params.get("subtitle").getAsString())));
            }
            return JsonNull.INSTANCE;
        });
    }
}
