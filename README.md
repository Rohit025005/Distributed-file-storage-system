# Distributed File System (DFS)

A GFS-style distributed file system in Java 21. Files are split into 64KB chunks, replicated across 4 storage nodes, and coordinated by a central metadata server. Clients stream bytes directly to/from storage nodes — the metadata server never touches file data.

## Architecture

```
                        CONTROL PLANE                DATA PLANE
       ┌────────┐     plan (JSON)     ┌──────────────────┐
       │ Client │ <─────────────────> │  Metadata Server  │       ┌────────────────┐
       │  CLI   │                     │    port :9090     │       │ Storage Node 1 │ :8081
       └───┬────┘                     │    SQLite DB      │       │ Storage Node 2 │ :8082
           │                          └────────▲──────────┘ poll  │ Storage Node 3 │ :8083
           │ raw chunk bytes                   │ /health         │ Storage Node 4 │ :8084
           ▼                                   │                 └────────────────┘
       chunks land as .bin files ──────────────┘  clients push/pull bytes DIRECTLY to nodes
```

### Components

| Component | Port(s) | Responsibility |
|-----------|---------|----------------|
| **Client CLI** | — | Splits/joins files, streams bytes to/from storage nodes, orchestrates uploads/downloads/deletes |
| **Metadata Server** | 9090 | SQLite DB tracking files, chunks, and chunk locations. Plans which node gets which chunk. Monitors node health. |
| **Storage Nodes** | 8081–8084 | Stores `.bin` chunk files on disk. Exposes HTTP API for store/read/delete. |

### Key Design Decisions

- **Metadata never touches file bytes** — it only plans chunk placement. Clients stream data directly to storage nodes. This mirrors GFS/HDFS architecture.
- **64KB chunks** — small enough to demonstrate multi-chunk behavior locally.
- **Replication factor 2** — each chunk has 1 primary + 1 replica on a different node.
- **Round-robin placement** — chunk `i`'s primary is `nodes[i % 4]`, replicas follow.
- **Immutable chunks** — once written, chunks are never modified, only overwritten or deleted.

## Project Structure

```
src/main/java/com/dfs/
├── types/              Shared data records (Node, Chunk, requests, responses)
│   ├── Node.java
│   ├── Chunk.java
│   ├── ChunkLocation.java
│   ├── CreateFileRequest.java / CreateFileResponse.java
│   ├── GetFileMetadataResponse.java
│   ├── DeleteFileResponse.java
│   ├── StoreChunkResponse.java
│   ├── HealthResponse.java / ErrorResponse.java
│   ├── DfsFile.java
│   └── Constants.java          64KB chunk size, RF=2
├── config/
│   └── Config.java             YAML config loader
├── metadata/
│   ├── Schema.java             SQLite DDL constants
│   ├── Store.java              SQLite CRUD (transactional CreateFile)
│   └── HealthMonitor.java      Background /health poller (10s interval)
├── storage/
│   └── ChunkStore.java         Disk I/O with path-traversal protection
├── chunker/
│   └── Chunker.java            Split/join files into byte arrays
├── client/
│   ├── HttpUtil.java           java.net.http.HttpClient wrapper
│   └── DfsClient.java          Upload/download/delete/ls orchestration
├── api/
│   ├── HttpKit.java            Shared guard(), sendJson(), queryParam()
│   ├── ApiException.java       Typed HTTP errors
│   ├── MetadataHandlers.java   Metadata server HTTP handlers
│   └── StorageHandlers.java    Storage node HTTP handlers
└── cmd/
    ├── metadata/Main.java      Metadata server entry point
    ├── storage/Main.java       Storage node entry point (reads NODE_ID)
    └── client/Main.java        CLI entry point
```

## API Reference

### Metadata Server (port 9090)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/create_file` | Create file metadata with chunk assignments. Body: `{"filename":"x","size":123,"chunkCount":2}` |
| `GET` | `/get_file_metadata?file_id=<id>` | Get file metadata with chunk locations |
| `DELETE` | `/delete_file?file_id=<id>` | Delete file metadata (cascades to chunks/locations) |
| `GET` | `/list_files` | List all stored files |
| `GET` | `/health` | Server health check |

### Storage Node (ports 8081–8084)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/store_chunk?chunk_id=<id>` | Store raw bytes as a chunk |
| `GET` | `/get_chunk?chunk_id=<id>` | Retrieve chunk bytes |
| `DELETE` | `/delete_chunk?chunk_id=<id>` | Delete a chunk from disk |
| `GET` | `/health` | Node health (disk free GB, chunk count) |

## How to Run

### Prerequisites

- Java 21+
- Maven 3.9+

### 1. Build

```bash
mvn compile
```

### 2. Start Metadata Server

```bash
mvn exec:java -Dexec.mainClass=com.dfs.cmd.metadata.Main
```

### 3. Start Storage Nodes (4 separate terminals)

```bash
# Terminal 1
$env:NODE_ID="node1"; mvn exec:java "-Dexec.mainClass=com.dfs.cmd.storage.Main"

# Terminal 2
$env:NODE_ID="node2"; mvn exec:java "-Dexec.mainClass=com.dfs.cmd.storage.Main"

# Terminal 3
$env:NODE_ID="node3"; mvn exec:java "-Dexec.mainClass=com.dfs.cmd.storage.Main"

# Terminal 4
$env:NODE_ID="node4"; mvn exec:java "-Dexec.mainClass=com.dfs.cmd.storage.Main"
```

### 4. Use the CLI

```bash
# Upload a file
mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=upload test.txt"

# Download (use the file ID from upload output)
mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=download <FILE_ID> restored.txt"

# List files
mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=ls"

# Delete a file
mvn exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=delete <FILE_ID>"
```

### 5. Run Tests

```bash
mvn test
```

## Upload Flow

```
Client                          Metadata Server               Storage Nodes
  │  POST /create_file             │                              │
  │ {filename,size,chunkCount} ──► │ filter dead nodes            │
  │                                │ BEGIN TRANSACTION            │
  │                                │  INSERT files row            │
  │                                │  for each chunk:             │
  │                                │   primary = nodes[i % N]     │
  │                                │   replicas = next RF-1 nodes │
  │                                │  INSERT chunks + locations   │
  │                                │ COMMIT                       │
  │ ◄── {fileId, chunks[...]}      │                              │
  │ split file into 64KB pieces    │                              │
  │ for each chunk:                │                              │
  │  POST /store_chunk ────────────┼──── raw bytes ──────────────►│ primary
  │                                ├─────────────────────────────►│ replicas (warn on fail)
  │ print fileId                   │                              │
```

## Download Flow

```
Client                          Metadata Server               Storage Nodes
  │ GET /get_file_metadata         │                              │
  │ ?file_id=... ────────────────► │ join chunks + locations      │
  │ ◄── ordered chunk list w/      │                              │
  │     primary + replicas         │                              │
  │ sort by index                  │                              │
  │ for each chunk:                │                              │
  │  try primary GET /get_chunk ───┼─────────────────────────────►│
  │  catch → try replicas ─────────┼─────────────────────────────►│ first success wins
  │ joinChunks → output file       │                              │
```

## Delete Flow (Order Matters!)

```
1. GET /get_file_metadata  ← BEFORE anything else (need to know where chunks live)
2. Collect every node holding each chunk
3. DELETE /delete_file     ← cascades metadata rows
4. For each chunk on each node: DELETE /delete_chunk
```

If you delete metadata first, chunk locations are lost forever.

## Design Notes

| Setting | Value | Industry Reference |
|---------|-------|--------------------|
| Chunk size | 64 KB | GFS 64MB, HDFS 128MB |
| Replication factor | 2 | Typically 3 |
| Health poll interval | 10s | HDFS heartbeat 3s |
| Probe timeout | 2s connect / 3s request | — |
| Metadata port | 9090 | — |
| Storage ports | 8081–8084 | — |

## Known Limitations

- **Single metadata server** = single point of failure. Production systems use Raft/ZAB consensus (HDFS HA, TiKV).
- **No authentication or TLS.**
- **No erasure coding** — RF=2 means one dead node can orphan chunks whose both copies were on it.
- **Best-effort re-replication** — no locking against concurrent deletes.
- **Round-robin placement** — ignores node capacity, load, and rack topology.

## Tech Stack

- Java 21 (records for immutable DTOs)
- SQLite via JDBC (embedded metadata store)
- Jackson (JSON + YAML serialization)
- `com.sun.net.httpserver` (stdlib HTTP server)
- `java.net.http.HttpClient` (modern HTTP client)
- JUnit 5 (integration tests)
