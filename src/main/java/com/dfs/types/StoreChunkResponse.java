package com.dfs.types;

public record StoreChunkResponse(
    boolean stored,
    int bytes
) {}