package com.dfs.metadata;

import com.dfs.types.*;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class Store {

    private final String dbPath;

    public Store(String dbPath) {
        this.dbPath = dbPath;
        open();
    }

    private void open() {
        try {
            File dbFile = new File(dbPath);
            if (dbFile.getParentFile() != null) {
                dbFile.getParentFile().mkdirs();
            }

            Class.forName("org.sqlite.JDBC");

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
                for (String sql : Schema.ALL_SQL) {
                    stmt.execute(sql);
                }
            }
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to open database", e);
        }
    }

    private Connection newConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    public CreateFileResponse CreateFile(CreateFileRequest req, List<String> nodeAddrs) {
        if (nodeAddrs.isEmpty()) {
            throw new RuntimeException("No storage nodes configured");
        }

        String fileId = UUID.randomUUID().toString();
        int chunkSize = Constants.DEFAULT_CHUNK_SIZE;
        long fileSize = req.size();
        int chunkCount = req.chunkCount();
        int maxReplicas = Math.min(Constants.REPLICATION_FACTOR - 1, nodeAddrs.size() - 1);

        try (Connection conn = newConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO files (file_id, filename, size, chunk_size, created_at) VALUES (?, ?, ?, ?, ?)")) {
                    pstmt.setString(1, fileId);
                    pstmt.setString(2, req.filename());
                    pstmt.setLong(3, fileSize);
                    pstmt.setInt(4, chunkSize);
                    pstmt.setString(5, Instant.now().toString());
                    pstmt.executeUpdate();
                }

                List<Chunk> chunks = new ArrayList<>();
                int remainingSize = (int) fileSize;
                int chunkIdx = 0;

                while (remainingSize > 0 && chunkIdx < chunkCount) {
                    int currentChunkSize = Math.min(chunkSize, remainingSize);
                    String chunkId = fileId + "_chunk_" + chunkIdx;

                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "INSERT INTO chunks (id, file_id, idx, size) VALUES (?, ?, ?, ?)")) {
                        pstmt.setString(1, chunkId);
                        pstmt.setString(2, fileId);
                        pstmt.setInt(3, chunkIdx);
                        pstmt.setInt(4, currentChunkSize);
                        pstmt.executeUpdate();
                    }

                    String primaryNode = nodeAddrs.get(chunkIdx % nodeAddrs.size());
                    List<String> replicaNodes = new ArrayList<>();
                    for (int i = 1; i <= maxReplicas; i++) {
                        replicaNodes.add(nodeAddrs.get((chunkIdx + i) % nodeAddrs.size()));
                    }

                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "INSERT INTO chunk_locations (chunk_id, node_address, is_primary) VALUES (?, ?, ?)")) {
                        pstmt.setString(1, chunkId);
                        pstmt.setString(2, primaryNode);
                        pstmt.setBoolean(3, true);
                        pstmt.executeUpdate();

                        for (String replica : replicaNodes) {
                            pstmt.setString(1, chunkId);
                            pstmt.setString(2, replica);
                            pstmt.setBoolean(3, false);
                            pstmt.executeUpdate();
                        }
                    }

                    chunks.add(new Chunk(chunkId, chunkIdx, currentChunkSize, primaryNode, replicaNodes));

                    remainingSize -= currentChunkSize;
                    chunkIdx++;
                }

                conn.commit();
                return new CreateFileResponse(fileId, chunks);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create file", e);
        }
    }

    public GetFileMetadataResponse GetFileMetadata(String fileID) {
        try (Connection conn = newConnection()) {
            String filename;
            long size;
            int chunkSize;

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT filename, size, chunk_size FROM files WHERE file_id = ?")) {
                pstmt.setString(1, fileID);
                ResultSet rs = pstmt.executeQuery();
                if (!rs.next()) {
                    throw new RuntimeException("File not found: " + fileID);
                }
                filename = rs.getString("filename");
                size = rs.getLong("size");
                chunkSize = rs.getInt("chunk_size");
            }

            Map<Integer, String> chunkIds = new LinkedHashMap<>();
            Map<Integer, Integer> chunkSizes = new LinkedHashMap<>();
            Map<Integer, String> primaries = new HashMap<>();
            Map<Integer, List<String>> replicas = new HashMap<>();

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT c.id, c.idx, c.size, cl.node_address, cl.is_primary " +
                    "FROM chunks c " +
                    "JOIN chunk_locations cl ON c.id = cl.chunk_id " +
                    "WHERE c.file_id = ? ORDER BY c.idx")) {
                pstmt.setString(1, fileID);
                ResultSet rs = pstmt.executeQuery();

                while (rs.next()) {
                    int idx = rs.getInt("idx");
                    chunkIds.putIfAbsent(idx, rs.getString("id"));
                    chunkSizes.putIfAbsent(idx, rs.getInt("size"));
                    if (rs.getBoolean("is_primary")) {
                        primaries.put(idx, rs.getString("node_address"));
                    } else {
                        replicas.computeIfAbsent(idx, k -> new ArrayList<>())
                                .add(rs.getString("node_address"));
                    }
                }
            }

            List<Chunk> chunks = new ArrayList<>();
            for (int idx : chunkIds.keySet()) {
                chunks.add(new Chunk(
                    chunkIds.get(idx),
                    idx,
                    chunkSizes.get(idx),
                    primaries.getOrDefault(idx, ""),
                    replicas.getOrDefault(idx, List.of())
                ));
            }

            return new GetFileMetadataResponse(fileID, filename, size, chunkSize, chunks);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get file metadata", e);
        }
    }

    public DeleteFileResponse DeleteFile(String fileID) {
        try (Connection conn = newConnection()) {
            List<String> chunksToDelete = new ArrayList<>();

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT id FROM chunks WHERE file_id = ?")) {
                pstmt.setString(1, fileID);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    chunksToDelete.add(rs.getString("id"));
                }
            }

            if (chunksToDelete.isEmpty()) {
                throw new RuntimeException("File not found: " + fileID);
            }

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "DELETE FROM files WHERE file_id = ?")) {
                pstmt.setString(1, fileID);
                pstmt.executeUpdate();
            }

            return new DeleteFileResponse(true, chunksToDelete);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    public List<DfsFile> ListFiles() {
        try (Connection conn = newConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT file_id, filename, size, chunk_size, created_at FROM files ORDER BY created_at")) {

            List<DfsFile> files = new ArrayList<>();
            while (rs.next()) {
                files.add(new DfsFile(
                    rs.getString("file_id"),
                    rs.getString("filename"),
                    rs.getLong("size"),
                    rs.getInt("chunk_size"),
                    rs.getString("created_at")
                ));
            }
            return files;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list files", e);
        }
    }
}