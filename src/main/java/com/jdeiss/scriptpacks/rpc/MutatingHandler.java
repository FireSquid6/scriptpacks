package com.jdeiss.scriptpacks.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface MutatingHandler {
    JsonElement invoke(MinecraftServer server, JsonObject params) throws Exception;
}
