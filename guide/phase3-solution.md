# Phase 3: Solution - Storage Node (Chunk Storage) in Java

> Reference implementation for `phase3-instruction.md`. Try building it yourself first,
> then compare.

## 1. Disk Logic (`src/main/java/com/dfs/storage/ChunkStore.java`)

```java
package com.dfs.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores chunk bytes on local disk under ./data/<nodeId>/chunks/<chunkId>.bin
 */
public class ChunkStore {

    /** Chunk IDs are generated as <uuid>_chunk_<n>; anything else is rejected. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_\\-]+");

    private final Path chunksDir;

    public ChunkStore(String nodeId) throws IOException {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId is required");
        }
        this.chunksDir = Path.of("data", nodeId, "chunks");
        Files.createDirectories(chunksDir);
    }

    /** Validate + resolve. All disk access goes through here. */
    private Path pathFor(String chunkId) {
        if (chunkId == null || !SAFE_ID.matcher(chunkId).matches()) {
            throw new IllegalArgumentException("Invalid chunk id: " + chunkId);
        }
        return chunksDir.resolve(chunkId + ".bin");
    }

    /**
     * Write chunk bytes to disk.
     * @return number of bytes stored
     */
    public long SaveChunk(String chunkId, byte[] data) throws IOException {
        Path target = pathFor(chunkId);
        Files.write(target, data);
        return data.length;
    }

    /**
     * Read chunk bytes from disk.
     * @throws NoSuchFileException if the chunk does not exist (maps to HTTP 404)
     */
    public byte[] ReadChunk(String chunkId) throws IOException {
        Path target = pathFor(chunkId);
        if (!Files.exists(target)) {
            throw new NoSuchFileException(chunkId);
        }
        return Files.readAllBytes(target);
    }

    /**
     * Delete a chunk from disk.
     * @return true if it existed and was deleted, false if it wasn't there
     */
    public boolean DeleteChunk(String chunkId) throws IOException {
        return Files.deleteIfExists(pathFor(chunkId));
    }

    /** Free space on the volume holding the chunks dir, in GB. */
    public double DiskFreeGB() {
        File dir = chunksDir.toFile();
        return dir.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
    }

    /** Number of .bin files currently stored. */
    public int ChunkCount() throws IOException {
        try (Stream<Path> files = Files.list(chunksDir)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".bin")).count();
        }
    }
}
```

Notes:
- `Files.write(...)` creates-or-truncates by default — exactly what we want for re-storing
  a chunk ID.
- If you want crash-safety against partially-written chunks, write to `<id>.bin.tmp` then
  `Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)`. Optional at this stage;
  Phase 6 discusses concurrency around this.

## 2. HTTP Handlers (`src/main/java/com/dfs/api/StorageHandlers.java`)

```java
package com.dfs.api;

import com.dfs.storage.ChunkStore;
import com.dfs.types.HealthResponse;
import com.dfs.types.StoreChunkResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public class StorageHandlers {

    private final String nodeId;
    private final ChunkStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StorageHandlers(String nodeId, ChunkStore store) {
        this.nodeId = nodeId;
        this.store = store;
    }

    /** Serialize any object as a JSON response body. */
    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); // length must match body exactly
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** All errors use the shared ErrorResponse shape. */
    public void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, new ErrorResponse(message, null));
    }

    /** Handle POST /store_chunk?chunk_id=... — body is RAW bytes. */
    public void handleStoreChunk(HttpExchange exchange, String chunkId) throws IOException {
        byte[] data = exchange.getRequestBody().readAllBytes(); // read fully BEFORE responding
        long bytesStored = store.SaveChunk(chunkId, data);
        sendJson(exchange, 200, new StoreChunkResponse(true, (int) bytesStored));
    }

    /** Handle GET /get_chunk?chunk_id=... — raw binary response. */
    public void handleGetChunk(HttpExchange exchange, String chunkId) throws IOException {
        byte[] data;
        try {
            data = store.ReadChunk(chunkId);
        } catch (java.nio.file.NoSuchFileException e) {
            sendError(exchange, 404, "Chunk not found: " + chunkId);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /** Handle DELETE /delete_chunk?chunk_id=... */
    public void handleDeleteChunk(HttpExchange exchange, String chunkId) throws IOException {
        boolean deleted = store.DeleteChunk(chunkId);
        if (!deleted) {
            sendError(exchange, 404, "Chunk not found: " + chunkId);
            return;
        }
        sendJson(exchange, 200, Map.of("deleted", true));
    }

    /** Handle GET /health — real disk free space and chunk count. */
    public void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200,
            new HealthResponse("healthy", store.DiskFreeGB(), store.ChunkCount()));
    }
}
```

Note: `ErrorResponse`, `StoreChunkResponse`, `HealthResponse` come from
`com.dfs.types.*`; the wildcard import covers them.

## 3. Main Entry Point (`src/main/java/com/dfs/cmd/storage/Main.java`)

```java
package com.dfs.cmd.storage;

import com.dfs.api.StorageHandlers;
import com.dfs.internal.config.Config;
import com.dfs.storage.ChunkStore;
import com.dfs.types.Node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

public class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    private interface Endpoint {
        void handle(HttpExchange exchange) throws IOException;
    }

    public static void main(String[] args) throws Exception {
        // 1. Which node am I?
        String nodeId = System.getenv("NODE_ID");
        if (args.length > 0 && !args[0].isBlank()) {
            nodeId = args[0];
        }
        if (nodeId == null || nodeId.isBlank()) {
            System.err.println("Usage: NODE_ID=node1 mvn exec:java -Dexec.mainClass=com.dfs.cmd.storage.Main");
            System.exit(1);
        }

        // 2. Find my port from config/nodes.yaml
        Config config = Config.loadFromFile("config/nodes.yaml");
        Node me = config.getNodes().stream()
                .filter(n -> n.id().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unknown node id: " + nodeId));
        int port = Integer.parseInt(me.address().split(":")[1]);

        // 3. Wire up storage + handlers
        ChunkStore store = new ChunkStore(nodeId);
        StorageHandlers handlers = new StorageHandlers(nodeId, store);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/store_chunk", exchange -> guard(exchange, () -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                handlers.sendError(exchange, 405, "Method not allowed");
                return;
            }
            String chunkId = queryParam(exchange, "chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                handlers.sendError(exchange, 400, "Missing chunk_id parameter");
                return;
            }
            handlers.handleStoreChunk(exchange, chunkId);
        }));

        server.createContext("/get_chunk", exchange -> guard(exchange, () -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                handlers.sendError(exchange, 405, "Method not allowed");
                return;
            }
            String chunkId = queryParam(exchange, "chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                handlers.sendError(exchange, 400, "Missing chunk_id parameter");
                return;
            }
            handlers.handleGetChunk(exchange, chunkId);
        }));

        server.createContext("/delete_chunk", exchange -> guard(exchange, () -> {
            if (!"DELETE".equals(exchange.getRequestMethod())) {
                handlers.sendError(exchange, 405, "Method not allowed");
                return;
            }
            String chunkId = queryParam(exchange, "chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                handlers.sendError(exchange, 400, "Missing chunk_id parameter");
                return;
            }
            handlers.handleDeleteChunk(exchange, chunkId);
        }));

        server.createContext("/health", exchange -> guard(exchange,
                () -> handlers.handleHealth(exchange)));

        server.setExecutor(null);
        server.start();

        System.out.println("Storage node '" + nodeId + "' started on port " + port);
        System.out.println("Chunks dir: data/" + nodeId + "/chunks/");
    }

    /** Run one request; convert failures into JSON error responses. */
    private static void guard(HttpExchange exchange, Endpoint endpoint) {
        try {
            endpoint.handle(exchange);
        } catch (IllegalArgumentException e) {          // bad input (e.g. unsafe chunk id)
            respondQuietly(exchange, 400, e.getMessage());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            respondQuietly(exchange, 500, message);
        }
    }

    private static void respondQuietly(HttpExchange exchange, int status, String message) {
        try {
            byte[] body = JSON.writeValueAsBytes(Map.of("error", message));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
            // Response already committed — nothing else we can do.
        } finally {
            exchange.close();
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return kv[1];
            }
        }
        return null;
    }
}
```

## 4. Bugs Fixed From Earlier Drafts / Common Mistakes

| # | Mistake | Fix |
|---|---------|-----|
| 1 | Building file paths directly from `chunk_id` query param | Whitelist regex `[A-Za-z0-9_\-]+` in `pathFor()` — blocks `../` traversal |
| 2 | Treating `/store_chunk` as multipart/form-data | Client posts raw bytes; read with `readAllBytes()` |
| 3 | Unknown chunk returning 500 | `NoSuchFileException` → explicit 404 with `ErrorResponse` |
| 4 | Responding before consuming request body | Read body first, then write response |
| 5 | Hardcoding each node's port | Look up own address in `config/nodes.yaml` by `NODE_ID` |
| 6 | Same `NODE_ID` in two terminals → "Address already in use" | One terminal per node; check with `netstat -ano \| findstr 8081` on Windows |

## 5. Verify Build

```bash
mvn compile

# Terminal 1..4 (from dfs/):
#   PowerShell: $env:NODE_ID="node1"; mvn exec:java "-Dexec.mainClass=com.dfs.cmd.storage.Main"
#   Bash:       NODE_ID=node1 mvn exec:java -Dexec.mainClass=com.dfs.cmd.storage.Main
```

Full curl walkthrough is in `phase3-instruction.md` §6 — key checks:

1. `POST --data-binary` a small file → `{"stored":true,"bytes":11}`; verify the `.bin`
   file exists under `data/node1/chunks/`.
2. `GET /get_chunk` returns identical bytes (`fc` compare or checksum).
3. `GET /health` shows `chunksStored` incrementing and a plausible real `diskFreeGB`.
4. `chunk_id=../oops` → 400 JSON error, nothing written outside `data/`.
5. Repeat on ports 8082–8084 to confirm all four nodes run concurrently with separate
   directories.
