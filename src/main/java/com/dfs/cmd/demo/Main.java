package com.dfs.cmd.demo;

import com.dfs.api.HttpKit;
import com.dfs.api.MetadataHandlers;
import com.dfs.api.StorageHandlers;
import com.dfs.client.DfsClient;
import com.dfs.config.Config;
import com.dfs.metadata.HealthMonitor;
import com.dfs.metadata.Store;
import com.dfs.storage.ChunkStore;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

/**
 * Runs the whole system in a single JVM/terminal and drives a scripted
 * walkthrough (upload -> list -> download -> verify -> delete), then shuts
 * everything down. This is the "just show me it working" entry point --
 * no separate terminals, no manual CLI steps.
 *
 * Usage: mvn exec:java -Dexec.mainClass=com.dfs.cmd.demo.Main
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "[%1$tT] %4$s %5$s%6$s%n");

        Path dataDir = Path.of("data");
        deleteRecursive(dataDir);

        Config config = Config.loadFromFile("config/nodes.yaml");

        System.out.println("== starting 4 storage nodes (ports 8081-8084) ==");
        List<HttpServer> storageServers = List.of(
            startStorageNode("node1", 8081),
            startStorageNode("node2", 8082),
            startStorageNode("node3", 8083),
            startStorageNode("node4", 8084)
        );

        System.out.println("== starting metadata server (port 9090) ==");
        HealthMonitor monitor = new HealthMonitor(config.getNodeAddresses());
        Store store = new Store("./data/metadata.db");
        MetadataHandlers metaHandlers = new MetadataHandlers(store, config.getNodeAddresses(), monitor);
        HttpServer metadataServer = startMetadataServer(metaHandlers);

        try {
            awaitHealthy("http://localhost:9090/health");
            for (String addr : config.getNodeAddresses()) {
                awaitHealthy("http://" + addr + "/health");
            }
            monitor.start(10);
            awaitMonitorSeesAllUp(monitor, config.getNodeAddresses());
            System.out.println("== all nodes up, running walkthrough ==\n");

            runWalkthrough();

            System.out.println("\n== demo complete, shutting down ==");
        } finally {
            monitor.stop();
            metadataServer.stop(0);
            for (HttpServer s : storageServers) {
                s.stop(0);
            }
        }
    }

    private static void runWalkthrough() throws Exception {
        DfsClient client = new DfsClient("http://localhost:9090");

        Path input = Path.of("data", "demo-input.txt");
        Files.createDirectories(input.getParent());
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            content.append("line ").append(i).append(" - distributed file storage demo\n");
        }
        Files.writeString(input, content.toString());
        System.out.println("[1/5] created sample file (" + Files.size(input) + " bytes, spans multiple 64KB chunks)");

        System.out.println("\n[2/5] uploading...");
        String fileId = client.upload(input);
        System.out.println("      file id: " + fileId);

        System.out.println("\n[3/5] listing files on metadata server...");
        client.listFiles();

        Path output = Path.of("data", "demo-output.txt");
        System.out.println("\n[4/5] downloading + verifying integrity...");
        client.download(fileId, output);
        String inputHash = sha256(input);
        String outputHash = sha256(output);
        System.out.println("      sha256 match: " + inputHash.equals(outputHash)
            + (inputHash.equals(outputHash) ? "" : "  (MISMATCH! " + inputHash + " vs " + outputHash + ")"));

        System.out.println("\n[5/5] deleting file...");
        client.delete(fileId);
        System.out.println("      files remaining:");
        client.listFiles();
    }

    private static HttpServer startMetadataServer(MetadataHandlers handlers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);

        server.createContext("/create_file", exchange -> HttpKit.guard(exchange, h -> {
            if (!"POST".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            handlers.handleCreateFile(h);
        }));

        server.createContext("/get_file_metadata", exchange -> HttpKit.guard(exchange, h -> {
            if (!"GET".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            String fileId = HttpKit.queryParam(h, "file_id");
            if (fileId == null || fileId.isBlank()) {
                throw new com.dfs.api.ApiException(400, "Missing file_id parameter");
            }
            handlers.handleGetMetadata(h, fileId);
        }));

        server.createContext("/delete_file", exchange -> HttpKit.guard(exchange, h -> {
            if (!"DELETE".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            String fileId = HttpKit.queryParam(h, "file_id");
            if (fileId == null || fileId.isBlank()) {
                throw new com.dfs.api.ApiException(400, "Missing file_id parameter");
            }
            handlers.handleDeleteFile(h, fileId);
        }));

        server.createContext("/list_files", exchange -> HttpKit.guard(exchange, h -> {
            if (!"GET".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            handlers.handleListFiles(h);
        }));

        server.createContext("/health", exchange -> HttpKit.guard(exchange, handlers::handleHealth));

        server.setExecutor(null);
        server.start();
        return server;
    }

    private static HttpServer startStorageNode(String nodeId, int port) throws IOException {
        ChunkStore chunkStore = new ChunkStore(nodeId);
        StorageHandlers handlers = new StorageHandlers(chunkStore);

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
        return server;
    }

    private static void awaitHealthy(String healthUrl) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(healthUrl))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(150);
        }
        throw new IllegalStateException("Timed out waiting for " + healthUrl);
    }

    private static void awaitMonitorSeesAllUp(HealthMonitor monitor, List<String> addresses)
            throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (monitor.liveOnly(addresses).size() == addresses.size()) {
                return;
            }
            Thread.sleep(150);
        }
        throw new IllegalStateException(
            "Health monitor still reports nodes down: " + monitor.downOnly(addresses));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(path));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    // best effort cleanup
                }
            });
        }
    }
}
