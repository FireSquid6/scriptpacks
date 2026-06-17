package com.jdeiss.scriptpacks.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdeiss.scriptpacks.snapshot.WorldSnapshot;

@FunctionalInterface
public interface ReadHandler {
    JsonElement invoke(WorldSnapshot snapshot, JsonObject params) throws Exception;
}
