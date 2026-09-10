# Phase 3: Storage Node (Chunk Storage) - Java Version

## Objective
Implement the actual byte-storage service. Each storage node exposes a simple HTTP API to store/retrieve/delete chunks on local disk. No metadata, no SQLite — just bytes on disk.

By the end of this phase you can run **4 storage node instances concurrently** (ports 8081–8084), each with its own data directory.

## 1. Component Layout

```
src/main/java/com/dfs/
├── storage/ChunkStore.java        # disk logic: save/read/delete/count
└── api/StorageHandlers.java       # HTTP layer (same style as MetadataHandlers)
src/main/java/com/dfs/cmd/storage/Main.java   # entry point (replace the Phase-1 stub)
```

## 2. Disk Logic (`com.dfs.storage.ChunkStore`)

Design decisions:

1. **Layout**: chunks live in `./data/<node_id>/chunks/<chunk_id>.bin`. Each node gets an
   isolated directory so all 4 instances can share one working dir.
2. **Chunk ID sanitization is mandatory**: chunk IDs come from HTTP query params and are
   used to build a file path. Without validation, `chunk_id=../../metadata.db` reads or
   overwrites arbitrary files (**path traversal attack**). Validate against a whitelist
   regex — our IDs look like `c_<uuid>_chunk_<n>`, so `[A-Za-z0-9_\-]+` suffices.
3. **Windows note**: "free space on this volume" is `File.getUsableSpace()` — works on
   Windows, Linux, and macOS, no native calls needed.

Methods to implement:

| Method | Behavior |
|---|---|
| `ChunkStore(String nodeId)` | creates `./data/<nodeId>/chunks/` if missing (`Files.createDirectories`) |
| `long SaveChunk(String chunkId, byte[] data)` | validates id, writes `<id>.bin`, returns byte count |
| `byte[] ReadChunk(String chunkId)` | validates id, returns bytes; throw `NoSuchFileException` if absent |
| `boolean DeleteChunk(String chunkId)` | validates id; `Files.deleteIfExists` → false means it wasn't there |
| `double DiskFreeGB()` | `usableSpace / (1024^3)` |
| `int ChunkCount()` | count `*.bin` files in the dir |

Suggested private helper:

```java
private Path pathFor(String chunkId) {
    // throws IllegalArgumentException unless chunkId matches SAFE_ID regex
    return chunksDir.resolve(chunkId + ".bin");
}
```

## 3. HTTP Handlers (`com.dfs.api.StorageHandlers`)

Reuse the Phase-2 patterns: `sendJson`/`sendError` helpers, method checks in Main,
query-param parsing via a shared helper.

| Method | Path | Handler | Notes |
|---|---|---|---|
| POST | `/store_chunk?chunk_id=...` | `handleStoreChunk` | **raw body**, not multipart: `exchange.getRequestBody().readAllBytes()`. Respond with the existing `StoreChunkResponse(stored, bytes)` record |
| GET | `/get_chunk?chunk_id=...` | `handleGetChunk` | respond `application/octet-stream`, content-length = data length. Missing chunk → **404** + `ErrorResponse` |
| DELETE | `/delete_chunk?chunk_id=...` | `handleDeleteChunk` | JSON body like `{"deleted":true}`; deleting a missing chunk → 404 |
| GET | `/health` | `handleHealth` | real values now: `new HealthResponse("healthy", store.DiskFreeGB(), store.ChunkCount())` |

Binary responses rule (same as Phase 2): pass the exact byte length to
`sendResponseHeaders`, then write once.

## 4. Wire It Up (`com.dfs.cmd.storage.Main`)

One binary serves ALL nodes — the instance differentiates itself at startup:

1. Node ID from env var `NODE_ID` or `args[0]`; refuse to start without one.
2. Load `config/nodes.yaml` with your Phase-1 `Config.loadFromFile(...)`; find the `Node`
   whose `id()` matches; parse the port out of its `address()` (`"localhost:8081"` → 8081).
3. Create `ChunkStore(nodeId)` and `StorageHandlers`.
4. Register contexts + `guard()` error wrapper exactly like the metadata server.
5. Listen on the parsed port.

Running 4 instances locally — each needs its own terminal window:

```powershell
# PowerShell (from the dfs/ directory)
$env:NODE_ID="node1"; mvn compile exec:java "-Dexec.mainClass=com.dfs.cmd.storage.Main"
# then in new terminals: $env:NODE_ID="node2"; ...
```

```bash
# Git Bash / Linux
NODE_ID=node2 mvn exec:java -Dexec.mainClass=com.dfs.cmd.storage.Main
```

## 5. Pitfalls To Avoid

1. **Path traversal** — the #1 security bug in naive implementations. Never trust
   `chunk_id`; regex-validate before touching the filesystem.
2. **Multipart confusion** — you do NOT need form-data handling here. The client sends
   raw bytes; read the whole input stream before responding.
3. **Wrong status codes** — unknown chunk = 404 (client resource), bad params/method =
   400/405, unexpected failure = 500. Phase 6 will formalize this mapping.
4. **Forgetting to fully consume the request body** — if you respond early without
   reading it, the connection can reset mid-response. Read first, respond second.
5. **Port collisions when testing** — starting two instances with the same `NODE_ID`
   gives "Address already in use". One terminal per node.
6. **Counting non-chunk files** — if you later write temp files into the same dir,
   filter by extension or use a `.tmp` suffix you exclude from `ChunkCount`.

## 6. Checklist for Phase 3

- [ ] Chunk save/read/delete working against local disk under `./data/<node_id>/chunks/`
- [ ] Invalid chunk IDs rejected (try `../evil` → expect 400/500 JSON error, not a write)
- [ ] Health endpoint reports real free disk GB + live chunk count
- [ ] All 4 endpoints tested with curl (script below)
- [ ] 4 storage node instances run concurrently on ports 8081–8084

### Manual verification script

```bash
mvn compile

# terminal 1..4: NODE_ID=node1..node4 (see commands above), then:
curl http://localhost:8081/health
# {"status":"healthy","diskFreeGB":123.4,"chunksStored":0}

# Store a raw chunk (note --data-binary!)
echo -n "hello world" > /tmp/c_test_chunk_0.bin
curl -X POST --data-binary @/tmp/c_test_chunk_0.bin \
  "http://localhost:8081/store_chunk?chunk_id=c_test_chunk_0"
# {"stored":true,"bytes":11}

curl "http://localhost:8081/get_chunk?chunk_id=c_test_chunk_0"
# hello world
curl http://localhost:8081/health          # chunksStored: 1

curl -X DELETE "http://localhost:8081/get_chunk?chunk_id=c_test_chunk_0"  # 405 sanity check
curl -X DELETE "http://localhost:8081/delete_chunk?chunk_id=c_test_chunk_0"

# traversal attempt -> must be rejected as JSON error, never written
curl -X POST --data-binary @/tmp/c_test_chunk_0.bin \
  "http://localhost:8081/store_chunk?chunk_id=../oops"

# verify files exist where expected (PowerShell)
dir .\data\node1\chunks\
```

## 7. Theory: Why Bytes-On-Disk Is This Simple

- Real systems (HDFS DataNode, Ceph OSD) also map object/chunk IDs to flat files or
  block offsets — but add checksums per chunk (CRC32C), compaction, and fsync ordering.
- We skip checksums for now; Phase 4 adds SHA-256 *verification* at the client, which is
  enough to prove correctness end-to-end.
- Flat directory + small files has inode overhead at scale; production systems shard
  directories (`chunks/ab/c_<uuid>.bin`). Optional stretch: hash the first 2 hex chars
  of the chunk ID into subdirectories.
