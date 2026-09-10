package com.dfs.config;

import com.dfs.types.Constants;
import com.dfs.types.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Config {

    private List<Node> nodes;
    private int replicationFactor;
    private int chunkSize;

    public Config() {
        this.replicationFactor = Constants.REPLICATION_FACTOR;
        this.chunkSize = Constants.DEFAULT_CHUNK_SIZE;
    }

    public static Config loadFromFile(String path) throws IOException {
        Config config = new Config();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        File file = new File(path);

        if (file.exists()) {
            Config yamlConfig = yamlMapper.readValue(file, Config.class);
            config.nodes = yamlConfig.getNodes();
            config.replicationFactor = yamlConfig.getReplicationFactor();
            config.chunkSize = yamlConfig.getChunkSize();
        }

        if (config.nodes == null || config.nodes.isEmpty()) {
            config.nodes = List.of(
                new Node("localhost:8081", "node1"),
                new Node("localhost:8082", "node2"),
                new Node("localhost:8083", "node3"),
                new Node("localhost:8084", "node4")
            );
        }

        if (config.replicationFactor <= 0) {
            config.replicationFactor = Constants.REPLICATION_FACTOR;
        }

        if (config.chunkSize <= 0) {
            config.chunkSize = Constants.DEFAULT_CHUNK_SIZE;
        }

        return config;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setNodes(List<Node> nodes) {
        this.nodes = nodes;
    }

    public void setReplicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public List<String> getNodeAddresses() {
        return nodes.stream()
                    .map(Node::address)
                    .collect(Collectors.toList());
    }
}