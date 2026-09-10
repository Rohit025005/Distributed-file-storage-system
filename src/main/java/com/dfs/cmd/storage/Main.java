package com.dfs.cmd.storage;

import com.dfs.api.HttpKit;
import com.dfs.api.StorageHandlers;
import com.dfs.config.Config;
import com.dfs.storage.ChunkStore;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.LogManager;

public class Main {

    public static void main(String[] args) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "[%1$tT] %4$s %5$s%6$s%n");

        String rawId = System.getenv("NODE_ID");
        if (rawId == null && args.length > 0) {
            rawId = args[0];
        }
        if (rawId == null || rawId.isBlank()) {
            System.err.println("NODE_ID required (env or first arg)");
            System.exit(1);
            return;
        }
        final String nodeId = rawId;

        Config config = Config.loadFromFile("config/nodes.yaml");
        String address = config.getNodes().stream()
            .filter(n -> n.id().equals(nodeId))
            .map(n -> n.address())
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Unknown node: " + nodeId));

        int port = Integer.parseInt(address.split(":")[1]);

        ChunkStore store = new ChunkStore(nodeId);
        StorageHandlers handlers = new StorageHandlers(store);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/store_chunk", exchange -> HttpKit.guard(exchange, h -> {
            if (!"POST".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            String chunkId = HttpKit.queryParam(h, "chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                throw new com.dfs.api.ApiException(400, "Missing chunk_id parameter");
            }
            handlers.handleStoreChunk(h, chunkId);
        }));

        server.createContext("/get_chunk", exchange -> HttpKit.guard(exchange, h -> {
            if (!"GET".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            String chunkId = HttpKit.queryParam(h, "chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                throw new com.dfs.api.ApiException(400, "Missing chunk_id parameter");
            }
            handlers.handleGetChunk(h, chunkId);
        }));

        server.createContext("/delete_chunk", exchange -> HttpKit.guard(exchange, h -> {
            if (!"DELETE".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            String chunkId = HttpKit.queryParam(h, "chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                throw new com.dfs.api.ApiException(400, "Missing chunk_id parameter");
            }
            handlers.handleDeleteChunk(h, chunkId);
        }));

        server.createContext("/health", exchange -> HttpKit.guard(exchange, handlers::handleHealth));

        server.setExecutor(null);
        server.start();

        System.out.println("Storage node " + nodeId + " started on port " + port);
    }
}