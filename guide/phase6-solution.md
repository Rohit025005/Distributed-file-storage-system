# Phase 6: Solution - Polish, Testing & Observability in Java

> Reference implementation for `phase6-instruction.md`. Try building it yourself first,
> then compare. This phase is mostly refactoring what already works — keep the tests
> from Phase 4/5 behavior green while you do it.

## 1. Typed Errors (`src/main/java/com/dfs/api/ApiException.java`)

```java
package com.dfs.api;

/** Expected failure carrying an HTTP status. Anything else maps to 500. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
```

Throw sites become self-documenting:

```java
throw new ApiException(404, "File not found: " + fileId);
throw new ApiException(400, "Missing chunk_id parameter");
throw new ApiException(405, "Method not allowed");
throw new ApiException(503, "No healthy storage nodes available");
```

## 2. Shared HTTP Kit (`src/main/java/com/dfs/api/HttpKit.java`)

One place for everything both servers were copy-pasting — plus request logging:

```java
package com.dfs.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.logging.Logger;

public final class HttpKit {

    private static final Logger LOG = Logger.getLogger(HttpKit.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpKit() {}

    @FunctionalInterface
    public interface Endpoint {
        void handle(HttpExchange exchange) throws IOException;
    }

    /**
     * Wrap one endpoint: logs method/path/status/latency, converts any throw
     * into a JSON error response. The single entry point for BOTH servers.
     */
    public static HttpHandler guard(String routeName, Endpoint endpoint) {
        return exchange -> {
            long startNanos = System.nanoTime();
            try {
                endpoint.handle(exchange);
            } catch (ApiException e) {
                respondError(exchange, e.status(), e.getMessage());
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                respondError(exchange, 500, message);
            } finally {
                long ms = (System.nanoTime() - startNanos) / 1_000_000;
                LOG.info(() -> routeName + " " + exchange.getRequestMethod()
                    + " " + exchange.getRequestURI()
                    + " (" + ms + " ms)");
            }
        };
    }

    /** Serialize any object as a JSON response body. */
    public static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); // length must match body exactly
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, new ErrorResponse(message, null));
    }

    private static void respondError(HttpExchange exchange, int status, String message) {
        try {
            sendError(exchange, status, message);
        } catch (IOException ignored) {
            // Response already committed — nothing else we can do.
        } finally {
            exchange.close();
        }
    }

    public static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }
}
```

Then delete the duplicated statics from both Mains and rewrite contexts as:

```java
server.createContext("/get_chunk", HttpKit.guard("get_chunk", exchange -> {
    requireMethod(exchange, "GET");                       // throws ApiException(405)
    String chunkId = HttpKit.queryParam(exchange, "chunk_id");
    requireParam(chunkId, "chunk_id");                    // throws ApiException(400)
    handlers.handleGetChunk(exchange, chunkId);
}));
```

with two tiny helpers in each Main:

```java
private static void requireMethod(HttpExchange ex, String method) {
    if (!method.equals(ex.getRequestMethod())) throw new ApiException(405, "Method not allowed");
}
private static void requireParam(String value, String name) {
    if (value == null || value.isBlank()) throw new ApiException(400, "Missing " + name + " parameter");
}
```

Convert store-level failures: `Store.GetFileMetadata/DeleteFile` and
`ChunkStore.ReadChunk` should throw `NoSuchFileException`-style conditions that handlers
translate to `ApiException(404, ...)`, keeping the Store layer HTTP-free.

## 3. Per-Chunk Locking (`ChunkStore` change)

```java
private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

private Object lockFor(String chunkId) {
    return locks.computeIfAbsent(chunkId, k -> new Object());
}

public long SaveChunk(String chunkId, byte[] data) throws IOException {
    Path target = pathFor(chunkId);
    synchronized (lockFor(chunkId)) {
        Files.write(target, data);
    }
    return data.length;
}

public boolean DeleteChunk(String chunkId) throws IOException {
    synchronized (lockFor(chunkId)) {
        return Files.deleteIfExists(pathFor(chunkId));
    }
}
```

**Documented choice:** writes/deletes are serialized per chunk ID; reads stay lock-free.
A reader may briefly hit a half-written file only if you skip atomic rename; to close
that gap entirely, write to `<id>.bin.tmp` inside the lock and
`Files.move(tmp, target, REPLACE_EXISTING)` before releasing it. Lock objects for deleted
chunks linger in the map — bounded by distinct chunk IDs ever touched on that node,
acceptable here and worth a comment.

## 4. Logging Setup (each server Main)

```java
public class Main {
    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "%1$tH:%1$tM:%1$tS %4$s %3$s %5$s%6$s%n");
    }
    // ...
}
```

Per-class loggers where events happen:

```java
private static final Logger LOG = Logger.getLogger(DfsClient.class.getName());
LOG.info("upload " + filePath.getFileName() + " (" + size + " bytes, "
         + chunks.size() + " chunks)");
LOG.warning("replica write failed on " + replica + ": " + e.getMessage());
```

## 5. Testable Server Factories

Extract construction so `main()` is thin and tests can boot clusters in-process.

`src/main/java/com/dfs/api/MetadataServer.java`:

```java
package com.dfs.api;

import com.dfs.metadata.HealthMonitor;
import com.dfs.metadata.Store;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

public final class MetadataServer {

    private MetadataServer() {}

    /** port 0 => OS picks an ephemeral port (read it back via getAddress()). */
    public static HttpServer start(int port, Store store, List<String> nodeAddresses,
                                   HealthMonitor monitor) throws IOException {
        MetadataHandlers handlers = new MetadataHandlers(store, nodeAddresses, monitor);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/create_file", HttpKit.guard("create_file", exchange -> {
            requireMethod(exchange, "POST");
            handlers.handleCreateFile(exchange);
        }));
        server.createContext("/get_file_metadata", HttpKit.guard("get_file_metadata", exchange -> {
            requireMethod(exchange, "GET");
            String fileId = HttpKit.queryParam(exchange, "file_id");
            if (fileId == null || fileId.isBlank()) {
                throw new ApiException(400, "Missing file_id parameter");
            }
            handlers.handleGetMetadata(exchange, fileId);
        }));
        server.createContext("/delete_file", HttpKit.guard("delete_file", exchange -> {
            requireMethod(exchange, "DELETE");
            String fileId = HttpKit.queryParam(exchange, "file_id");
            if (fileId == null || fileId.isBlank()) {
                throw new ApiException(400, "Missing file_id parameter");
            }
            handlers.handleDeleteFile(exchange, fileId);
        }));
        server.createContext("/list_files", HttpKit.guard("list_files", exchange -> {
            requireMethod(exchange, "GET");
            handlers.handleListFiles(exchange);
        }));
        server.createContext("/health", HttpKit.guard("health",
            handlers::handleHealth));

        server.setExecutor(null);
        server.start();
        return server;
    }

    private static void requireMethod(HttpExchange ex, String method) {
        if (!method.equals(ex.getRequestMethod())) {
            throw new ApiException(405, "Method not allowed");
        }
    }
}
```

`src/main/java/com/dfs/api/StorageServer.java` follows the same shape:
`start(int port, String nodeId, ChunkStore store)` registering the four storage contexts
with `HttpKit.guard`, returning the `HttpServer`.

Both Mains shrink to arg/env parsing + factory calls. Behavior is unchanged — verify
with the Phase-4 curl script before running the test suite.

## 6. Integration Test (`src/test/java/com/dfs/EndToEndIT.java`)

```java
package com.dfs;

import com.dfs.api.MetadataServer;
import com.dfs.api.StorageServer;
import com.dfs.client.DfsClient;
import com.dfs.chunker.Chunker;
import com.dfs.metadata.Store;
import com.dfs.storage.ChunkStore;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EndToEndIT {

    static HttpServer metadataServer;
    static final List<HttpServer> storageServers = new ArrayList<>();
    static DfsClient client;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startCluster() throws Exception {
        // PITFALL: ":memory:" does NOT work — our Store opens one connection PER
        // OPERATION, and each :memory: connection gets its own empty database.
        // Use a temp FILE database instead.
        Path dbFile = Files.createTempFile(tempDir, "metadata-", ".db");
        Store store = new Store(dbFile.toString());

        // Boot 4 storage nodes on ephemeral ports first so metadata knows their addresses.
        List<String> nodeAddresses = new ArrayList<>();
        List<ChunkStore> chunkStores = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Path chunksDir = tempDir.resolve("node" + i);
            ChunkStore chunkStore = new ChunkStore(chunksDir.toString()); // or ctor variant taking a dir
            chunkStores.add(chunkStore);
            HttpServer node = StorageServer.start(0, "node" + i, chunkStore);
            storageServers.add(node);
            nodeAddresses.add("localhost:" + node.getAddress().getPort());
        }

        metadataServer = MetadataServer.start(0, store, nodeAddresses, /*monitor*/ null);
        client = new DfsClient(
            "http://localhost:" + metadataServer.getAddress().getPort());

        // NOTE: if your HealthMonitor is non-null it must be told these addresses;
        // passing null means liveOnly() must tolerate absence (or pass a stub).
    }

    @AfterAll
    static void stopCluster() {
        storageServers.forEach(HttpServer::stop);
        if (metadataServer != null) metadataServer.stop(0);
    }

    @Test
    void uploadDownloadDeleteRoundTrip() throws Exception {
        // ~300KB of random data = several 64KB chunks
        byte[] original = new byte[300_123];
        new Random(42).nextBytes(original);
        Path source = tempDir.resolve("source.bin");
        Path restored = tempDir.resolve("restored.bin");
        Files.write(source, original);

        // sanity: chunker round-trips locally first (isolates DFS vs chunker bugs)
        var split = Chunker.splitFile(source, 64 * 1024);
        assertEquals((300_123 + 64 * 1024 - 1) / (64 * 1024), split.size());

        String fileId = client.upload(source);
        assertNotNull(fileId);

        client.download(fileId, restored);
        assertTrue(Files.exists(restored));
        assertArrayEquals(original, Files.readAllBytes(restored));   // byte-exact
        assertEquals(DfsClient.sha256(source), DfsClient.sha256(restored));

        client.delete(fileId);
        // metadata gone...
        Exception ex = assertThrows(Exception.class,
            () -> client.download(fileId, tempDir.resolve("nope.bin")));
        assertTrue(String.valueOf(ex.getMessage()).contains("HTTP"));
        // ...and no orphaned chunks anywhere
        for (ChunkStore cs : chunkStores) {
            assertEquals(0, cs.ChunkCount(), "chunks must be purged after delete");
        }
    }
}
```

Two small enablers referenced above:
- Give `ChunkStore` an alternate constructor taking an explicit dir path
  (tests inject `@TempDir`; production passes `data/<nodeId>/chunks`). Keep pathFor's
  regex validation identical.
- If `MetadataHandlers` requires a `HealthMonitor`, add a pass-through mode (e.g. accept
  nullable monitor meaning "all configured nodes alive") so tests don't need a poller.

Run with:

```bash
mvn test
```

## 7. Demo Scripts

`run-all.ps1` (PowerShell):

```powershell
# Boots metadata + 4 storage nodes for local demo. Run from dfs/.
mvn -q compile

Start-Process powershell -ArgumentList '-Command',
  "cd '$PWD'; mvn -q exec:java '-Dexec.mainClass=com.dfs.cmd.metadata.Main'"

foreach ($i in 1..4) {
    Start-Process powershell -ArgumentList '-Command',
      "cd '$PWD'; `$env:NODE_ID='node$i'; mvn -q exec:java '-Dexec.mainClass=com.dfs.cmd.storage.Main'"
}

# wait for cluster
do { Start-Sleep 1; $ok = $true; foreach ($p in 9090..9090) {} } until ($ok)
curl.exe http://localhost:9090/health
Write-Host "Cluster ready: metadata=9090 nodes=8081-8084"
```

`run-all.sh` (Git Bash/Linux):

```bash
#!/usr/bin/env bash
mvn -q compile
NODE_ID= mvn -q exec:java -Dexec.mainClass=com.dfs.cmd.metadata.Main &
for i in 1 2 3 4; do NODE_ID=node$i mvn -q exec:java -Dexec.mainClass=com.dfs.cmd.storage.Main & done
sleep 3
curl -s http://localhost:9090/health && echo " <- cluster ready"
wait
```

Demo flow for the README:

```bash
./run-all.sh
mvn exec:java -Dexec.mainClass=com.dfs.cmd.client.Main -Dexec.args="upload demo.zip"
# kill node2's terminal window
mvn exec:java -Dexec.mainClass=com.dfs.cmd.client.Main -Dexec.args="download <ID> out.zip"
Get-FileHash demo.zip, out.zip          # identical despite a dead node
```

## 8. README Skeleton

```markdown
# dfs — Distributed File System (Java)

Client → Metadata Server (:9090, SQLite) → Storage Nodes (:8081-8084, disk chunks).
64KB chunks, replication factor 2, round-robin placement, replica read failover.

## Architecture
   ┌────────┐  create_file   ┌──────────────┐  health poll ┌───────────────┐
   │ Client │ ─────────────► │ Metadata Srv │ ────────────►│ node1..node4  │
   │  CLI   │ ◄───────────── │   (SQLite)   │              │ chunk .bin    │
   └───┬────┘  plan/chunks   └──────────────┘              └──────▲────────┘
       │  raw chunk bytes (client pushes/pulls directly)          │
       └──────────────────────────────────────────────────────────┘

## Quickstart / API table / Design notes / Known limitations
(single metadata server = SPOF, no auth/TLS, best-effort repair, in-memory liveness)
```

## 9. Common Mistakes Checklist

| # | Mistake | Fix |
|---|---------|-----|
| 1 | `jdbc:sqlite::memory:` in tests → empty DB per operation | Temp-file DB (or switch Store to one long-lived connection) |
| 2 | Fixed ports in tests | Port 0 + `getAddress().getPort()` |
| 3 | Double-committed responses after error-path refactor | Only `guard`'s catch sends errors; endpoints send success once |
| 4 | Latency logged excluding error path | Measure in `finally` around whole guard body |
| 5 | Tests depend on leftover `./data` state | Everything under `@TempDir` |
| 6 | Locks removed when adding atomic-rename write path | Pick ONE scheme; document which parts are lock-free and why |

## 10. Verify Build

```bash
mvn clean verify        # compiles main + tests, runs the suite
mvn test                # integration test boots full cluster in-process

# manual regression before declaring done:
./run-all.ps1           # or run-all.sh
mvn exec:java -Dexec.mainClass=com.dfs.cmd.client.Main "-Dexec.args=upload demo.zip"
mvn exec:java -Dexec.mainClass=com.dfs.cmd.client.Main "-Dexec.args=ls"
# kill a node, download again, checksum match
```
