# DFS Theory Guide — Know It Inside Out

> Everything you built in Phases 1–6, explained from first principles to design tradeoffs,
> plus the interview questions you're most likely to get. If you can answer every question
> in §10 without looking, you own this project.

---

## 1. The Big Picture

### 1.1 What is a Distributed File System?

A **Distributed File System (DFS)** stores files across many machines while presenting
them as one unified namespace (`upload file.zip` / `download <id>`), exactly like a local
filesystem would. You use one daily: Google Drive, HDFS (Hadoop), GFS (Google), S3,
Ceph.

Why bother?
- **Capacity**: aggregate the disks of N machines instead of one giant box.
- **Durability**: a machine dying must not lose data → replicate chunks.
- **Throughput**: parallel reads/writes across machines.
- **Elasticity**: add nodes to grow storage.

Your project implements the classic **GFS-style architecture** at toy scale:
4 storage nodes on localhost, 64KB chunks, replication factor 2.

### 1.2 The Three Components

```
                    CONTROL PLANE                DATA PLANE
   ┌────────┐   plan (JSON)   ┌──────────────────┐
   │ Client │ ◄─────────────► │  Metadata Server │      ┌────────────────┐
   │  CLI   │                 │   port :9090     │      │ Storage Node 1 │ :8081
   └───┬────┘                 │   SQLite         │      │ Storage Node 2 │ :8082
       │                      └────────▲─────────┘ poll │ Storage Node 3 │ :8083
       │ raw chunk bytes               │ /health        │ Storage Node 4 │ :8084
       ▼                               │                └────────────────┘
   chunks land as .bin files ──────────┴── clients push/pull bytes DIRECTLY to nodes
```

| Component | Port(s) | Holds | Responsibility |
|---|---|---|---|
| **Client** | — | nothing | splits/joins files, pushes/pulls bytes, orchestrates everything |
| **Metadata server** | 9090 | SQLite DB (files, chunks, chunk_locations) | the "brain": plans where chunks go, tracks what exists where, monitors node liveness |
| **Storage nodes** | 8081–8084 | `.bin` files on disk | dumb byte buckets: store/read/delete a chunk by ID |

### 1.3 The Single Most Important Architectural Decision

**Metadata never touches file bytes.** The metadata server only *plans* (which node gets
which chunk); the *client* streams bytes directly to/from storage nodes.

Why this matters (say this in interviews):
- Metadata stays tiny and fast — it scales with the number of *files*, not total *bytes*.
- Data throughput doesn't funnel through one bottleneck server.
- Cost: clients become complex (they need failover logic, retry rules) because they now
  talk to many fallible machines. This tradeoff is exactly why Phase 5 exists.

This is GFS/HDFS's core trick. HDFS NameNode doesn't serve a single byte of file data;
DataNodes do.

---

## 2. Data Model & Schema

Three tables encode the entire namespace:

```
files            one row per uploaded file
 ├── file_id     UUID (the ONLY handle the user keeps)
 ├── filename    original name (cosmetic)
 ├── size        total bytes
 ├── chunk_size  snapshot of chunk size at upload time
 └── created_at

chunks           one row per piece of a file
 ├── id          "<file_id>_chunk_<n>"  (deterministic, debuggable)
 ├── file_id     FK → files, ON DELETE CASCADE
 ├── idx         position in the file (0-based)
 └── size        actual bytes (last chunk may be smaller)

chunk_locations  one row per COPY of a chunk (primary + replicas)
 ├── chunk_id    FK → chunks, ON DELETE CASCADE
 ├── node_address  e.g. localhost:8083
 ├── is_primary  which copy is authoritative for reads-first
 └── PK (chunk_id, node_address)
```

Key properties to articulate:
- **Cascade deletes**: deleting the `files` row wipes all dependent rows in SQL, so
  metadata cleanup is atomic-ish and simple.
- **Composite PK** prevents assigning two copies of the same chunk to the same node.
- **Chunk IDs are deterministic** (`fileId_chunk_idx`) — anyone can verify a chunk's
  origin by reading its name. Real systems use content hashes or random IDs.
- A 300KB file with 64KB chunks = 1 file row + 5 chunk rows + 10 location rows
  (5 primaries × RF 2).

---

## 3. Program Flow Walkthroughs

Memorize these five flows cold. For each: who talks to whom, in what order, and why
that order.

### 3.1 Upload

```
Client                          Metadata                        Storage Nodes
  │  POST /create_file             │                                │
  │ {filename,size,chunkCount} ──► │ filter dead nodes              │
  │                                │ BEGIN TRANSACTION              │
  │                                │  INSERT files row              │
  │                                │  for each chunk i:             │
  │                                │   primary = nodes[i % N]       │
  │                                │   replicas = next RF-1 nodes   │
  │                                │   INSERT chunks + locations    │
  │                                │ COMMIT                         │
  │ ◄── {fileId, chunks[...]}      │                                │
  │ split local file (64KB pieces) │                                │
  │ for each chunk:                │                                │
  │  POST /store_chunk?chunk_id=.. ┼── raw bytes ─────────────────► │ primary (MUST succeed)
  │                                ├──────────────────────────────► │ replicas (warn-only)
  │ print fileId                   │                                │
```

Order-sensitive details:
- Plan FIRST, then push bytes. If planning fails (503 no healthy nodes), no bytes move.
- Primary write failure aborts the upload; replica failures only warn → the file is
  readable but **under-replicated**.
- The transaction wraps ALL inserts: a crash mid-loop must not leave a file row whose
  chunks were never fully registered.

### 3.2 Download

```
Client                          Metadata                        Storage Nodes
  │ GET /get_file_metadata         │                                │
  │ ?file_id=... ────────────────► │ join chunks+locations          │
  │ ◄── ordered chunk list w/      │                                │
  │     primary + replicas each    │                                │
  │ sort by index (don't trust JSON order)                          │
  │ for each chunk:                                                 │
  │  try primary  GET /get_chunk ──┼──────────────────────────────► │
  │  catch → try replicas in order ► first success wins            │
  │  all fail → whole download fails                                │
  │ joinChunks → output file                                        │
  │ (optionally SHA-256 compare)                                    │
```

### 3.3 Delete — THE ordering trap

```
1. GET /get_file_metadata?file_id=X   ← BEFORE anything else!
2. collect every holder (primaries + replicas)
3. DELETE /delete_file?file_id=X      ← cascade wipes metadata rows
4. for each chunkId × each holder: DELETE /delete_chunk?chunk_id=...
```

If you delete metadata first, you can never learn where the chunks lived → orphaned
`.bin` files forever. Interview gold: *"what happens if step 4 partially fails?"* →
orphans on dead nodes; production systems run garbage-collection scans comparing disk
contents vs metadata (and that's also how you'd clean up).

### 3.4 Health Monitoring (metadata side)

```
every 10s (daemon ScheduledExecutorService thread):
  for each configured node address:
    GET http://addr/health  (connect timeout 2s, request timeout 3s)
    alive[addr] = (status == 200)
    log only TRANSITIONS (UP→DOWN / DOWN→UP)
create_file handler:
  liveOnly(configuredNodes)   ← map READ only, never a live probe
  if empty → 503
```

Rule worth quoting: **probe timeouts < poll interval**, otherwise probes pile up and
liveness always lags reality.

### 3.5 Re-replication (stretch)

```
every 30s:
  for each DOWN node D:
    for each chunk C located on D:
      source = any ALIVE holder of C        (read from a surviving copy)
      target = ALIVE node not holding C     (restore RF)
      GET chunk from source → POST to target
      UPDATE chunk_locations SET node=target WHERE chunk=C AND node=D
```

Known shortcuts (state them honestly): no locking against concurrent deletes (possible
orphan), no post-copy hash verification, best-effort only.

---

## 4. Core Concepts (definitions you must nail)

### 4.1 Chunking
Files are cut into fixed-size pieces (here 64KB). Why not store whole files?
- Parallelism: different chunks read/write simultaneously across nodes.
- Load balancing: no single huge-file hotspot.
- Pipelining: a client can stream chunk N while receiving N−1.
Tradeoffs: smaller chunks = more metadata rows + more HTTP requests; larger = less
parallelism granularity. GFS uses 64MB, HDFS 128MB — we use 64KB so multi-chunk behavior
is demoable locally.

### 4.2 Replication Factor
Each chunk has RF copies (here 2: 1 primary + 1 replica).
- Tolerates RF−1 simultaneous node failures per chunk.
- Read availability improves (any copy serves).
- Write amplification: storing X bytes writes ~RF×X over the network.
- RF=3 is industry standard (survives maintenance + one failure concurrently).

### 4.3 Round-Robin Placement
Chunk i's primary = `nodes[i mod N]`; replicas follow the primary (wrapping).
Properties: uniform spread (no node overload), deterministic (reproducible/debuggable),
simple. Production systems go further: rack-aware placement (copies on different racks
so a switch failure doesn't kill both copies), load-aware balancing, randomization to
avoid sync patterns.

### 4.4 Failover
On read failure, walk candidates `[primary, replicas…]`, first success wins. Requires:
- short connect timeouts (a dead host's TCP connect otherwise hangs ~minutes), and
- deterministic candidate order (we keep replica order stable).

### 4.5 Heartbeats / Health Checks
Our model is *pull*-based: metadata polls nodes. HDFS is *push*-based: DataNodes send
heartbeat + block reports to the NameNode every few seconds. Pull is simpler and fine at
our scale; push scales better and carries richer state. Marking a node down after ONE
failed probe is aggressive ("flapping"); production uses N consecutive misses or
phi-accrual scoring.

### 4.6 CAP Theorem
In a network partition you choose: Consistency or Availability.
- Our **writes are CP-flavored**: create_file refuses (503) rather than assign chunks to
  possibly-dead nodes — we prefer failing over serving stale plans.
- Our **reads are AP-flavored**: any replica serves bytes, no consensus about which copy
  is "freshest" (fine because immutable chunks are written once).
Interview line: *"I know which operations need coordination (planning/metadata) versus
which tolerate staleness (bulk data transfer), and I chose differently for each."*

### 4.7 Transactions & Crash Safety
CreateFile inserts into 3 tables. Without `BEGIN … COMMIT` (`setAutoCommit(false)`), a
crash between inserts leaves an orphaned file row → downloads would see a file with
missing chunks. All-or-nothing via rollback. Related concept: our per-op connection +
transaction ≈ SQLite gives us atomicity; durability comes from SQLite's journal.

### 4.8 Idempotency
Re-running `store_chunk` with the same ID overwrites cleanly (idempotent) — that's why
chunk-level retries are safe. Delete operations are also idempotent (`deleteIfExists`).
Retrying non-idempotent ops needs IDs/dedup; we sidestepped that by design.

### 4.9 Single Point of Failure (SPOF)
One metadata server = SPOF. If it dies: no new uploads/downloads (clients can't resolve
locations) even though all chunk data still exists on nodes. Production fixes: Raft/ZAB
replicated metadata (HDFS HA with JournalNodes+ZKFC), or leader election. Being able to
name your SPOF and its blast radius is very strong interview signal.

### 4.10 Consistency Model
We offer last-writer-wins per chunk with **immutable-after-write** chunks: chunks are
never edited, only overwritten wholesale or deleted. That dodges the hard problems
(partial updates, version conflicts). Mutable-file support needs versioning or leases —
explicitly out of scope, and saying so shows judgment.

---

## 5. Technology Choices & Why (Java specifics)

| Choice | What we did | Why / alternative |
|---|---|---|
| Language/runtime | Java 21 | records give Go-struct-like immutable DTOs |
| Records | Node, Chunk, all API types | immutability = thread-safe value objects, auto equals/hashCode |
| Build | Maven | dependency management, `exec:java`, surefire |
| JSON | Jackson everywhere | serializes records natively; picked ONE lib deliberately (Gson dropped) |
| HTTP server | `com.sun.net.httpserver` | stdlib, zero deps, good enough for learning; alt: Javalin/Spring |
| HTTP client | `java.net.http.HttpClient` | modern, timeouts built-in, cleaner than HttpURLConnection |
| Storage DB | SQLite via JDBC | zero-config embedded relational store; alt: Postgres, etcd |
| Disk layout | flat `data/<node>/chunks/*.bin` | simple; prod shards dirs + checksums per block |
| Concurrency | ConcurrentHashMap, synchronized, ScheduledExecutorService, daemon threads | right-sized tools; no framework magic |

Java-specific gotchas you hit (mention these as war stories):
- Multiple public top-level types in one file don't compile → one record per file.
- Records have NO setters/getters-with-set semantics → build them once from collected
  data (drove the map-grouping rewrite of GetFileMetadata).
- Writing body after `sendResponseHeaders(status, -1)` is illegal (-1 = no body).
- Non-daemon scheduler threads keep JVM alive after main returns.
- Each `jdbc:sqlite::memory:` connection is a separate empty DB — broke test plans;
  temp-file DBs instead.
- `File.getUsableSpace()` replaces Go's `syscall.Statfs` cross-platform.

---

## 6. Concurrency Model (per component)

**Storage node**
- Default executor handles requests on multiple threads → concurrent access possible.
- Writes/deletes serialized per chunk via `ConcurrentHashMap<String,Object>` lock
  objects (`computeIfAbsent` so lock creation is itself race-free).
- Reads lock-free: worst case during non-atomic overwrite is a torn read; closed by
  writing tmp + `Files.move(REPLACE_EXISTING)` inside the lock.
- Lock objects leak after delete — bounded by distinct chunk IDs ever seen; documented,
  acceptable.

**Metadata server**
- SQLite serializes writers internally; our transaction boundaries make multi-row
  writes atomic.
- Liveness map: poller thread writes, request threads read → ConcurrentHashMap (HashMap
  here is a real data race).
- RepairJob single-threaded + per-chunk "in progress" set avoids double repair.

**Client**
- Sequential per operation (no pipelining yet). Obvious upgrade: parallel chunk upload
  with a fixed-size thread pool — discuss what breaks (server-assigned order, error
  aggregation, partial-failure cleanup).

---

## 7. Failure Scenario Drills

Walk through these out loud:

1. **Node dies mid-download** → chunk fetch times out (3s connect cap) → client tries
   replica → download succeeds. User sees WARN logs only.
2. **Primary's node dies mid-upload** → that chunk's store fails → whole upload aborts
   (by policy). Metadata row already committed! → orphaned plan with missing bytes.
   Mitigation exercise: client calls delete(fileId) in the failure path.
3. **Node dies before upload** → health poller flips it DOWN ≤10s → create_file filters
   it out → new chunks avoid it entirely.
4. **All nodes die** → liveOnly empty → 503 JSON error, clean failure.
5. **Replica write fails, primary ok** → warn + report under-replicated → repair job
   later restores RF by copying from primary.
6. **Metadata server dies mid-upload** → some chunks stored on nodes but plan incomplete
   → those bytes unreachable (IDs known only from response that never arrived) → GC scan
   territory again.
7. **Two clients create files simultaneously** → SQLite transactions serialize; each
   gets its own UUID; round-robin interleaves harmlessly.
8. **Network partition (node up but slow)** → probes time out → marked down → traffic
   reroutes. False-positive downtime costs capacity, not correctness — CAP in action.

---

## 8. Numbers Cheat Sheet

| Value | Ours | Industry reference |
|---|---|---|
| Chunk size | 64 KB | GFS 64MB, HDFS 128MB |
| Replication factor | 2 (1 primary + 1 replica) | typically 3 |
| Health poll interval | 10 s | HDFS heartbeat 3 s |
| Probe timeouts | 2 s connect / 3 s request | — |
| Repair cycle | 30 s (stretch) | continuous |
| Metadata port / node ports | 9090 / 8081–8084 | — |
| Max chunks per 300KB file @64KB | ceil(300000/65536) = 5 | — |

Math you may be asked: chunks for size S with chunk size C = `(S + C − 1)/C` (integer
ceil, never floats); copies on disk = S × RF; metadata rows ≈ chunks × RF.

---

## 9. Yours vs Production (the maturity table)

| Concern | This project | Production systems |
|---|---|---|
| Metadata HA | single SQLite server (SPOF) | Raft/ZAB quorum (HDFS HA, Kudu, TiKV) |
| Placement | round-robin | topology/rack-aware + balancers |
| Failure detection | 10s polling, 1-strike DOWN | push heartbeats, phi-accrual, hysteresis |
| Repairs | periodic best-effort re-copy | continuous pipelines, erasure coding (RS 6+3) |
| Integrity | client-side SHA-256 check | per-block CRC32C checked on every read/scan |
| Consistency | immutable chunks, LWW | leases, version stamps, quorum writes |
| Auth/TLS | none | Kerberos, mTLS, token auth |
| Scale ceiling | toy (localhost) | thousands of nodes, PB of data |
| Deletes | immediate fan-out purge | tombstones + asynchronous GC |

Being fluent in EVERY row — what you chose, why, what breaks at scale, and what the fix
is called — is what makes this resume-ready.

---

## 10. Interview Q&A Drill

**Q: Walk me through what happens when a user uploads a file.**
A: Client computes size/chunkCount, POSTs create_file. Metadata filters healthy nodes,
generates a UUID, and in ONE transaction inserts the file row plus per-chunk rows with
round-robin assignments (primary rotates per chunk, replicas follow). Client then splits
locally and pushes raw bytes to each chunk's primary, then replicas. Primary failure
aborts; replica failure warns and reports under-replication.

**Q: Why does metadata not store file data?**
A: Keeps the coordinator stateless w.r.t. bytes — it scales with file count not volume,
avoids being a bandwidth bottleneck, mirrors GFS/HDFS separation of control and data
planes. Tradeoff: clients must implement failover/retry themselves.

**Q: Why is round-robin used? What are its weaknesses?**
A: Deterministic uniform spread, trivially implementable, spreads hot spots evenly.
Weaknesses: ignores node capacity/load, ignores topology (both copies could sit behind
one failing switch), and correlated assignment patterns. Production adds rack-awareness
and load balancing.

**Q: A storage node crashes permanently with RF=2. What does the user experience?**
A: Downloads still succeed via replicas (failover). New uploads skip the node once the
health poller marks it down. Chunks whose BOTH copies were on dead nodes are lost —
that's why real systems use RF≥3 or erasure coding. My stretch repair job re-copies
stranded chunks to survivors within ~30s.

**Q: Where is your consistency weakest and why is that OK?**
A: Reads — any replica serves without freshness checks. OK because chunks are immutable
after write; there's no update anomaly to expose. Writes are the consistent path:
transactional, planned only against live nodes.

**Q: Explain the delete-ordering bug you avoided.**
A: delete_file cascades away the chunk_locations rows; afterwards nobody knows which
nodes hold the bytes. So the client snapshots locations FIRST, deletes metadata second,
purges third. Partial purge failures leave orphans that only a disk-vs-metadata GC scan
could find.

**Q: How do you detect a dead node and what are the tradeoffs?**
A: Pull-based: daemon scheduler GETs /health every 10s with 2–3s timeouts; result cached
in a ConcurrentHashMap that request handlers read. Tradeoffs: pull adds detection lag
(≤ interval) vs push heartbeats; single failed probe marks down (flapping risk) vs
N-strike hysteresis; handlers must never probe synchronously or every upload stalls.

**Q: Why SQLite? Its limits?**
A: Zero-config, transactional, perfect for a single-server learning project. Limits: one
writer at a time, single machine, no network replication — hence it IS my SPOF.
Production answer: replicated KV/consensus store (etcd/Raft) for metadata.

**Q: What happens on concurrent uploads of the same file? Two different files?**
A: Same filename twice → independent UUIDs, no dedup (content-addressable storage would
fix that: hash → same chunks stored once). Different files → transactions serialize in
SQLite; round-robin interleaves; no interference beyond write contention.

**Q: What would you do next if you had another month?**
A (pick 2–3, ordered by impact): parallel chunk transfers with bounded thread pool;
checksum-per-chunk stored in metadata + verified on read; metadata HA via Raft
(or even a simple standby + fsync'd WAL shipping); erasure coding experiment; GC scan
for orphaned chunks; proper integration tests for kill-node scenarios using fault
injection instead of manual kills.

**Q: Biggest bug you hit and how you found it?**
A (true stories from this build): fake round-robin where every primary was node1 — found
by asserting primaries rotate when creating a 4-chunk file; GetFileMetadata duplicating
chunks by adding to a list AND returning map values — found by count mismatch vs
create_file response; body-after-sendResponseHeaders(-1) producing silent empty errors —
found via curl -i showing truncated responses.

---

## 11. Glossary (one-liners)

- **Control plane / data plane**: decision-making traffic vs bulk payload traffic.
- **Namespace**: logical view of files, independent of physical placement.
- **Under-replicated**: chunk with fewer live copies than RF.
- **Flapping**: node rapidly oscillating up/down due to marginal connectivity.
- **Hysteresis**: requiring repeated evidence before flipping state (anti-flapping).
- **Torn read**: reader sees half-written data; prevented by atomic rename.
- **Write amplification**: physical bytes written ÷ logical bytes (≈RF here).
- **GC scan**: comparing actual disk contents vs metadata to find orphans.
- **Erasure coding**: split chunk into k shards + m parity; survive any m losses with
  k+m/k storage overhead vs RF× overhead.
- **Lease**: time-bounded lock letting a primary cache mutable state safely.
- **Quorum**: majority agreement (⌊n/2⌋+1) making decisions partition-safe.
- **SPOF**: component whose failure halts the system; name yours proactively.
