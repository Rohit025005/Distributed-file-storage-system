package com.dfs.types;

public record HealthResponse(
    String status,
    double diskFreeGB,
    int chunksStored
) {}