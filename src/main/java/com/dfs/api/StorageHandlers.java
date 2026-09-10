package com.dfs.api;

import com.dfs.storage.ChunkStore;
import com.dfs.types.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

public class StorageHandlers {

    private static final Logger log = Logger.getLogger(StorageHandlers.class.getName());

    private final ChunkStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StorageHandlers(ChunkStore store) {
        this.store = store;
    }

    public void handleStoreChunk(HttpExchange exchange, String chunkId) throws IOException {
        byte[] data = exchange.getRequestBody().readAllBytes();
        long bytes = store.saveChunk(chunkId, data);
        log.info("store_chunk: " + chunkId + " (" + bytes + " bytes)");
        HttpKit.sendJson(exchange, 200, new StoreChunkResponse(true, (int) bytes));
    }

    public void handleGetChunk(HttpExchange exchange, String chunkId) throws IOException {
        byte[] data = store.readChunk(chunkId);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, data.length);
        try (var os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    public void handleDeleteChunk(HttpExchange exchange, String chunkId) throws IOException {
        boolean deleted = store.deleteChunk(chunkId);
        if (!deleted) {
            throw new ApiException(404, "Chunk not found: " + chunkId);
        }
        log.info("delete_chunk: " + chunkId);
        HttpKit.sendJson(exchange, 200, Map.of("deleted", true));
    }

    public void handleHealth(HttpExchange exchange) throws IOException {
        HttpKit.sendJson(exchange, 200,
            new HealthResponse("healthy", store.diskFreeGB(), store.chunkCount()));
    }
}