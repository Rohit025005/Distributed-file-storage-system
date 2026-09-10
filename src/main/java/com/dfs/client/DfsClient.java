package com.dfs.client;

import com.dfs.chunker.Chunker;
import com.dfs.types.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class DfsClient {

    private final String metadataUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public DfsClient(String metadataUrl) {
        this.metadataUrl = metadataUrl;
    }

    public String upload(Path filePath) throws IOException {
        long size = Files.size(filePath);
        int chunkSize = com.dfs.types.Constants.DEFAULT_CHUNK_SIZE;
        int chunkCount = (int) ((size + chunkSize - 1) / chunkSize);
        if (chunkCount == 0) chunkCount = 1;

        String filename = filePath.getFileName().toString();
        String reqJson = mapper.writeValueAsString(new CreateFileRequest(filename, size, chunkCount));
        String respJson = HttpUtil.postJson(metadataUrl + "/create_file", reqJson);
        CreateFileResponse resp = mapper.readValue(respJson, CreateFileResponse.class);

        List<byte[]> localChunks = Chunker.splitFile(filePath, chunkSize);
        if (localChunks.size() != resp.chunks().size()) {
            throw new IOException("Chunk count mismatch: local=" + localChunks.size()
                + " server=" + resp.chunks().size());
        }

        for (int i = 0; i < resp.chunks().size(); i++) {
            Chunk chunk = resp.chunks().get(i);
            byte[] data = localChunks.get(i);

            String primaryUrl = "http://" + chunk.primaryNode() + "/store_chunk?chunk_id=" + chunk.id();
            HttpUtil.postBytes(primaryUrl, data);
            System.out.println("  chunk " + i + "/" + (resp.chunks().size() - 1) + " -> " + chunk.primaryNode());

            for (String replica : chunk.replicaNodes()) {
                String replicaUrl = "http://" + replica + "/store_chunk?chunk_id=" + chunk.id();
                try {
                    HttpUtil.postBytes(replicaUrl, data);
                } catch (IOException e) {
                    System.err.println("  WARN: replica write failed on " + replica + ": " + e.getMessage());
                }
            }
        }

        return resp.fileId();
    }

    public void download(String fileId, Path outputPath) throws IOException {
        String respJson = HttpUtil.getJson(metadataUrl + "/get_file_metadata?file_id=" + fileId);
        GetFileMetadataResponse meta = mapper.readValue(respJson, GetFileMetadataResponse.class);

        List<Chunk> sorted = meta.chunks().stream()
            .sorted(Comparator.comparingInt(Chunk::index))
            .collect(Collectors.toList());

        List<byte[]> chunkData = new ArrayList<>();
        for (Chunk chunk : sorted) {
            byte[] data = fetchWithFailover(chunk);
            chunkData.add(data);
            System.out.println("  chunk " + chunk.index() + "/" + (sorted.size() - 1) + " fetched");
        }

        Chunker.joinChunks(chunkData, outputPath);
        System.out.println("Downloaded to " + outputPath);
    }

    private byte[] fetchWithFailover(Chunk chunk) throws IOException {
        List<String> candidates = new ArrayList<>();
        candidates.add(chunk.primaryNode());
        candidates.addAll(chunk.replicaNodes());

        IOException lastError = null;
        for (String node : candidates) {
            try {
                return HttpUtil.getBytes("http://" + node + "/get_chunk?chunk_id=" + chunk.id());
            } catch (IOException e) {
                System.err.println("  WARN: fetch failed from " + node + ": " + e.getMessage());
                lastError = e;
            }
        }
        throw new IOException("All nodes failed for chunk " + chunk.id(), lastError);
    }

    public void delete(String fileId) throws IOException {
        String metaJson = HttpUtil.getJson(metadataUrl + "/get_file_metadata?file_id=" + fileId);
        GetFileMetadataResponse meta = mapper.readValue(metaJson, GetFileMetadataResponse.class);

        Map<String, Set<String>> chunkHolders = new HashMap<>();
        for (Chunk chunk : meta.chunks()) {
            Set<String> holders = new HashSet<>();
            holders.add(chunk.primaryNode());
            holders.addAll(chunk.replicaNodes());
            chunkHolders.put(chunk.id(), holders);
        }

        String delJson = HttpUtil.delete(metadataUrl + "/delete_file?file_id=" + fileId);
        DeleteFileResponse delResp = mapper.readValue(delJson, DeleteFileResponse.class);

        for (String chunkId : delResp.chunksToDelete()) {
            Set<String> holders = chunkHolders.getOrDefault(chunkId, Set.of());
            for (String node : holders) {
                try {
                    HttpUtil.delete("http://" + node + "/delete_chunk?chunk_id=" + chunkId);
                } catch (IOException e) {
                    System.err.println("  WARN: chunk delete failed on " + node + ": " + e.getMessage());
                }
            }
        }
        System.out.println("Deleted file " + fileId);
    }

    public void listFiles() throws IOException {
        String respJson = HttpUtil.getJson(metadataUrl + "/list_files");
        List<DfsFile> files = mapper.readValue(respJson, new TypeReference<>() {});
        if (files.isEmpty()) {
            System.out.println("No files stored.");
            return;
        }
        System.out.printf("%-36s  %-20s  %10s  %s%n", "FILE ID", "NAME", "SIZE", "CREATED");
        for (DfsFile f : files) {
            System.out.printf("%-36s  %-20s  %10d  %s%n",
                f.fileId(), f.filename(), f.size(), f.createdAt());
        }
    }

    public static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] all = Files.readAllBytes(path);
        byte[] hash = digest.digest(all);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}