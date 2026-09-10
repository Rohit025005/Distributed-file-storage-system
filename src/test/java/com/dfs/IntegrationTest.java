package com.dfs;

import com.dfs.api.HttpKit;
import com.dfs.api.MetadataHandlers;
import com.dfs.api.StorageHandlers;
import com.dfs.chunker.Chunker;
import com.dfs.client.DfsClient;
import com.dfs.metadata.HealthMonitor;
import com.dfs.metadata.Store;
import com.dfs.storage.ChunkStore;
import com.dfs.types.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationTest {

    @TempDir
    static Path tempDir;

    static HttpServer metadataServer;
    static List<HttpServer> storageServers = new ArrayList<>();
    static DfsClient client;
    static int metadataPort;
    static List<Integer> storagePorts = new ArrayList<>();

    @BeforeAll
    static void startCluster() throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "[%1$tT] %4$s %5$s%6$s%n");

        String[] nodeIds = {"node1", "node2", "node3", "node4"};

        // Storage nodes on ephemeral ports
        for (int i = 0; i < 4; i++) {
            final String nodeId = nodeIds[i];
            Path chunksDir = tempDir.resolve(nodeId).resolve("chunks");
            Files.createDirectories(chunksDir);

            ChunkStore chunkStore = new ChunkStore(nodeId) {
                @Override
                public long saveChunk(String chunkId, byte[] data) throws IOException {
                    Path target = chunksDir.resolve(chunkId + ".bin");
                    Files.write(target, data);
                    return data.length;
                }

                @Override
                public byte[] readChunk(String chunkId) throws IOException {
                    Path p = chunksDir.resolve(chunkId + ".bin");
                    if (!Files.exists(p))
                        throw new java.nio.file.NoSuchFileException("Chunk not found: " + chunkId);
                    return Files.readAllBytes(p);
                }

                @Override
                public boolean deleteChunk(String chunkId) throws IOException {
                    return Files.deleteIfExists(chunksDir.resolve(chunkId + ".bin"));
                }

                @Override
                public double diskFreeGB() {
                    return chunksDir.toFile().getUsableSpace() / (1024.0 * 1024 * 1024);
                }

                @Override
                public int chunkCount() throws IOException {
                    try (var s = Files.list(chunksDir)) {
                        return (int) s.filter(p -> p.toString().endsWith(".bin")).count();
                    }
                }
            };

            StorageHandlers sh = new StorageHandlers(chunkStore);
            HttpServer ss = HttpServer.create(new InetSocketAddress(0), 0);
            ss.createContext("/store_chunk", exchange -> HttpKit.guard(exchange, h -> {
                if (!"POST".equals(h.getRequestMethod()))
                    throw new com.dfs.api.ApiException(405, "Method not allowed");
                String cid = HttpKit.queryParam(h, "chunk_id");
                if (cid == null || cid.isBlank())
                    throw new com.dfs.api.ApiException(400, "Missing chunk_id");
                sh.handleStoreChunk(h, cid);
            }));
            ss.createContext("/get_chunk", exchange -> HttpKit.guard(exchange, h -> {
                if (!"GET".equals(h.getRequestMethod()))
                    throw new com.dfs.api.ApiException(405, "Method not allowed");
                String cid = HttpKit.queryParam(h, "chunk_id");
                if (cid == null || cid.isBlank())
                    throw new com.dfs.api.ApiException(400, "Missing chunk_id");
                sh.handleGetChunk(h, cid);
            }));
            ss.createContext("/delete_chunk", exchange -> HttpKit.guard(exchange, h -> {
                if (!"DELETE".equals(h.getRequestMethod()))
                    throw new com.dfs.api.ApiException(405, "Method not allowed");
                String cid = HttpKit.queryParam(h, "chunk_id");
                if (cid == null || cid.isBlank())
                    throw new com.dfs.api.ApiException(400, "Missing chunk_id");
                sh.handleDeleteChunk(h, cid);
            }));
            ss.createContext("/health", exchange -> HttpKit.guard(exchange, sh::handleHealth));
            ss.setExecutor(null);
            ss.start();
            storageServers.add(ss);
            storagePorts.add(ss.getAddress().getPort());
        }

        // Metadata server pointing at real ephemeral ports
        List<String> realAddrs = storagePorts.stream()
            .map(p -> "localhost:" + p)
            .toList();

        Path metaDb = tempDir.resolve("metadata.db");
        Store store = new Store(metaDb.toString());
        HealthMonitor monitor = new HealthMonitor(realAddrs);
        MetadataHandlers handlers = new MetadataHandlers(store, realAddrs, monitor);

        metadataServer = HttpServer.create(new InetSocketAddress(0), 0);
        metadataServer.createContext("/create_file", exchange -> HttpKit.guard(exchange, h -> {
            if (!"POST".equals(h.getRequestMethod()))
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            handlers.handleCreateFile(h);
        }));
        metadataServer.createContext("/get_file_metadata", exchange -> HttpKit.guard(exchange, h -> {
            if (!"GET".equals(h.getRequestMethod()))
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            String fileId = HttpKit.queryParam(h, "file_id");
            if (fileId == null || fileId.isBlank())
                throw new com.dfs.api.ApiException(400, "Missing file_id");
            handlers.handleGetMetadata(h, fileId);
        }));
        metadataServer.createContext("/delete_file", exchange -> HttpKit.guard(exchange, h -> {
            if (!"DELETE".equals(h.getRequestMethod()))
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            String fileId = HttpKit.queryParam(h, "file_id");
            if (fileId == null || fileId.isBlank())
                throw new com.dfs.api.ApiException(400, "Missing file_id");
            handlers.handleDeleteFile(h, fileId);
        }));
        metadataServer.createContext("/list_files", exchange -> HttpKit.guard(exchange, h -> {
            if (!"GET".equals(h.getRequestMethod()))
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            handlers.handleListFiles(h);
        }));
        metadataServer.createContext("/health", exchange -> HttpKit.guard(exchange, handlers::handleHealth));
        metadataServer.setExecutor(null);
        metadataServer.start();
        metadataPort = metadataServer.getAddress().getPort();

        client = new DfsClient("http://localhost:" + metadataPort);
    }

    @AfterAll
    static void stopCluster() {
        if (metadataServer != null) metadataServer.stop(0);
        for (HttpServer ss : storageServers) ss.stop(0);
    }

    @Test
    @Order(1)
    void testUploadDownloadDelete() throws Exception {
        Path testFile = tempDir.resolve("test_input.bin");
        byte[] testData = new byte[300_000];
        RandomGenerator.of("L128X1024MixRandom").nextBytes(testData);
        Files.write(testFile, testData);

        // Upload
        String fileId = client.upload(testFile);
        assertNotNull(fileId);
        assertFalse(fileId.isBlank());

        // Download
        Path downloadPath = tempDir.resolve("test_output.bin");
        client.download(fileId, downloadPath);
        assertTrue(Files.exists(downloadPath));
        assertEquals(300_000, Files.size(downloadPath));

        // SHA-256 compare
        String originalHash = DfsClient.sha256(testFile);
        String downloadedHash = DfsClient.sha256(downloadPath);
        assertEquals(originalHash, downloadedHash, "Downloaded file must be byte-identical");

        // List files
        String listJson = com.dfs.client.HttpUtil.getJson("http://localhost:" + metadataPort + "/list_files");
        assertTrue(listJson.contains(fileId));

        // Delete
        client.delete(fileId);

        // Verify metadata empty
        String listAfter = com.dfs.client.HttpUtil.getJson("http://localhost:" + metadataPort + "/list_files");
        assertFalse(listAfter.contains(fileId));
    }

    @Test
    @Order(2)
    void testChunkerSplitJoin() throws Exception {
        // Exact multiple of chunk size
        Path exactFile = tempDir.resolve("exact.bin");
        byte[] exactData = new byte[65536 * 3];
        Files.write(exactFile, exactData);
        List<byte[]> chunks = Chunker.splitFile(exactFile, 65536);
        assertEquals(3, chunks.size());
        for (byte[] c : chunks) assertEquals(65536, c.length);

        Path rejoined = tempDir.resolve("rejoined.bin");
        Chunker.joinChunks(chunks, rejoined);
        assertArrayEquals(exactData, Files.readAllBytes(rejoined));

        // Smaller than one chunk
        Path smallFile = tempDir.resolve("small.bin");
        byte[] smallData = new byte[100];
        Files.write(smallFile, smallData);
        List<byte[]> smallChunks = Chunker.splitFile(smallFile, 65536);
        assertEquals(1, smallChunks.size());
        assertArrayEquals(smallData, smallChunks.get(0));

        // Zero-byte file
        Path emptyFile = tempDir.resolve("empty.bin");
        Files.write(emptyFile, new byte[0]);
        List<byte[]> emptyChunks = Chunker.splitFile(emptyFile, 65536);
        assertEquals(0, emptyChunks.size());
    }

    @Test
    @Order(3)
    void testErrorResponses() {
        // Missing file_id -> 400
        assertThrows(Exception.class, () ->
            com.dfs.client.HttpUtil.getJson("http://localhost:" + metadataPort + "/get_file_metadata"));

        // Nonexistent file -> 404
        assertThrows(Exception.class, () ->
            com.dfs.client.HttpUtil.getJson("http://localhost:" + metadataPort + "/get_file_metadata?file_id=nonexistent"));
    }

    @Test
    @Order(4)
    void testHealthEndpoints() throws Exception {
        String metaHealth = com.dfs.client.HttpUtil.getJson("http://localhost:" + metadataPort + "/health");
        assertTrue(metaHealth.contains("healthy"));

        for (int port : storagePorts) {
            String health = com.dfs.client.HttpUtil.getJson("http://localhost:" + port + "/health");
            assertTrue(health.contains("healthy"));
        }
    }
}