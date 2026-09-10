package com.dfs.types;

public record ChunkLocation(
    String chunkId,
    String nodeAddress,
    boolean isPrimary
) {}