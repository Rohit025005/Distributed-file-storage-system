package com.dfs.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.regex.Pattern;

public class ChunkStore {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_\\-]+$");

    private final Path chunksDir;

    public ChunkStore(String nodeId) throws IOException {
        this.chunksDir = Path.of("data", nodeId, "chunks");
        Files.createDirectories(chunksDir);
    }

    private Path pathFor(String chunkId) {
        if (!SAFE_ID.matcher(chunkId).matches()) {
            throw new IllegalArgumentException("Unsafe chunk ID: " + chunkId);
        }
        return chunksDir.resolve(chunkId + ".bin");
    }

    public long saveChunk(String chunkId, byte[] data) throws IOException {
        Path target = pathFor(chunkId);
        Path tmp = chunksDir.resolve(chunkId + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return data.length;
    }

    public byte[] readChunk(String chunkId) throws IOException {
        Path path = pathFor(chunkId);
        if (!Files.exists(path)) {
            throw new NoSuchFileException("Chunk not found: " + chunkId);
        }
        return Files.readAllBytes(path);
    }

    public boolean deleteChunk(String chunkId) throws IOException {
        Path path = pathFor(chunkId);
        return Files.deleteIfExists(path);
    }

    public double diskFreeGB() {
        return chunksDir.toFile().getUsableSpace() / (1024.0 * 1024 * 1024);
    }

    public int chunkCount() throws IOException {
        try (var stream = Files.list(chunksDir)) {
            return (int) stream
                .filter(p -> p.toString().endsWith(".bin"))
                .count();
        }
    }
}