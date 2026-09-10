# Phase 6: Polish, Testing & Observability - Java Version

## Objective
Round the project out into something demo-ready and resume-worthy: consistent errors, documented concurrency behavior, structured logs, a real integration test suite, and a README with a one-command demo.

## 1. Error Handling & Response Consistency

Goal: **no bare 500s with stack traces, ever**. Every failure is a JSON body:

```json
{ "error": "human readable message", "details": "optional context" }
```

Approach — formalize what you've been doing ad hoc:

1. Add `com.dfs.api.ApiException extends RuntimeException` carrying an HTTP status.
2. Throw it from handlers/store for *expected* failures:
   - not found → 404 (`File not found`, `Chunk not found`)
   - bad input → 400 (missing params, unsafe chunk id)
   - method mismatch → 405
   - no healthy nodes → 503
3. The shared `guard()` wrapper maps: `ApiException` → its status; anything else → 500.
4. Extract `guard`, `queryParam`, and JSON-writing into ONE place used by both servers
   (e.g. `com.dfs.api.HttpKit`) instead of copy-pasted statics in two Mains.

## 2. Concurrency Safety

Document your choices in code comments AND the README:

- **Storage node**: concurrent uploads can write/read/delete the same chunk ID. Guard
  mutations with per-chunk locks: `ConcurrentHashMap<String, Object>` of lock objects,
  `synchronized (lockFor(chunkId))`. Reads can stay lock-free if you write atomically
  via temp-file + `Files.move(..., REPLACE_EXISTING)` — pick ONE scheme, justify it,
  and say so explicitly.
- **Metadata server**: SQLite serializes writers; your transactional CreateFile plus
  synchronized store methods are sufficient at this scale. State that SQLite's default
  journal mode means readers may block briefly during writes.
- **HealthMonitor / RepairJob**: already single-threaded by design.

## 3. Logging

Use `java.util.logging` (JUL) or `System.getLogger`. Use JUL:

- One named logger per class: `Logger log = Logger.getLogger(DfsClient.class.getName())`.
- Request line logging in `guard()`: method, path, status, latency ms. Compute status by
  tracking whether the endpoint completed (wrap sendJson to record it).
- Log chunk IDs touched on storage nodes; file IDs + chunk counts on metadata ops.
- Configure format once in each Main: `System.setProperty("java.util.logging.SimpleFormatter.format", ...)`.

## 4. Integration Test Suite

JUnit 5 tests that spin up the WHOLE cluster in-process:

- Metadata server: real `Store` on a **temp-file DB** (see pitfall #1 below), `HttpServer`
  on port `0` (ephemeral).
- 4 storage nodes: `HttpServer(0)` each with their own `ChunkStore` under `@TempDir`.
- Real `DfsClient` pointed at the ephemeral metadata port.
- Test flow: random ~300KB file → upload → download → assert byte equality (+ SHA-256)
  → delete → assert metadata empty and `.bin` files gone.

To make this testable, refactor both Mains so server construction is reusable:

```
MetadataServer.start(Store store, int port, List<String> nodeAddrs, HealthMonitor monitor) -> HttpServer
StorageServer.start(int port, String nodeId, Path chunksDir) -> HttpServer
main(...) = parse args + call start(...)
```

### pom.xml additions

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
```

plus surefire (picks up JUnit 5 automatically):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version>
</plugin>
```

## 5. README + Demo Script

README contents:
- Architecture diagram (ASCII): client → metadata (SQLite) → storage nodes (disk chunks)
- How to build/run everything locally (metadata + 4 nodes + CLI examples)
- API reference table (all endpoints across both services)
- Design notes: chunk size, replication factor, round-robin assignment, failover rules,
  concurrency choices, known limitations (single metadata server = SPOF, no auth/TLS,
  best-effort repair)

Demo script `run-all.ps1` (PowerShell) + `run-all.sh`: starts metadata then nodes 1–4 in
background windows/jobs, waits for `/health`, prints ready message. Scripted demo:
upload → checksum → kill node2 → download still OK → delete → cleanup.

## 6. Pitfalls To Avoid

1. **The `jdbc:sqlite::memory:` trap**: your Phase-2 Store opens a NEW connection per
   operation. Each `:memory:` connection gets its own EMPTY database — schema included.
   In-memory testing silently breaks. Use a temp FILE DB (`Files.createTempFile`) in
   tests, or switch Store to hold one long-lived connection. Pick deliberately.
2. **Double response commit** — after refactoring error paths, make sure no handler can
   call `sendResponseHeaders` twice (guard only sends when the endpoint threw).
3. **Latency measured wrong** — measure around the whole guard body including error
   responses; finally-block placement matters.
4. **Tests binding fixed ports** — always use port `0` and read back
   `server.getAddress().getPort()`; fixed ports break in CI.
5. **Test isolation** — point each test's stores at `@TempDir`; leftover `./data` state
   from manual runs makes tests flaky.
6. **Logging config noise** — JUL's default level logs fine, but set the SimpleFormatter
   pattern ONCE per process or every line has 4 lines of header.

## 7. Checklist for Phase 6

- [ ] Consistent JSON error responses with correct statuses (400/404/405/500/503) everywhere
- [ ] Concurrency approach implemented AND documented (per-chunk locks or atomic rename)
- [ ] Request logging with method/path/status/latency on both servers
- [ ] Integration test suite green: `mvn test` boots cluster in-process end-to-end
- [ ] Unit tests still pass (chunker round-trip incl. >1MB, Store CRUD, HealthMonitor probe logic)
- [ ] README with architecture diagram, run instructions, API table, design notes
- [ ] `run-all` script boots full local demo in one command
- [ ] Recorded/scripted demo: upload → kill node → download survives

## 8. Theory: What You Built vs Production Systems

| Concern | Yours | HDFS/GFS/Ceph |
|---|---|---|
| Metadata HA | single SQLite server | replicated Raft/ZAB quorum |
| Chunk placement | round-robin | topology/rack-aware, balanced |
| Failure detection | 10s poller | heartbeats + phi-accrual |
| Repairs | best-effort re-copy | pipeline replication, erasure coding |
| Consistency | last-writer-wins per chunk | leases, versioned reads |
| Auth | none | Kerberos/mTLS |

Being able to explain EACH row of this table — what you chose, why, and what breaks at
scale — is exactly what makes this project interview-ready.
