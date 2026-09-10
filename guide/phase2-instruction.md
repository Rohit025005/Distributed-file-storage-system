# Phase 2: Metadata Server (SQLite + HTTP API) - Java Version

## Objective
Build the metadata server that tracks files, chunks, and which nodes hold each chunk. This is the "brain" of the DFS — no actual file bytes live here, just bookkeeping.

## 1. Database Schema (`src/main/java/com/dfs/metadata/Schema.java`)

Create a class that holds the schema SQL as constants (one statement per table):

```java
package com.dfs.metadata;

/**
 * Database schema constants for the metadata store.
 */
public final class Schema {
    private Schema() {} // prevent instantiation

    public static final String FILES_SQL =
        "CREATE TABLE IF NOT EXISTS files (" +
        "    file_id     TEXT PRIMARY KEY," +
        "    filename    TEXT NOT NULL," +
        "    size        INTEGER NOT NULL," +
        "    chunk_size  INTEGER NOT NULL," +
        "    created_at  DATETIME NOT NULL" +
        ")";

    public static final String CHUNKS_SQL =
        "CREATE TABLE IF NOT EXISTS chunks (" +
        "    id      TEXT PRIMARY KEY," +
        "    file_id TEXT NOT NULL," +
        "    idx     INTEGER NOT NULL," +
        "    size    INTEGER NOT NULL," +
        "    FOREIGN KEY (file_id) REFERENCES files(file_id) ON DELETE CASCADE" +
        ")";

    public static final String CHUNK_LOCATIONS_SQL =
        "CREATE TABLE IF NOT EXISTS chunk_locations (" +
        "    chunk_id     TEXT NOT NULL," +
        "    node_address TEXT NOT NULL," +
        "    is_primary   BOOLEAN NOT NULL," +
        "    PRIMARY KEY (chunk_id, node_address)," +
        "    FOREIGN KEY (chunk_id) REFERENCES chunks(id) ON DELETE CASCADE" +
        ")";

    /** All schema statements, in dependency order. */
    public static final String[] ALL_SQL = {
        FILES_SQL,
        CHUNKS_SQL,
        CHUNK_LOCATIONS_SQL
    };
}
```

**Watch out:** `private Schema() { // comment }` puts the closing brace *inside* the line
comment and will not compile. Keep `{}` on one line and put the comment after it.

## 2. New Type: `DfsFile` (`src/main/java/com/dfs/types/DfsFile.java`)

`GET /list_files` needs a summary row per file. Do **not** name it `File` — that clashes
with `java.io.File`. Create `src/main/java/com/dfs/types/DfsFile.java`
(one public top-level type per `.java` file in Java!):

```java
package com.dfs.types;

/**
 * Summary entry for one file in the DFS namespace (used by GET /list_files).
 */
public record DfsFile(
    String fileId,
    String filename,
    long size,
    int chunkSize,
    String createdAt   // ISO-8601 string; keeps JSON serialization trivial
) {}
```

Why `createdAt` is a `String`: we store `Instant.now().toString()` in SQLite and read it
back with `getString(...)`. Returning `LocalDateTime` would require registering Jackson's
`JavaTimeModule` just to serialize it — not worth it here.

## 3. Store Layer (`src/main/java/com/dfs/metadata/Store.java`)

Key design decisions (all fix real bugs — see §7 "Pitfalls"):

1. **One connection per operation** via `newConnection()` — no shared mutable connection field.
2. **Transactions**: `CreateFile` inserts into 3 tables; wrap it with
   `setAutoCommit(false)` / `commit()` / `rollback()` so a failure can never leave a
   half-written file behind.
3. **Real round-robin**: chunk `i`'s primary is `nodeAddrs.get(i % N)`, replicas are the
   next nodes after the primary (wrapping). Never hardcode primary = first node.
4. **Immutable records**: build `Chunk` objects only after gathering all locations —
   records cannot be mutated after construction.

```java
package com.dfs.metadata;

import com.dfs.types.*;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Store layer for managing metadata in SQLite.
 * Handles all database operations for files, chunks, and chunk locations.
 */
public class Store {
    private final String dbPath;

    public Store(String dbPath) {
        this.dbPath = dbPath;
        open();
    }

    /** Create parent dirs, load the driver, run schema migration once. */
    private void open() {
        try {
            File dbFile = new File(dbPath);
            if (dbFile.getParentFile() != null) {
                dbFile.getParentFile().mkdirs();
            }

            Class.forName("org.sqlite.JDBC");

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
                for (String sql : Schema.ALL_SQL) {
                    stmt.execute(sql);
                }
            }
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to open database", e);
        }
    }

    /** Fresh connection per operation, foreign keys enabled. */
    private Connection newConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    /**
     * Create a new file entry with chunk assignments.
     * @param req       CreateFileRequest with filename, size, chunkCount
     * @param nodeAddrs List of node addresses for round-robin assignment
     * @return CreateFileResponse with fileId and chunk assignments
     */
    public CreateFileResponse CreateFile(CreateFileRequest req, List<String> nodeAddrs) {
        if (nodeAddrs.isEmpty()) {
            throw new RuntimeException("No storage nodes configured");
        }

        String fileId = UUID.randomUUID().toString();
        int chunkSize = Constants.DEFAULT_CHUNK_SIZE;
        long fileSize = req.size();
        int chunkCount = req.chunkCount();
        // Can't have more replica copies than distinct remaining nodes.
        int maxReplicas = Math.min(Constants.REPLICATION_FACTOR - 1, nodeAddrs.size() - 1);

        try (Connection conn = newConnection()) {
            conn.setAutoCommit(false); // all-or-nothing across files/chunks/locations
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO files (file_id, filename, size, chunk_size, created_at) VALUES (?, ?, ?, ?, ?)")) {
                    pstmt.setString(1, fileId);
                    pstmt.setString(2, req.filename());
                    pstmt.setLong(3, fileSize);
                    pstmt.setInt(4, chunkSize);
                    pstmt.setString(5, Instant.now().toString());
                    pstmt.executeUpdate();
                }

                List<Chunk> chunks = new ArrayList<>();
                int remainingSize = (int) fileSize;
                int chunkIdx = 0;

                while (remainingSize > 0 && chunkIdx < chunkCount) {
                    int currentChunkSize = Math.min(chunkSize, remainingSize);
                    String chunkId = fileId + "_chunk_" + chunkIdx;

                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "INSERT INTO chunks (id, file_id, idx, size) VALUES (?, ?, ?, ?)")) {
                        pstmt.setString(1, chunkId);
                        pstmt.setString(2, fileId);
                        pstmt.setInt(3, chunkIdx);
                        pstmt.setInt(4, currentChunkSize);
                        pstmt.executeUpdate();
                    }

                    // Round-robin: primary rotates per chunk, replicas follow it (wrapping).
                    // Example with 4 nodes, factor=2:
                    //   chunk 0 -> primary node1, replica node2
                    //   chunk 1 -> primary node2, replica node3
                    //   chunk 2 -> primary node3, replica node4
                    //   chunk 3 -> primary node4, replica node1
                    String primaryNode = nodeAddrs.get(chunkIdx % nodeAddrs.size());
                    List<String> replicaNodes = new ArrayList<>();
                    for (int i = 1; i <= maxReplicas; i++) {
                        replicaNodes.add(nodeAddrs.get((chunkIdx + i) % nodeAddrs.size()));
                    }

                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "INSERT INTO chunk_locations (chunk_id, node_address, is_primary) VALUES (?, ?, ?)")) {
                        pstmt.setString(1, chunkId);
                        pstmt.setString(2, primaryNode);
                        pstmt.setBoolean(3, true);
                        pstmt.executeUpdate();

                        for (String replica : replicaNodes) {
                            pstmt.setString(1, chunkId);
                            pstmt.setString(2, replica);
                            pstmt.setBoolean(3, false);
                            pstmt.executeUpdate();
                        }
                    }

                    chunks.add(new Chunk(chunkId, chunkIdx, currentChunkSize, primaryNode, replicaNodes));

                    remainingSize -= currentChunkSize;
                    chunkIdx++;
                }

                conn.commit();
                return new CreateFileResponse(fileId, chunks);
            } catch (SQLException e) {
                conn.rollback(); // undo any partial inserts
                throw e;
            } finally {
                conn.setAutoCommit(true); // reset before returning pooled/closed conn
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create file", e);
        }
    }

    /**
     * Get file metadata including chunk locations.
     * @param fileID The file ID to look up
     * @return GetFileMetadataResponse with file details and chunks
     */
    public GetFileMetadataResponse GetFileMetadata(String fileID) {
        try (Connection conn = newConnection()) {
            String filename;
            long size;
            int chunkSize;

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT filename, size, chunk_size FROM files WHERE file_id = ?")) {
                pstmt.setString(1, fileID);
                ResultSet rs = pstmt.executeQuery();
                if (!rs.next()) {
                    throw new RuntimeException("File not found: " + fileID);
                }
                filename = rs.getString("filename");
                size = rs.getLong("size");
                chunkSize = rs.getInt("chunk_size");
            }

            // The join returns one row PER LOCATION. Group them by chunk index:
            // the row with is_primary = 1 gives the primary, the rest are replicas.
            Map<Integer, String> chunkIds = new LinkedHashMap<>();
            Map<Integer, Integer> chunkSizes = new LinkedHashMap<>();
            Map<Integer, String> primaries = new HashMap<>();
            Map<Integer, List<String>> replicas = new HashMap<>();

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT c.id, c.idx, c.size, cl.node_address, cl.is_primary " +
                    "FROM chunks c " +
                    "JOIN chunk_locations cl ON c.id = cl.chunk_id " +
                    "WHERE c.file_id = ? ORDER BY c.idx")) {
                pstmt.setString(1, fileID);
                ResultSet rs = pstmt.executeQuery();

                while (rs.next()) {
                    int idx = rs.getInt("idx");
                    chunkIds.putIfAbsent(idx, rs.getString("id"));
                    chunkSizes.putIfAbsent(idx, rs.getInt("size"));
                    if (rs.getBoolean("is_primary")) {
                        primaries.put(idx, rs.getString("node_address"));
                    } else {
                        replicas.computeIfAbsent(idx, k -> new ArrayList<>())
                                .add(rs.getString("node_address"));
                    }
                }
            }

            // Records are immutable — assemble each Chunk once, after all rows are read.
            List<Chunk> chunks = new ArrayList<>();
            for (int idx : chunkIds.keySet()) {
                chunks.add(new Chunk(
                    chunkIds.get(idx),
                    idx,
                    chunkSizes.get(idx),
                    primaries.getOrDefault(idx, ""),
                    replicas.getOrDefault(idx, List.of())
                ));
            }

            return new GetFileMetadataResponse(fileID, filename, size, chunkSize, chunks);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get file metadata", e);
        }
    }

    /**
     * Delete a file and return chunk IDs for cleanup on storage nodes.
     * @param fileID The file ID to delete
     * @return DeleteFileResponse confirming deletion
     */
    public DeleteFileResponse DeleteFile(String fileID) {
        try (Connection conn = newConnection()) {
            List<String> chunksToDelete = new ArrayList<>();

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT id FROM chunks WHERE file_id = ?")) {
                pstmt.setString(1, fileID);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    chunksToDelete.add(rs.getString("id"));
                }
            }

            if (chunksToDelete.isEmpty()) {
                throw new RuntimeException("File not found: " + fileID);
            }

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "DELETE FROM files WHERE file_id = ?")) {
                pstmt.setString(1, fileID);
                pstmt.executeUpdate(); // cascades to chunks and chunk_locations
            }

            return new DeleteFileResponse(true, chunksToDelete);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    /**
     * List all files in the metadata store.
     * @return List of DfsFile summaries
     */
    public List<DfsFile> ListFiles() {
        try (Connection conn = newConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT file_id, filename, size, chunk_size, created_at FROM files ORDER BY created_at")) {

            List<DfsFile> files = new ArrayList<>();
            while (rs.next()) {
                files.add(new DfsFile(
                    rs.getString("file_id"),
                    rs.getString("filename"),
                    rs.getLong("size"),
                    rs.getInt("chunk_size"),
                    rs.getString("created_at")
                ));
            }
            return files;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list files", e);
        }
    }
}
```

## 4. HTTP Handlers (`src/main/java/com/dfs/api/MetadataHandlers.java`)

Use Java's built-in `com.sun.net.httpserver.HttpServer`. Centralize response writing in
`sendJson` / `sendError` helpers:

```java
package com.dfs.api;

import com.dfs.metadata.Store;
import com.dfs.types.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MetadataHandlers {

    private final Store store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetadataHandlers(Store store) {
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

    /** Handle POST /create_file */
    public void handleCreateFile(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }

        CreateFileRequest req = objectMapper.readValue(sb.toString(), CreateFileRequest.class);

        // TODO (Phase 5): load from config/nodes.yaml and skip unhealthy nodes
        List<String> nodeAddrs = Arrays.asList(
            "localhost:8081", "localhost:8082", "localhost:8083", "localhost:8084");

        sendJson(exchange, 200, store.CreateFile(req, nodeAddrs));
    }

    /** Handle GET /get_file_metadata */
    public void handleGetMetadata(HttpExchange exchange, String fileId) throws IOException {
        sendJson(exchange, 200, store.GetFileMetadata(fileId));
    }

    /** Handle DELETE /delete_file */
    public void handleDeleteFile(HttpExchange exchange, String fileId) throws IOException {
        sendJson(exchange, 200, store.DeleteFile(fileId));
    }

    /** Handle GET /list_files */
    public void handleListFiles(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, store.ListFiles());
    }

    /** Handle GET /health */
    public void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, Map.of("status", "healthy"));
    }
}
```

## 5. Wire It Up (`src/main/java/com/dfs/cmd/metadata/Main.java`)

Main should stay thin: parse query params, route to handlers, and convert any exception
into a proper JSON error response.

**HTTP rule to remember:** once you call `sendResponseHeaders(status, -1)` you told the
client there is NO body — writing to `getResponseBody()` afterwards fails or is silently
dropped. Always pass the real content length, then write the body.

```java
package com.dfs.cmd.metadata;

import com.dfs.api.MetadataHandlers;
import com.dfs.metadata.Store;
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

    public static void main(String[] args) throws IOException {
        Store store = new Store("./data/metadata.db");
        MetadataHandlers handlers = new MetadataHandlers(store);

        HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);

        server.createContext("/create_file", exchange -> guard(exchange, () -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                handlers.sendError(exchange, 405, "Method not allowed");
                return;
            }
            handlers.handleCreateFile(exchange);
        }));

        server.createContext("/get_file_metadata", exchange -> guard(exchange, () -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                handlers.sendError(exchange, 405, "Method not allowed");
                return;
            }
            String fileId = queryParam(exchange, "file_id");
            if (fileId == null || fileId.isBlank()) {
                handlers.sendError(exchange, 400, "Missing file_id parameter");
                return;
            }
            handlers.handleGetMetadata(exchange, fileId);
        }));

        server.createContext("/delete_file", exchange -> guard(exchange, () -> {
            if (!"DELETE".equals(exchange.getRequestMethod())) {
                handlers.sendError(exchange, 405, "Method not allowed");
                return;
            }
            String fileId = queryParam(exchange, "file_id");
            if (fileId == null || fileId.isBlank()) {
                handlers.sendError(exchange, 400, "Missing file_id parameter");
                return;
            }
            handlers.handleDeleteFile(exchange, fileId);
        }));

        server.createContext("/list_files", exchange -> guard(exchange, () -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                handlers.sendError(exchange, 405, "Method not allowed");
                return;
            }
            handlers.handleListFiles(exchange);
        }));

        server.createContext("/health", exchange -> guard(exchange,
            () -> handlers.handleHealth(exchange)));

        server.setExecutor(null); // default executor is fine for now
        server.start();

        System.out.println("Metadata server started on port 9090");
        System.out.println("Data directory: ./data/metadata.db");
    }

    /** Run one request; convert any failure into a JSON error response. */
    private static void guard(HttpExchange exchange, Endpoint endpoint) {
        try {
            endpoint.handle(exchange);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            int status = message.startsWith("File not found") ? 404 : 500;
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

## 6. Dependencies (pom.xml)

Make sure `pom.xml` contains **all three** of these (the SQLite driver is easy to forget —
without it you get `ClassNotFoundException: org.sqlite.JDBC`; jackson-databind comes
transitively but listing it explicitly is clearer):

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.1.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.15.2</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <version>2.15.2</version>
</dependency>
```

Gson is optional — this phase uses Jackson everywhere, so you can drop it to keep things
simple (pick ONE json library per project).

## 7. Pitfalls To Avoid (bugs found in earlier drafts)

These are the classic mistakes — check your implementation against each one:

1. **Fake round-robin**: assigning every chunk `nodeAddrs.get(0)` as primary defeats the
   whole point. Primary must rotate: `chunkIdx % nodeAddrs.size()`.
2. **Broken replica bounds**: `i < REPLICATION_FACTOR - 1 && i < nodeAddrs.size() - 1`
   conflates two different limits. Compute `maxReplicas = min(factor - 1, nodes - 1)` once,
   then loop `for i in 1..maxReplicas`.
3. **No transaction around CreateFile**: it writes 1 `files` row + N `chunks` rows + M
   `chunk_locations` rows. Without `setAutoCommit(false)/commit()/rollback()` a mid-loop
   crash leaves an orphaned file record with missing chunks.
4. **Mutating immutable records**: Java `record`s have no setters. You can't
   "`chunk.setPrimary(...)`" or call nonexistent getters like `getReplicaNodes()`.
   Collect data first (maps keyed by chunk index), then construct each record once.
5. **Double-adding results**: building a `chunks` list inside the row loop AND returning
   `chunkMap.values()` produces duplicated/mixed data. Return exactly one assembled list.
6. **`File` name clash**: a `types.File` record collides with `java.io.File` in the same
   compilation unit imports. Name it `DfsFile`.
7. **Body after `sendResponseHeaders(-1)`**: `-1` means "no response body"; subsequent
   writes are invalid. Pass real byte length, then write.
8. **Missing imports**: `StandardCharsets`, `Arrays`, `Map`, etc. fail to compile if you
   forget them — compile early, compile often (`mvn compile`).
9. **JSON property naming**: Jackson maps record components verbatim, so the request body
   must use `"chunkCount"` (camelCase). Either test with camelCase in curl, or annotate the
   record with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` to accept
   `"chunk_count"`.
10. **One public type per file**: multiple top-level `record`s in a single `.java` file
    won't compile. Give each type its own file under `types/`.

## 8. Checklist for Phase 2

- [ ] SQLite schema created and migrations run on startup
- [ ] Store layer with transactional CreateFile
- [ ] Node assignment logic (round-robin, respects ReplicationFactor)
- [ ] GetFileMetadata correctly groups primary + replicas per chunk
- [ ] All 5 HTTP endpoints implemented and manually tested with curl
- [ ] Errors return JSON (ErrorResponse shape) with correct status codes (400/404/405/500)
- [ ] Unit tests for Store (in-memory `jdbc:sqlite::memory:`)

### Manual verification script

```bash
mvn compile
mvn exec:java -Dexec.mainClass="com.dfs.cmd.metadata.Main"

# Health
curl http://localhost:9090/health

# Create (note camelCase keys)
curl -X POST http://localhost:9090/create_file \
  -H "Content-Type: application/json" \
  -d '{"filename":"test.txt","size":262144,"chunkCount":4}'

# Check the response: 4 chunks, primaries rotating across the 4 nodes,
# each chunk having exactly 1 replica, and no node holding both copies
# of the same chunk. Then:
curl "http://localhost:9090/get_file_metadata?file_id=<file_id>"
curl http://localhost:9090/list_files
curl -X DELETE "http://localhost:9090/delete_file?file_id=<file_id>"

# Error paths
curl "http://localhost:9090/get_file_metadata?file_id=nope"          # expect 404 + JSON error
curl "http://localhost:9090/get_file_metadata"                       # expect 400 + JSON error
curl -X GET http://localhost:9090/create_file                        # expect 405 + JSON error
```
