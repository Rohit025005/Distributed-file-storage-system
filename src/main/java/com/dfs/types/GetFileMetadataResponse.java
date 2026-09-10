package com.dfs.types;

import java.util.List;

public record GetFileMetadataResponse(
    String fileId,
    String filename,
    long size,
    int chunkSize,
    List<Chunk> chunks
) {}