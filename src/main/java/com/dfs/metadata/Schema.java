package com.dfs.metadata;

import java.util.Arrays;

public final class Schema {

    private Schema() {
    }

    public static final String FILES_SQL =
        "CREATE TABLE IF NOT EXISTS files (" +
        "    file_id     TEXT PRIMARY KEY," +
        "    filename    TEXT NOT NULL," +
        "    size        INTEGER NOT NULL," +
        "    chunk_size  INTEGER NOT NULL," +
        "    created_at  DATETIME NOT NULL" +
        ")";

    public static final String CHUNKS_SQL =
        "CREATE TABLE IF NOT EXISTS chunks (" +
        "    id      TEXT PRIMARY KEY," +
        "    file_id TEXT NOT NULL," +
        "    idx     INTEGER NOT NULL," +
        "    size    INTEGER NOT NULL," +
        "    FOREIGN KEY (file_id) REFERENCES files(file_id) ON DELETE CASCADE" +
        ")";

    public static final String CHUNK_LOCATIONS_SQL =
        "CREATE TABLE IF NOT EXISTS chunk_locations (" +
        "    chunk_id     TEXT NOT NULL," +
        "    node_address TEXT NOT NULL," +
        "    is_primary   BOOLEAN NOT NULL," +
        "    PRIMARY KEY (chunk_id, node_address)," +
        "    FOREIGN KEY (chunk_id) REFERENCES chunks(id) ON DELETE CASCADE" +
        ")";

    public static final String[] ALL_SQL = {
        FILES_SQL,
        CHUNKS_SQL,
        CHUNK_LOCATIONS_SQL
    };
}