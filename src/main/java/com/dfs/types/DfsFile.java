package com.dfs.types;

public record DfsFile(
    String fileId,
    String filename,
    long size,
    int chunkSize,
    String createdAt
) {}