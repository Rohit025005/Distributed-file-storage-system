package com.dfs.api;

import com.dfs.metadata.HealthMonitor;
import com.dfs.metadata.Store;
import com.dfs.types.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class MetadataHandlers {

    private static final Logger log = Logger.getLogger(MetadataHandlers.class.getName());

    private final Store store;
    private final List<String> configuredNodes;
    private final HealthMonitor monitor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetadataHandlers(Store store, List<String> configuredNodes, HealthMonitor monitor) {
        this.store = store;
        this.configuredNodes = configuredNodes;
        this.monitor = monitor;
    }

    public void handleCreateFile(HttpExchange exchange) throws IOException {
        CreateFileRequest req = objectMapper.readValue(HttpKit.readBody(exchange), CreateFileRequest.class);
        List<String> liveNodes = monitor.liveOnly(configuredNodes);
        if (liveNodes.isEmpty()) {
            throw new ApiException(503, "No healthy storage nodes available");
        }
        CreateFileResponse resp = store.CreateFile(req, liveNodes);
        log.info("create_file: " + resp.fileId() + " (" + resp.chunks().size() + " chunks)");
        HttpKit.sendJson(exchange, 200, resp);
    }

    public void handleGetMetadata(HttpExchange exchange, String fileId) throws IOException {
        HttpKit.sendJson(exchange, 200, store.GetFileMetadata(fileId));
    }

    public void handleDeleteFile(HttpExchange exchange, String fileId) throws IOException {
        DeleteFileResponse resp = store.DeleteFile(fileId);
        log.info("delete_file: " + fileId + " (" + resp.chunksToDelete().size() + " chunks)");
        HttpKit.sendJson(exchange, 200, resp);
    }

    public void handleListFiles(HttpExchange exchange) throws IOException {
        HttpKit.sendJson(exchange, 200, store.ListFiles());
    }

    public void handleHealth(HttpExchange exchange) throws IOException {
        HttpKit.sendJson(exchange, 200, Map.of("status", "healthy"));
    }
}