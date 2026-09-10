package com.dfs.types;

public record ErrorResponse(
    String error,
    String details
) {}