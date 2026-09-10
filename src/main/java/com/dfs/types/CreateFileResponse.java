package com.dfs.types;

import java.util.List;

public record CreateFileResponse(
    String fileId,
    List<Chunk> chunks
) {}