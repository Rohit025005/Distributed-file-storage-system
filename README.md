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
    ├── demo/Main.java           Single-JVM demo: boots all 5 services, scripted walkthrough
    ├── metadata/Main.java      Metadata server entry point
    ├── storage/Main.java       Storage node entry point (reads NODE_ID)
    └── client/Main.java        CLI entry point

# root
├── dfs.ps1                     Windows launcher: demo / start / stop / status / logs / CLI
├── start.ps1                   Alias for `dfs.ps1 demo`
├── start.sh                    One-shot demo (macOS/Linux)
├── config/nodes.yaml           Node id -> address, replication factor, chunk size
├── pom.xml                     exec-maven-plugin defaults `mvn exec:java` to the demo
├── data/                       Runtime state (gitignored)
└── run/                        Background service PIDs + logs (gitignored)
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

### Quick check: one command

```powershell
.\dfs.ps1 demo
```

Boots all 5 services in a single JVM, runs upload → list → download → verify → delete,
then shuts down and exits. Takes ~3 seconds. Look for `sha256 match: true`.
**Wipes `data\`.** Refuses to run if background services are already up.

### Live demo — Layout A: 2 terminals (recommended)

**Terminal 1 — control.** Run these in order:

| Step | Command | Expected |
|------|---------|----------|
| 1 | `.\dfs.ps1 start` | 5 × `healthy`, then `Ready.` (~5-10s) |
| 2 | `.\dfs.ps1 status` | all 5 `UP` |
| 3 | `.\dfs.ps1 upload test.txt` | prints a file id — **copy it** |
| 4 | `.\dfs.ps1 ls` | shows the file in the catalog |
| 5 | `.\dfs.ps1 download <id> out.txt` | re-assembles the file |
| 6 | `.\dfs.ps1 delete <id>` | removes metadata + chunks |

**Terminal 2 — watch it work (optional).** Follow all five services live:

```powershell
.\dfs.ps1 logs
```

Output is interleaved and prefixed `[node1]`, `[metadata]`, ... Ctrl+C to stop.

**Teardown:**

```powershell
.\dfs.ps1 stop
```

Use `test.txt` (488,893 bytes = 8 chunks) rather than a small file — you can see the
chunks spread across nodes. `.\dfs.ps1 logs node1` follows a single node.

### Live demo — Layout B: 3 terminals

Same commands, just split across windows. `start` returns as soon as the services are
healthy, so no window is ever blocked — the split only changes which window you type into.

| Window | First command | Then |
|--------|---------------|------|
| 1 | `.\dfs.ps1 start nodes` | — |
| 2 | `.\dfs.ps1 start metadata` | — |
| 3 | `.\dfs.ps1 status` | `upload` / `ls` / `download` / `delete` |

`.\dfs.ps1 logs` works in any window. Teardown with `.\dfs.ps1 stop` in any window.

### Execution order: the rules that matter

1. **`start` before any client command.** The client refuses with a clear message
   otherwise.
2. **Storage nodes before metadata.** `.\dfs.ps1 start` and `start nodes` handle this for
   you. If you start metadata first, its health monitor probes immediately, finds nothing
   listening, and marks all 4 nodes `DOWN` — every upload then fails with
   `503 No healthy storage nodes available` for up to 10 seconds, until the next poll.
3. **`demo` only with nothing running.** It refuses while services are up, because it
   deletes `data\` on startup.
4. **`stop` before you leave.** Closing the terminal does *not* stop the services. The five
   JVMs keep holding ports 8081-8084 and 9090, and the next `start` will report a port
   conflict.
5. **Re-running `start` is safe.** It skips anything already running and starts only what
   is missing.
6. **The leading dot is required:** `.\dfs.ps1`, not `\dfs.ps1`. PowerShell will not run a
   script from the current directory without it.

### What `start` actually does

`.\dfs.ps1 start` uses `Start-Process`, which spawns **independent OS processes** — not
PowerShell jobs. They are not children of your shell and they survive it. The only handle
you have on them is the PID written to `run/<service>.pid`, which is how `stop` finds them
later. Concretely:

- Nothing supervises them. A node that dies stays dead until you re-run `start`.
- Closing every window, or logging off, leaves all five running.
- `stop` verifies the stored PID is still a `java` process before killing it, so a reused
  PID belonging to something else is never terminated.

### Command reference

| Command | Description |
|---------|-------------|
| `.\dfs.ps1 demo` | One-shot scripted demo in a single JVM (~3s). Wipes `data\`, exits. |
| `.\dfs.ps1 start [all\|nodes\|metadata]` | Start in background and wait until healthy. Default `all`. |
| `.\dfs.ps1 stop [all\|nodes\|metadata]` | Stop background services. Default `all`. |
| `.\dfs.ps1 status` | Health table of all 5 services. |
| `.\dfs.ps1 logs [all\|metadata\|node1..node4]` | Follow logs live, Ctrl+C to stop. Default `all`. |
| `.\dfs.ps1 upload <path>` | Upload a file, prints its file id. |
| `.\dfs.ps1 ls` | List stored files. |
| `.\dfs.ps1 download <id> <out-path>` | Download and re-assemble. |
| `.\dfs.ps1 delete <id>` | Delete a file and its chunks. |
| `.\dfs.ps1 help` | Usage. Same as running it with no arguments. |

`.\start.ps1` is an alias for `.\dfs.ps1 demo`.

### Where state lives

| Path | Contents |
|------|----------|
| `data/metadata.db` | SQLite catalog — which files, which chunks, which nodes hold them |
| `data/nodeN/chunks/*.bin` | The actual chunk bytes |
| `run/*.pid`, `run/*.log` | Background service PIDs and logs (gitignored) |

Unlike `demo`, live mode **persists** `metadata.db` between sessions, so `ls` will show
leftovers from an earlier run. Delete `data\metadata.db` for a clean slate.

### macOS/Linux demo

```bash
./start.sh
```

The `dfs.ps1` launcher is Windows-only. On macOS/Linux the one-shot demo works via
`start.sh`; the equivalent background start/stop/status/logs commands are not provided.

### Tests

```bash
mvn test
```

### Manual setup (advanced, long commands)

The raw `mvn` invocations behind `dfs.ps1`, for reference or if you are not on Windows.

#### 1. Build

```bash
mvn compile
```

#### 2. Start Metadata Server

```bash
mvn exec:java -Dexec.mainClass=com.dfs.cmd.metadata.Main
```

#### 3. Start Storage Nodes (4 separate terminals)

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

#### 4. Use the CLI

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
