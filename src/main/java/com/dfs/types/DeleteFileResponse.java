package com.dfs.types;

import java.util.List;

public record DeleteFileResponse(
    boolean deleted,
    List<String> chunksToDelete
) {}