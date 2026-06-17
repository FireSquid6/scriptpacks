package com.jdeiss.scriptpacks.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jdeiss.scriptpacks.rpc.RpcDispatcher;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

public final class ContentHandlers {

    private ContentHandlers() {}

    public static void register(RpcDispatcher dispatcher) {
        // buildItemStack — snapshot read, pure construction
        dispatcher.registerRead("buildItemStack", (snapshot, params) -> {
            JsonObject itemSpec = params.getAsJsonObject("item");
            RegistryAccess registryAccess = snapshot.getRegistryAccess();
            if (registryAccess == null) {
                throw new IllegalStateException("Registry access not yet available");
            }
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess);
            ItemStack stack = ItemStack.CODEC.parse(ops, itemSpec)
                    .getOrThrow(msg -> new IllegalArgumentException("Invalid item spec: " + msg));

            // Re-encode to return canonical form
            JsonElement encoded = ItemStack.CODEC.encodeStart(ops, stack)
                    .getOrThrow(msg -> new RuntimeException("Failed to encode item: " + msg));
            return encoded;
        });

        // applyComponents — snapshot read, pure
        dispatcher.registerRead("applyComponents", (snapshot, params) -> {
            JsonObject itemSpec = params.getAsJsonObject("item");
            RegistryAccess registryAccess = snapshot.getRegistryAccess();
            if (registryAccess == null) {
                throw new IllegalStateException("Registry access not yet available");
            }
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess);
            ItemStack stack = ItemStack.CODEC.parse(ops, itemSpec)
                    .getOrThrow(msg -> new IllegalArgumentException("Invalid item spec: " + msg));

            JsonElement encoded = ItemStack.CODEC.encodeStart(ops, stack)
                    .getOrThrow(msg -> new RuntimeException("Failed to encode item: " + msg));
            return encoded;
        });

        // reloadData — mutation
        dispatcher.registerMutating("reloadData", (server, params) -> {
            server.reloadResources(server.getPackRepository().getSelectedIds()).join();
            return JsonNull.INSTANCE;
        });
    }

    public static ItemStack decodeItemStack(MinecraftServer server, JsonObject itemSpec) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        return ItemStack.CODEC.parse(ops, itemSpec)
                .getOrThrow(msg -> new IllegalArgumentException("Invalid item spec: " + msg));
    }
}
