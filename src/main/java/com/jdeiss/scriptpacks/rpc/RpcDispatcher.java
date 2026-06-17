package com.jdeiss.scriptpacks.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jdeiss.scriptpacks.snapshot.WorldSnapshot;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RpcDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/rpc");

    private final Map<String, ReadHandler> readHandlers = new LinkedHashMap<>();
    private final Map<String, MutatingHandler> mutatingHandlers = new LinkedHashMap<>();

    private final WorldSnapshot snapshot;
    private volatile MinecraftServer server;

    private volatile String currentMutatingHandler = null;

    public RpcDispatcher(WorldSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void registerRead(String method, ReadHandler handler) {
        readHandlers.put(method, handler);
    }

    public void registerMutating(String method, MutatingHandler handler) {
        mutatingHandlers.put(method, handler);
    }

    public String getCurrentMutatingHandler() {
        return currentMutatingHandler;
    }

    public boolean hasMethod(String method) {
        return readHandlers.containsKey(method) || mutatingHandlers.containsKey(method);
    }

    public JsonObject dispatch(String method, JsonObject params, String callerNamespace) {
        ReadHandler readHandler = readHandlers.get(method);
        if (readHandler != null) {
            return dispatchRead(method, readHandler, params, callerNamespace);
        }

        MutatingHandler mutatingHandler = mutatingHandlers.get(method);
        if (mutatingHandler != null) {
            return dispatchMutating(method, mutatingHandler, params, callerNamespace);
        }

        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("error", "Unknown method: " + method);
        return error;
    }

    private JsonObject dispatchRead(String method, ReadHandler handler, JsonObject params, String callerNamespace) {
        try {
            JsonElement result = handler.invoke(snapshot, params);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.add("value", result != null ? result : JsonNull.INSTANCE);
            return response;
        } catch (Exception e) {
            LOGGER.error("[{}] ReadHandler '{}' threw exception", callerNamespace, method, e);
            JsonObject response = new JsonObject();
            response.addProperty("ok", false);
            response.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return response;
        }
    }

    private JsonObject dispatchMutating(String method, MutatingHandler handler, JsonObject params, String callerNamespace) {
        if (server == null) {
            JsonObject response = new JsonObject();
            response.addProperty("ok", false);
            response.addProperty("error", "Server not available");
            return response;
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            currentMutatingHandler = method;
            try {
                JsonElement result = handler.invoke(server, params);
                JsonObject response = new JsonObject();
                response.addProperty("ok", true);
                response.add("value", result != null ? result : JsonNull.INSTANCE);
                future.complete(response);
            } catch (Exception e) {
                LOGGER.error("[{}] MutatingHandler '{}' threw exception", callerNamespace, method, e);
                JsonObject response = new JsonObject();
                response.addProperty("ok", false);
                response.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                future.complete(response);
            } finally {
                currentMutatingHandler = null;
            }
        });

        try {
            return future.join();
        } catch (Exception e) {
            JsonObject response = new JsonObject();
            response.addProperty("ok", false);
            response.addProperty("error", "Dispatch failed: " + e.getMessage());
            return response;
        }
    }
}
