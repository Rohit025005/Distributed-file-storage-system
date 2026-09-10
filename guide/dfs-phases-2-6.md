# Phase 2: Metadata Server (SQLite + HTTP API)

## Objective
Build the metadata server that tracks files, chunks, and which nodes hold each chunk. This is the "brain" of the DFS — no actual file bytes live here, just bookkeeping.

## 1. Database Schema (`internal/metadata/schema.sql`)
```sql
CREATE TABLE IF NOT EXISTS files (
    file_id     TEXT PRIMARY KEY,
    filename    TEXT NOT NULL,
    size        INTEGER NOT NULL,
    chunk_size  INTEGER NOT NULL,
    created_at  DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS chunks (
    id      TEXT PRIMARY KEY,
    file_id TEXT NOT NULL,
    idx     INTEGER NOT NULL,
    size    INTEGER NOT NULL,
    FOREIGN KEY (file_id) REFERENCES files(file_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chunk_locations (
    chunk_id     TEXT NOT NULL,
    node_address TEXT NOT NULL,
    is_primary   BOOLEAN NOT NULL,
    PRIMARY KEY (chunk_id, node_address),
    FOREIGN KEY (chunk_id) REFERENCES chunks(id) ON DELETE CASCADE
);
```

## 2. Store Layer (`internal/metadata/store.go`)
- `Open(dbPath string) (*Store, error)` — opens SQLite, runs schema, enables `PRAGMA foreign_keys = ON`.
- `CreateFile(req types.CreateFileRequest, nodeAddrs []string) (*types.CreateFileResponse, error)`
  - Generates `file_id` (uuid), splits into `chunk_count` chunks
  - For each chunk: pick primary + replica node(s) round-robin across `nodeAddrs`
  - Insert file, chunks, chunk_locations in a single transaction
- `GetFileMetadata(fileID string) (*types.GetFileMetadataResponse, error)`
- `DeleteFile(fileID string) (*types.DeleteFileResponse, error)` — deletes row, returns chunk IDs so the caller can tell storage nodes to purge them
- `ListFiles() ([]types.File, error)` (used later by CLI `ls`)

## 3. HTTP Handlers (`internal/api/metadata_handlers.go`)
| Method | Path                     | Handler            |
|--------|--------------------------|---------------------|
| POST   | `/create_file`           | `handleCreateFile`  |
| GET    | `/get_file_metadata`     | `handleGetMetadata` (query param `file_id`) |
| DELETE | `/delete_file`           | `handleDeleteFile`  (query param `file_id`) |
| GET    | `/list_files`            | `handleListFiles`   |
| GET    | `/health`                | `handleHealth`      |

Use `net/http` + `chi` or stdlib `http.ServeMux` (Go 1.22+ pattern routing is fine, no need for a router lib).

## 4. Wire It Up (`cmd/metadata/main.go`)
- Load `config/nodes.yaml`
- Open SQLite store at `./data/metadata.db`
- Register handlers, listen on `:9090`

## Checklist for Phase 2
- [ ] SQLite schema created and migrations run on startup
- [ ] Store layer with transactional CreateFile
- [ ] Node assignment logic (round-robin, respects `ReplicationFactor`)
- [ ] All 5 HTTP endpoints implemented and manually tested with `curl`
- [ ] Unit tests for Store (in-memory `:memory:` SQLite)

---

# Phase 3: Storage Node (Chunk Storage)

## Objective
Implement the actual byte-storage service. Each storage node exposes a simple HTTP API to store/retrieve/delete chunks on local disk.

## 1. Storage Logic (`internal/storage/store.go`)
- `SaveChunk(chunkID string, data []byte) error` — writes to `./data/chunks/<chunk_id>.bin`
- `ReadChunk(chunkID string) ([]byte, error)`
- `DeleteChunk(chunkID string) error`
- `DiskFreeGB() (float64, error)` — for health checks (`syscall.Statfs` or `golang.org/x/sys/unix`)
- `ChunkCount() (int, error)` — count files in chunks dir

## 2. HTTP Handlers (`internal/api/storage_handlers.go`)
| Method | Path                | Handler            |
|--------|---------------------|---------------------|
| POST   | `/store_chunk`      | `handleStoreChunk`  (multipart or raw body, chunk_id in query/header) |
| GET    | `/get_chunk`        | `handleGetChunk`    (query param `chunk_id`) |
| DELETE | `/delete_chunk`     | `handleDeleteChunk` |
| GET    | `/health`           | `handleHealth`      (returns `types.HealthResponse`) |

## 3. Wire It Up (`cmd/storage/main.go`)
- Read node ID + address from a CLI flag or env var (`NODE_ID=node1 go run cmd/storage/main.go`)
- Create `./data/<node_id>/chunks/` directory if missing
- Listen on the port from `nodes.yaml` for that node ID

## Checklist for Phase 3
- [ ] Chunk save/read/delete working against local disk
- [ ] Health endpoint reports real disk free space + chunk count
- [ ] All 4 storage node endpoints tested with `curl`
- [ ] Can run 4 storage node instances concurrently on different ports

---

# Phase 4: Chunker + Client CLI (Upload/Download)

## Objective
Tie metadata + storage together into an actual client that can upload and download files.

## 1. Chunker (`internal/chunker/chunker.go`)
- `SplitFile(path string, chunkSize int) ([][]byte, error)` — reads file, splits into `chunkSize`-byte pieces (last chunk may be smaller)
- `JoinChunks(chunks [][]byte, outputPath string) error` — reverse operation for download
- Generate chunk IDs client-side or accept them from the metadata server's `CreateFileResponse`

## 2. Client Logic (`internal/client/client.go`)
- `Upload(filePath string) error`
  1. Read file, compute size + chunk count
  2. `POST /create_file` to metadata server → get back `Chunks []types.Chunk` with node assignments
  3. Split file into chunks matching that count
  4. For each chunk: `POST /store_chunk` to its `PrimaryNode`, then replicate to each `ReplicaNodes` entry
- `Download(fileID, outputPath string) error`
  1. `GET /get_file_metadata` → get chunk list + locations
  2. For each chunk (ordered by `Index`): `GET /get_chunk` from `PrimaryNode` (fall back to a replica on failure)
  3. `JoinChunks` and write to `outputPath`
- `Delete(fileID string) error`
  1. `DELETE /delete_file` on metadata server → get `ChunksToDelete`
  2. `DELETE /delete_chunk` on every node that held each chunk

## 3. CLI Commands (`cmd/client/main.go`)
```
dfs upload <local-path>
dfs download <file-id> <output-path>
dfs delete <file-id>
dfs ls
```
Use `flag` or `cobra` for subcommand parsing (stdlib `flag` is enough for 4 commands).

## Checklist for Phase 4
- [ ] Chunker splits/joins files byte-for-byte correctly (test with a >1MB file)
- [ ] `dfs upload` stores primary + replica copies correctly
- [ ] `dfs download` reconstructs an identical file (checksum compare)
- [ ] `dfs delete` cleans up chunks on every node that held them
- [ ] End-to-end test: upload → download → diff → delete

---

# Phase 5: Replication, Failure Handling & Health Checks

## Objective
Make the system tolerate a storage node going down — this is the "distributed systems" payoff.

## 1. Read Failover
- In `Download`, if the primary node returns an error or times out, retry against each replica in order before failing the whole download.

## 2. Write Failure Handling
- In `Upload`, if a replica write fails, log a warning but don't fail the whole upload (primary write failing *should* fail it).
- Track "under-replicated" chunks (optional: a `chunks_needing_repair` table).

## 3. Node Health Polling
- Metadata server periodically (`time.Ticker`, e.g. every 10s) hits `/health` on each configured node.
- Maintain an in-memory `map[string]bool` of node liveness.
- `CreateFile`'s node-assignment logic skips nodes currently marked down.

## 4. Re-replication (stretch goal)
- If a node stays down past a threshold, scan chunks that had it as primary/replica and re-replicate them to a healthy node.

## Checklist for Phase 5
- [ ] Kill a storage node mid-download → client still succeeds via replica
- [ ] Kill a storage node before upload → new files skip that node
- [ ] Health poller correctly flips nodes up/down in real time
- [ ] (Stretch) Re-replication job implemented and tested

---

# Phase 6: Polish, Testing & Observability

## Objective
Round out the project into something demo-ready and resume-worthy.

## 1. Error Handling & Response Consistency
- Every handler returns `types.ErrorResponse` with a proper HTTP status code on failure — no bare 500s with empty bodies.

## 2. Concurrency Safety
- Storage node: guard chunk read/write with per-chunk locks if you expect concurrent access (`sync.Map` of mutexes), or rely on the filesystem being safe enough for this scope — document your choice.
- Metadata server: SQLite writes are serialized by default; make sure your transaction boundaries are correct under concurrent uploads.

## 3. Logging
- Structured logging (`log/slog`) across all three binaries: request method/path, latency, chunk IDs touched.

## 4. Integration Test Suite
- Spin up metadata server + 4 storage nodes in-process (`httptest.Server`) in a Go test, run upload/download/delete against them.

## 5. README + Demo Script
- Architecture diagram (client → metadata → storage nodes)
- `make run-all` / a small shell script to boot all 4 storage nodes + metadata server for local demo
- A recorded/scripted demo: upload a file, kill a node, download successfully anyway

## Checklist for Phase 6
- [ ] Consistent error responses across all endpoints
- [ ] Concurrency behavior documented and tested
- [ ] Structured logs on all three binaries
- [ ] `go test ./...` passes an integration suite, not just unit tests
- [ ] README with architecture diagram and demo steps
