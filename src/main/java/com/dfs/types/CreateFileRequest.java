package com.dfs.types;

public record CreateFileRequest(
    String filename,
    long size,
    int chunkCount
) {}