package com.jdeiss.scriptpacks.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class RpcServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/rpc");
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUEST_SIZE = 1024 * 1024; // 1MB
    private static final int MAX_CONCURRENT = 64;

    private final RpcDispatcher dispatcher;
    private final int port;
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT);
    private HttpServer httpServer;

    public RpcServer(RpcDispatcher dispatcher, int port) {
        this.dispatcher = dispatcher;
        this.port = port;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/rpc", this::handleRpc);
        httpServer.start();
        LOGGER.info("RPC server listening on 127.0.0.1:{}", port);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
            LOGGER.info("RPC server stopped");
        }
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, errorJson("Method not allowed"));
            return;
        }

        if (!semaphore.tryAcquire()) {
            sendResponse(exchange, 429, errorJson("Too many concurrent requests"));
            return;
        }

        try {
            String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
            if (contentLength != null) {
                try {
                    if (Long.parseLong(contentLength) > MAX_REQUEST_SIZE) {
                        sendResponse(exchange, 413, errorJson("Request too large"));
                        return;
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            byte[] body;
            try (InputStream is = exchange.getRequestBody()) {
                body = is.readNBytes(MAX_REQUEST_SIZE + 1);
            }
            if (body.length > MAX_REQUEST_SIZE) {
                sendResponse(exchange, 413, errorJson("Request too large"));
                return;
            }

            String json = new String(body, StandardCharsets.UTF_8);
            JsonObject request;
            try {
                request = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception e) {
                sendResponse(exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
                return;
            }

            String method = request.has("method") ? request.get("method").getAsString() : null;
            if (method == null || method.isBlank()) {
                sendResponse(exchange, 400, errorJson("Missing 'method' field"));
                return;
            }

            JsonObject params = request.has("params") && request.get("params").isJsonObject()
                    ? request.getAsJsonObject("params")
                    : new JsonObject();

            String callerNamespace = exchange.getRequestHeaders().getFirst("X-Scriptpack");
            if (callerNamespace == null) callerNamespace = "unknown";

            JsonObject response = dispatcher.dispatch(method, params, callerNamespace);
            boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
            sendResponse(exchange, ok ? 200 : 500, GSON.toJson(response));

        } catch (Exception e) {
            LOGGER.error("Unhandled RPC error", e);
            sendResponse(exchange, 500, errorJson("Internal error: " + e.getMessage()));
        } finally {
            semaphore.release();
        }
    }

    private String errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", false);
        obj.addProperty("error", message);
        return GSON.toJson(obj);
    }

    private void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
