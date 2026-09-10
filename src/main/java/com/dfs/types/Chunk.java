package com.dfs.types;

import java.util.List;

public record Chunk(
    String id,
    int index,
    int size,
    String primaryNode,
    List<String> replicaNodes
) {}