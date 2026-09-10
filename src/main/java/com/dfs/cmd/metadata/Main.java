package com.dfs.cmd.metadata;

import com.dfs.api.HttpKit;
import com.dfs.api.MetadataHandlers;
import com.dfs.config.Config;
import com.dfs.metadata.HealthMonitor;
import com.dfs.metadata.Store;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.LogManager;

public class Main {

    public static void main(String[] args) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "[%1$tT] %4$s %5$s%6$s%n");

        Config config = Config.loadFromFile("config/nodes.yaml");
        Store store = new Store("./data/metadata.db");

        HealthMonitor monitor = new HealthMonitor(config.getNodeAddresses());
        monitor.start(10);

        MetadataHandlers handlers = new MetadataHandlers(store, config.getNodeAddresses(), monitor);

        HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);

        server.createContext("/create_file", exchange -> HttpKit.guard(exchange, h -> {
            if (!"POST".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            handlers.handleCreateFile(h);
        }));

        server.createContext("/get_file_metadata", exchange -> HttpKit.guard(exchange, h -> {
            if (!"GET".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            String fileId = HttpKit.queryParam(h, "file_id");
            if (fileId == null || fileId.isBlank()) {
                throw new com.dfs.api.ApiException(400, "Missing file_id parameter");
            }
            handlers.handleGetMetadata(h, fileId);
        }));

        server.createContext("/delete_file", exchange -> HttpKit.guard(exchange, h -> {
            if (!"DELETE".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            String fileId = HttpKit.queryParam(h, "file_id");
            if (fileId == null || fileId.isBlank()) {
                throw new com.dfs.api.ApiException(400, "Missing file_id parameter");
            }
            handlers.handleDeleteFile(h, fileId);
        }));

        server.createContext("/list_files", exchange -> HttpKit.guard(exchange, h -> {
            if (!"GET".equals(h.getRequestMethod())) {
                throw new com.dfs.api.ApiException(405, "Method not allowed");
            }
            handlers.handleListFiles(h);
        }));

        server.createContext("/health", exchange -> HttpKit.guard(exchange, handlers::handleHealth));

        server.setExecutor(null);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(monitor::stop));

        System.out.println("Metadata server started on port 9090");
    }
}