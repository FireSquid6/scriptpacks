package com.jdeiss.scriptpacks.resource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class PackHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/resource");

    private HttpServer httpServer;
    private volatile Path currentZipPath;
    private final int port;

    public PackHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/pack.zip", this::handlePackRequest);
        httpServer.start();
        LOGGER.info("Resource pack HTTP server listening on 0.0.0.0:{}", port);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
            LOGGER.info("Resource pack HTTP server stopped");
        }
    }

    public void setCurrentPack(Path zipPath) {
        this.currentZipPath = zipPath;
    }

    private void handlePackRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Path zip = currentZipPath;
        if (zip == null || !Files.isRegularFile(zip)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] bytes = Files.readAllBytes(zip);
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
