# Phase 4: Solution - Chunker + Client CLI in Java

> Reference implementation for `phase4-instruction.md`. Try building it yourself first,
> then compare.

## 1. Chunker (`src/main/java/com/dfs/chunker/Chunker.java`)

```java
package com.dfs.chunker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure byte-splitting logic. Knows nothing about HTTP, metadata, or nodes —
 * which makes it trivially unit-testable.
 */
public final class Chunker {

    private Chunker() {} // static utility class

    /**
     * Split a file into chunkSize-byte pieces (last piece may be smaller).
     * A zero-byte file yields an EMPTY list.
     */
    public static List<byte[]> splitFile(Path path, int chunkSize) throws IOException {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        List<byte[]> chunks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path)) {
            while (true) {
                byte[] chunk = in.readNBytes(chunkSize);
                if (chunk.length == 0) {
                    break; // EOF
                }
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /** Write chunks back-to-back into a new file (reverse of splitFile). */
    public static void joinChunks(List<byte[]> chunks, Path outputPath) throws IOException {
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            for (byte[] chunk : chunks) {
                out.write(chunk);
            }
        }
    }
}
```

## 2. HTTP Helper (`src/main/java/com/dfs/client/HttpUtil.java`)

```java
package com.dfs.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin wrapper over the JDK HttpClient.
 * Convention: non-2xx -> IOException carrying status + body, so callers can
 * just try/catch and never inspect raw status codes.
 */
public final class HttpUtil {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))   // dead nodes fail fast (crucial in Phase 5)
            .build();

    private HttpUtil() {}

    public static String postJson(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return bodyOrThrow(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static String getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return bodyOrThrow(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static String delete(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .DELETE()
                .build();
        return bodyOrThrow(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static void postBytes(String url, byte[] data) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();
        bodyOrThrow(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        return bytesOrThrow(CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static String bodyOrThrow(HttpResponse<String> response) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + response.uri() + ": " + response.body());
        }
        return response.body();
    }

    private static byte[] bytesOrThrow(HttpResponse<byte[]> response) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + response.uri());
        }
        return response.body();
    }
}
```

Note: `HttpClient.send` throws `InterruptedException` too — the client methods declare it
and the CLI top level handles both.

## 3. Client Logic (`src/main/java/com/dfs/client/DfsClient.java`)

```java
package com.dfs.client;

import com.dfs.chunker.Chunker;
import com.dfs.types.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Orchestrates metadata server + storage nodes to implement upload/download/delete/ls.
 */
public class DfsClient {

    private final String metadataUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DfsClient(String metadataUrl) {
        this.metadataUrl = metadataUrl;
    }

    /**
     * Upload a local file. Returns the fileId (the only handle you get!).
     */
    public String upload(Path filePath) throws Exception {
        long size = Files.size(filePath);
        int chunkSize = Constants.DEFAULT_CHUNK_SIZE;
        int chunkCount = (int) ((size + chunkSize - 1) / chunkSize); // ceil without floats

        // 1. Ask the metadata server to plan the file
        CreateFileRequest req =
            new CreateFileRequest(filePath.getFileName().toString(), size, chunkCount);
        CreateFileResponse resp = objectMapper.readValue(
            HttpUtil.postJson(metadataUrl + "/create_file", objectMapper.writeValueAsString(req)),
            CreateFileResponse.class);

        // 2. Split locally
        List<byte[]> chunks = Chunker.splitFile(filePath, chunkSize);
        if (chunks.size() != resp.chunks().size()) {
            throw new IOException("Chunk count mismatch: local=" + chunks.size()
                + ", assigned=" + resp.chunks().size());
        }

        // 3. Push each chunk: primary MUST succeed, replicas warn-only
        List<Chunk> plan = new ArrayList<>(resp.chunks());
        plan.sort(Comparator.comparingInt(Chunk::index));

        for (int i = 0; i < plan.size(); i++) {
            Chunk meta = plan.get(i);
            byte[] data = chunks.get(meta.index());

            storeStrict(meta.primaryNode(), meta.id(), data);   // primary: fail = abort
            for (String replica : meta.replicaNodes()) {
                try {
                    storeStrict(replica, meta.id(), data);
                } catch (IOException e) {
                    System.err.println("WARN: replica write failed on " + replica
                        + " for " + meta.id() + ": " + e.getMessage());
                }
            }
            System.out.printf("chunk %d/%d -> primary=%s replicas=%s%n",
                i + 1, plan.size(), meta.primaryNode(), meta.replicaNodes());
        }

        System.out.println("Uploaded as fileId: " + resp.fileId());
        return resp.fileId();
    }

    private void storeStrict(String nodeAddress, String chunkId, byte[] data)
            throws IOException, InterruptedException {
        HttpUtil.postBytes("http://" + nodeAddress + "/store_chunk?chunk_id=" + chunkId, data);
    }

    /**
     * Download a file by id and reconstruct it at outputPath.
     */
    public void download(String fileId, Path outputPath) throws Exception {
        GetFileMetadataResponse meta = objectMapper.readValue(
            HttpUtil.getJson(metadataUrl + "/get_file_metadata?file_id=" + fileId),
            GetFileMetadataResponse.class);

        List<Chunk> ordered = new ArrayList<>(meta.chunks());
        ordered.sort(Comparator.comparingInt(Chunk::index)); // don't trust JSON order

        List<byte[]> chunks = new ArrayList<>();
        for (Chunk c : ordered) {
            chunks.add(fetchWithFailover(c));
        }
        Chunker.joinChunks(chunks, outputPath);
        System.out.println("Downloaded " + fileId + " (" + ordered.size() + " chunks) -> "
            + outputPath);
    }

    /** Try primary first, then each replica in order; fail only when all fail. */
    private byte[] fetchWithFailover(Chunk c) throws IOException, InterruptedException {
        List<String> candidates = new ArrayList<>();
        candidates.add(c.primaryNode());
        candidates.addAll(c.replicaNodes());

        IOException last = null;
        for (String addr : candidates) {
            try {
                return HttpUtil.getBytes("http://" + addr + "/get_chunk?chunk_id=" + c.id());
            } catch (IOException e) {
                last = e;
                System.err.println("WARN: fetch of " + c.id() + " from " + addr
                    + " failed: " + e.getMessage());
            }
        }
        throw new IOException("All candidate nodes failed for chunk " + c.id(), last);
    }

    /**
     * Delete a file everywhere.
     *
     * ORDER MATTERS: fetch locations BEFORE deleting metadata — after delete_file
     * the location rows are gone and orphaned .bin files could never be found.
     */
    public void delete(String fileId) throws Exception {
        // 1. Snapshot who holds what (before it's gone!)
        Set<String> holders = new HashSet<>();
        GetFileMetadataResponse meta = objectMapper.readValue(
            HttpUtil.getJson(metadataUrl + "/get_file_metadata?file_id=" + fileId),
            GetFileMetadataResponse.class);
        for (Chunk c : meta.chunks()) {
            holders.add(c.primaryNode());
            holders.addAll(c.replicaNodes());
        }

        // 2. Delete metadata (returns chunk ids to purge)
        DeleteFileResponse resp = objectMapper.readValue(
            HttpUtil.delete(metadataUrl + "/delete_file?file_id=" + fileId),
            DeleteFileResponse.class);

        // 3. Fan out purges; individual failures are warnings
        for (String chunkId : resp.chunksToDelete()) {
            for (String holder : holders) {
                try {
                    HttpUtil.delete("http://" + holder + "/delete_chunk?chunk_id=" + chunkId);
                } catch (IOException e) {
                    System.err.println("WARN: purge of " + chunkId + " from " + holder
                        + " failed: " + e.getMessage());
                }
            }
        }
        System.out.println("Deleted " + fileId + " (" + resp.chunksToDelete().size()
            + " chunks from " + holders.size() + " nodes)");
    }

    /** List all files (for `dfs ls`). */
    public List<DfsFile> listFiles() throws Exception {
        return objectMapper.readValue(
            HttpUtil.getJson(metadataUrl + "/list_files"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, DfsFile.class));
    }

    /** SHA-256 helper so you can verify round-trips. */
    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
```

## 4. CLI (`src/main/java/com/dfs/cmd/client/Main.java`)

```java
package com.dfs.cmd.client;

import com.dfs.client.DfsClient;

import java.nio.file.Path;
import java.util.List;

public class Main {

    private static final String USAGE =
            """
                    Usage:
                      dfs upload <local-path>
                      dfs download <file-id> <output-path>
                      dfs delete <file-id>
                      dfs ls
                    Environment:
                      METADATA_URL   default http://localhost:9090
                    """;

    public static void main(String[] args) {
        String metadataUrl = System.getenv().getOrDefault("METADATA_URL", "http://localhost:9090");
        DfsClient client = new DfsClient(metadataUrl);

        try {
            if (args.length == 0) {
                usageAndExit();
            }
            switch (args[0]) {
                case "upload" -> {
                    requireArgs(args, 2);
                    client.upload(Path.of(args[1]));
                }
                case "download" -> {
                    requireArgs(args, 3);
                    client.download(args[1], Path.of(args[2]));
                }
                case "delete" -> {
                    requireArgs(args, 2);
                    client.delete(args[1]);
                }
                case "ls" -> printFiles(client);
                default -> usageAndExit();
            }
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();   // unwrap Jackson etc.
            System.err.println("ERROR: " + root.getMessage());
            System.exit(1);
        }
    }

    private static void printFiles(DfsClient client) throws Exception {
        List<DfsFile> files = client.listFiles();
        if (files.isEmpty()) {
            System.out.println("(no files)");
            return;
        }
        System.out.printf("%-38s %-24s %12s%n", "FILE ID", "NAME", "SIZE");
        for (DfsFile f : files) {
            String shortId = f.fileId().length() > 36 ? f.fileId().substring(0, 36) : f.fileId();
            System.out.printf("%-38s %-24s %12d%n", shortId, f.filename(), f.size());
        }
    }

    private static void requireArgs(String[] args, int expected) {
        if (args.length < expected) {
            usageAndExit();
        }
    }

    private static void usageAndExit() {
        System.err.print(USAGE);
        System.exit(1);
    }
}
```

Running via Maven:

```bash
mvn compile exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=upload test.bin"
```

## 5. Bugs Fixed From Earlier Drafts / Common Mistakes

| # | Mistake | Fix |
|---|---------|-----|
| 1 | Deleting metadata before capturing chunk locations → orphaned `.bin` files | Fetch metadata FIRST, then delete, then fan out purges |
| 2 | `(int) Math.ceil((double)size/chunkSize)` float rounding | Integer math: `(size + chunkSize - 1) / chunkSize` |
| 3 | Trusting JSON array order of chunks | Sort by `Chunk::index()` before joining |
| 4 | No connect timeout → hangs minutes on dead nodes | `connectTimeout(3s)` on shared HttpClient |
| 5 | Swallowed error bodies on non-2xx | Read body always; throw `IOException("HTTP n: body")` |
| 6 | Primary and replica failures treated the same | Primary fail = abort upload; replica fail = warn only |
| 7 | No verification that upload worked | SHA-256 original vs restored (see `sha256` helper) |

## 6. Verify Build

Full end-to-end walkthrough is in `phase4-instruction.md` §7. Minimum pass:

```bash
mvn compile

# cluster up: metadata (9090) + node1..node4 (8081-8084)

mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=upload test.bin"
# => Uploaded as fileId: <ID>; check data/node*/chunks/ hold the expected copies

mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=download <ID> restored.bin"
Get-FileHash test.bin, restored.bin      # PowerShell; sha256sum on Git Bash — must match

mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=ls"

# Failover smoke test (full treatment in Phase 5): kill one node holding a REPLICA,
# re-download — must still succeed. Kill a PRIMARY's node mid-list → also succeeds
# via replica thanks to fetchWithFailover.

mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=delete <ID>"
# verify: list_files empty AND no leftover .bin files on any node
```
