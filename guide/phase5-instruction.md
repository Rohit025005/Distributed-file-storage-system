# Phase 5: Replication, Failure Handling & Health Checks - Java Version

## Objective
Make the system tolerate a storage node going down — this is the "distributed systems" payoff. After this phase: kill a node mid-download and the client still succeeds; kill a node before upload and new files simply skip it.

## 1. Component Layout

```
src/main/java/com/dfs/
├── metadata/
│   ├── Store.java                 # + one small method: reassignLocation (stretch)
│   └── HealthMonitor.java         # NEW: background /health poller
├── api/MetadataHandlers.java      # constructor changes: config-driven nodes + monitor
└── cmd/metadata/Main.java         # builds & starts the monitor
```

Client-side failover already landed in Phase 4 (`fetchWithFailover`, warn-only replica
writes). This phase makes the *metadata server* failure-aware so uploads avoid dead
nodes, and formalizes the client rules.

## 2. Health Poller (`com.dfs.metadata.HealthMonitor`)

Design decisions:

1. **One background thread** via `ScheduledExecutorService.scheduleAtFixedRate`. Mark it daemon so it never blocks JVM shutdown.
2. **Liveness map**: `ConcurrentHashMap<String, Boolean> address → alive`. Request threads
   read it concurrently with poller writes — hence concurrent, not HashMap.
3. **Optimistic startup**: all configured nodes start as alive; the first failed probe
   flips them down. Avoids "everything down for 10s at boot".
4. **Timeouts must be shorter than the poll interval** — probe connect timeout ~2s,
   request timeout ~3s, interval 10s. Otherwise probes pile up and liveness lags reality.
5. **Log transitions only** (UP→DOWN, DOWN→UP), not every poll — log noise hides real
   problems.

| Method | Behavior |
|---|---|
| `HealthMonitor(Collection<String> addresses)` | seed map with all-alive |
| `start(long periodSeconds)` | schedule `pollAll` at fixed rate |
| `stop()` | `shutdownNow()` |
| `boolean isAlive(String address)` | map lookup; unknown = false |
| `List<String> liveOnly(List<String> addresses)` | filter preserving order |
| private `probe(address)` | GET `/health`, 200 → true, any exception → false |

## 3. Failure-Aware CreateFile

Replace Phase 2/3's hardcoded address list in `MetadataHandlers`:

- Constructor takes `(Store store, List<String> configuredNodes, HealthMonitor monitor)`.
- `handleCreateFile`: `nodeAddrs = monitor.liveOnly(configuredNodes)`; if empty → **503**
  `ErrorResponse("No healthy storage nodes available")`.
- Pass the filtered list to `store.CreateFile(req, liveNodes)`.

Your Phase-2 `CreateFile` already degrades gracefully: `maxReplicas =
min(factor - 1, nodes - 1)` means a 2-node cluster still works with factor 2 (primary +
1 replica), and a 1-node cluster stores primary-only. Verify that's still true after any
refactor.

Also load addresses from `config/nodes.yaml` via your Phase-1 `Config` — no more
hardcoded `localhost:808x`.

## 4. Client Rules (formalize what Phase 4 started)

**Read failover** (download): candidates in order `[primary, replicas...]`; first success
wins; fail only when all exhausted. Keep request timeouts tight (5–10s for chunk reads)
so a hung node doesn't stall the download.

**Write handling** (upload):
- Primary store fails → **abort the whole upload**. An under-written file is worse than
  a failed upload.
- Replica store fails → **warn and continue**. The file is readable from primary;
  the chunk is now *under-replicated*.

**Under-replication tracking (optional but recommended):**
- Client: on replica failure, `POST /report_chunk_loss?chunk_id=...&lost_node=...` to the
  metadata server (new endpoint).
- Metadata server: keep an in-memory `Map<String, Set<String>> underReplicated`
  (chunkId → missing holders). Expose `GET /under_replicated` for inspection.
- A DB table also works (`chunks_needing_repair`) if you want it to survive restarts —
  in-memory is fine for this project; document your choice.

## 5. Re-replication (stretch goal)

A repair job that heals under-replicated chunks automatically:

1. Periodically scan: for each chunk whose location sits on a DOWN node (or reported
   lost), choose a replacement target among ALIVE nodes not already holding the chunk.
2. Copy bytes server-side: metadata server (or the target node) does
   `GET /get_chunk` from any live holder → `POST /store_chunk` to the target.
3. Update the row:
   `UPDATE chunk_locations SET node_address = ? WHERE chunk_id = ? AND node_address = ?`
   (add `Store.reassignLocation(chunkId, oldAddr, newAddr)`).
4. Run it on its own single-threaded scheduler (e.g. every 30s), guarded so two repairs
   of the same chunk can't overlap.

Acceptable shortcuts at this scale: no locking protocol between repair and concurrent
uploads, best-effort copy without fsync, no verification hash re-check. Note these
limitations honestly in your README — real systems solve them with versioned leases.

## 6. Pitfalls To Avoid

1. **Blocking request threads on health state** — reads against the map are cheap;
   never do a synchronous `/health` probe inside `handleCreateFile`. That turns every
   upload into a 3s stall when a node dies.
2. **Poller thread leaks** — non-daemon scheduler threads keep the JVM alive after you
   close the server; make threads daemon + call `stop()` on shutdown hook.
3. **Flapping** — one dropped probe flips a node DOWN instantly. Fine here; production
   uses hysteresis (N consecutive failures). Mention it in comments.
4. **ConcurrentModification-style bugs** — iterate `alive.keySet()` while putting into
   the same map inside the loop is safe on `ConcurrentHashMap`, NOT on HashMap.
5. **Failover ordering bugs** — replicas list order must be deterministic (it is: derived
   from round-robin); don't shuffle or parallelize fetches yet.
6. **Forgetting the 503 path** — all nodes down must produce a clean JSON error, not a
   stack trace or an empty-chunk-list file.
7. **Repair racing delete** — stretch-goal only: if a file is deleted mid-repair you may
   recreate a chunk on the target node. Harmless orphan here; note it.

## 7. Checklist for Phase 5

- [ ] Health poller flips nodes up/down within one interval (watch the logs)
- [ ] Kill a storage node mid-download → client still succeeds via replica
- [ ] Kill a node before upload → new files' chunks never assigned to it (check response JSON)
- [ ] All nodes down → create_file returns 503 JSON error
- [ ] Restart the killed node → it rejoins liveness within one interval
- [ ] Under-replication report endpoint works (if implemented)
- [ ] (Stretch) Repair job re-copies chunks off dead nodes to healthy ones

### Manual verification script

```bash
# cluster up: metadata + node1..node4

# 1. watch liveness transitions
#    terminal: NODE_ID=node3 ... then Ctrl+C it; within ~10s metadata logs:
#    [health] node localhost:8083 -> DOWN

# 2. upload skips the dead node
mvn exec:java -Dexec.mainClass=com.dfs.cmd.client.Main -Dexec.args=upload test.bin
# inspect create_file response JSON: localhost:8083 appears NOWHERE

# 3. download survives
#    identify a chunk whose PRIMARY is node3 (from earlier uploads), kill node3,
mvn exec:java -Dexec.mainClass=com.dfs.cmd.client.Main "-Dexec.args=download <ID> out.bin"
# succeeds; client stderr shows WARN fallback lines

# 4. restart node3 -> [health] node localhost:8083 -> UP

# 5. kill ALL nodes -> upload attempt returns clean 503 error
```

## 8. Theory: What CAP Means For You

Right now your system chooses: **CP-flavored** writes (create_file fails rather than
assigning chunks to stale-dead nodes) with **AP-flavored reads** (any replica serves
bytes, no consensus). There is no leader election, no quorum — a real system adds Raft
for metadata and quorum writes for data. Knowing which operations are "planning" (needs
consistency) vs "data transfer" (tolerates staleness) is 80% of practical distributed
systems design.
