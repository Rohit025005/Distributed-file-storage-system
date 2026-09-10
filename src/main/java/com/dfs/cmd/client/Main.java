package com.dfs.cmd.client;

import com.dfs.client.DfsClient;

import java.nio.file.Path;

public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String metadataUrl = System.getenv("METADATA_URL");
        if (metadataUrl == null || metadataUrl.isBlank()) {
            metadataUrl = "http://localhost:9090";
        }

        DfsClient client = new DfsClient(metadataUrl);

        try {
            switch (args[0]) {
                case "upload" -> {
                    if (args.length < 2) { printUsage(); System.exit(1); }
                    Path path = Path.of(args[1]);
                    String fileId = client.upload(path);
                    System.out.println("Uploaded file " + fileId);
                }
                case "download" -> {
                    if (args.length < 3) { printUsage(); System.exit(1); }
                    client.download(args[1], Path.of(args[2]));
                }
                case "delete" -> {
                    if (args.length < 2) { printUsage(); System.exit(1); }
                    client.delete(args[1]);
                }
                case "ls" -> client.listFiles();
                default -> { printUsage(); System.exit(1); }
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: dfs <command> [args]");
        System.out.println("  upload <local-path>");
        System.out.println("  download <file-id> <output-path>");
        System.out.println("  delete <file-id>");
        System.out.println("  ls");
    }
}