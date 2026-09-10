# Phase 5: Solution - Replication, Failure Handling & Health Checks in Java

> Reference implementation for `phase5-instruction.md`. Try building it yourself first,
> then compare.

## 1. Health Poller (`src/main/java/com/dfs/metadata/HealthMonitor.java`)

```java
package com.dfs.metadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Polls /health on every configured storage node and tracks liveness.
 * One daemon thread; request handlers only ever READ the map.
 */
public class HealthMonitor {

    /** Probe timeouts MUST be shorter than the poll interval. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final Map<String, Boolean> alive = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final ScheduledExecutorService scheduler;

    public HealthMonitor(Collection<String> addresses) {
        // Optimistic start: assume all up until the first failed probe.
        addresses.forEach(a -> alive.put(a, Boolean.TRUE));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-monitor");
            t.setDaemon(true);   // never block JVM shutdown
            return t;
        });
    }

    public void start(long periodSeconds) {
        scheduler.scheduleAtFixedRate(this::pollAll, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void pollAll() {
        for (String address : alive.keySet()) {          // safe on ConcurrentHashMap
            boolean nowAlive = probe(address);
            Boolean previous = alive.put(address, nowAlive);
            if (previous != null && previous != nowAlive) {
                System.out.println("[health] node " + address + " -> " + (nowAlive ? "UP" : "DOWN"));
            }
        }
    }

    private boolean probe(String address) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + address + "/health"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;  // timeout, connect refused, malformed response — all mean DOWN
        }
    }

    /** Request threads call this — map read only, never probe here. */
    public boolean isAlive(String address) {
        return alive.getOrDefault(address, false);
    }

    /** Filter to live nodes, preserving input order (keeps round-robin deterministic). */
    public List<String> liveOnly(List<String> addresses) {
        return addresses.stream().filter(this::isAlive).collect(Collectors.toList());
    }
}
```

## 2. Failure-Aware MetadataHandlers (changes to `com.dfs.api.MetadataHandlers`)

Constructor now takes configured nodes + monitor:

```java
private final Store store;
private final List<String> configuredNodes;
private final HealthMonitor monitor;
private final ObjectMapper objectMapper = new ObjectMapper();

public MetadataHandlers(Store store, List<String> configuredNodes, HealthMonitor monitor) {
    this.store = store;
    this.configuredNodes = configuredNodes;
    this.monitor = monitor;
}
```

`handleCreateFile` becomes failure-aware:

```java
/** Handle POST /create_file */
public void handleCreateFile(HttpExchange exchange) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader br = new BufferedReader(
            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
    }

    CreateFileRequest req = objectMapper.readValue(sb.toString(), CreateFileRequest.class);

    // Filter out dead nodes — map READ only, no synchronous probing here.
    List<String> liveNodes = monitor.liveOnly(configuredNodes);
    if (liveNodes.isEmpty()) {
        sendError(exchange, 503, "No healthy storage nodes available");
        return;
    }

    sendJson(exchange, 200, store.CreateFile(req, liveNodes));
}
```

(`CreateFile` needs no changes: `maxReplicas = min(factor-1, liveCount-1)` already
degrades gracefully with fewer nodes.)

## 3. Wire It Up (`com.dfs.cmd.metadata.Main` — changed lines)

```java
public static void main(String[] args) throws IOException {
    Store store = new Store("./data/metadata.db");

    // Config-driven node list (no more hardcoded localhost:808x)
    Config config = Config.loadFromFile("config/nodes.yaml");
    List<String> nodeAddresses = config.getNodeAddresses();

    HealthMonitor monitor = new HealthMonitor(nodeAddresses);
    monitor.start(10);   // poll every 10s

    MetadataHandlers handlers = new MetadataHandlers(store, nodeAddresses, monitor);

    HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);
    // ... contexts exactly as Phase 2 ...

    Runtime.getRuntime().addShutdownHook(new Thread(monitor::stop));
    server.start();
    System.out.println("Metadata server started on port 9090");
}
```

Add `import com.dfs.config.Config; import com.dfs.metadata.HealthMonitor;`.

## 4. Under-Replication Reporting (optional)

In `MetadataHandlers`, add an in-memory tracker + two endpoints:

```java
// chunkId -> set of nodes believed to have lost their copy
private final Map<String, Set<String>> underReplicated = new ConcurrentHashMap<>();

/** POST /report_chunk_loss?chunk_id=...&lost_node=... */
public void handleReportChunkLoss(HttpExchange exchange, String chunkId, String lostNode)
        throws IOException {
    underReplicated.computeIfAbsent(chunkId, k -> ConcurrentHashMap.newKeySet()).add(lostNode);
    sendJson(exchange, 200, Map.of("reported", true));
}

/** GET /under_replicated */
public void handleUnderReplicated(HttpExchange exchange) throws IOException {
    sendJson(exchange, 200,
        underReplicated.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
}
```

Client side (`DfsClient.upload`) — after a replica failure:

```java
} catch (IOException e) {
    System.err.println("WARN: replica write failed on " + replica + ": " + e.getMessage());
    try {
        HttpUtil.postJson(metadataUrl
            + "/report_chunk_loss?chunk_id=" + meta.id() + "&lost_node=" + replica, "{}");
    } catch (IOException ignored) { /* metadata down too — nothing to report to */ }
}
```

In-memory was chosen over a DB table deliberately: it's diagnostic state, not durable
state. Document that choice in your code comment.

## 5. Stretch: Re-replication Job

New method on `Store`:

```java
/**
 * Move one location row: chunk now lives at newAddress instead of oldAddress.
 * @return true if a row was updated
 */
public boolean reassignLocation(String chunkId, String oldAddress, String newAddress) {
    try (Connection conn = newConnection();
         PreparedStatement pstmt = conn.prepareStatement(
             "UPDATE chunk_locations SET node_address = ? " +
             "WHERE chunk_id = ? AND node_address = ?")) {
        pstmt.setString(1, newAddress);
        pstmt.setString(2, chunkId);
        pstmt.setString(3, oldAddress);
        return pstmt.executeUpdate() > 0;
    } catch (SQLException e) {
        throw new RuntimeException("Failed to reassign chunk location", e);
    }
}
```

The job itself (`com.dfs.metadata.RepairJob`):

```java
package com.dfs.metadata;

import com.dfs.client.HttpUtil;

import java.util.*;

/**
 * Periodically copies chunks off DOWN nodes onto healthy ones.
 * Best-effort: no locks vs concurrent uploads, no hash re-checks — documented trade-off.
 */
public class RepairJob {

    private final Store store;
    private final List<String> allNodes;
    private final HealthMonitor monitor;
    private final Set<String> repairInProgress = ConcurrentHashMap.newKeySet();

    public RepairJob(Store store, List<String> allNodes, HealthMonitor monitor) {
        this.store = store;
        this.allNodes = allNodes;
        this.monitor = monitor;
    }

    public void runOnce() {
        for (String deadNode : allNodes.stream().filter(a -> !monitor.isAlive(a)).toList()) {
            for (String[] loc : store.locationsOnNode(deadNode)) {   // [chunkId, ...] rows
                String chunkId = loc[0];
                if (!repairInProgress.add(chunkId)) continue;      // already repairing

                try {
                    String source = pickLiveHolder(chunkId, deadNode);
                    String target = pickReplacement(chunkId, deadNode);
                    if (source == null || target == null) continue;  // can't help right now

                    byte[] data = HttpUtil.getBytes(
                        "http://" + source + "/get_chunk?chunk_id=" + chunkId);
                    HttpUtil.postBytes(
                        "http://" + target + "/store_chunk?chunk_id=" + chunkId, data);

                    if (store.reassignLocation(chunkId, deadNode, target)) {
                        System.out.println("[repair] " + chunkId + " "
                            + deadNode + " -> " + target);
                    }
                } catch (Exception e) {
                    System.err.println("[repair] failed for " + chunkId + ": " + e.getMessage());
                } finally {
                    repairInProgress.remove(chunkId);
                }
            }
        }
    }

    private String pickLiveHolder(String chunkId, String exclude) {
        return store.holdersOf(chunkId).stream()
                .filter(a -> !a.equals(exclude) && monitor.isAlive(a))
                .findFirst().orElse(null);
    }

    private String pickReplacement(String chunkId, String exclude) {
        Set<String> holders = new HashSet<>(store.holdersOf(chunkId));
        return allNodes.stream()
                .filter(a -> !a.equals(exclude) && monitor.isAlive(a) && !holders.contains(a))
                .findFirst().orElse(null);
    }
}
```

It uses two tiny `Store` helpers (same style as the others):

```java
/** All node addresses currently holding any copy of the chunk. */
public List<String> holdersOf(String chunkId) {
    try (Connection conn = newConnection();
         PreparedStatement pstmt = conn.prepareStatement(
             "SELECT node_address FROM chunk_locations WHERE chunk_id = ?")) {
        pstmt.setString(1, chunkId);
        ResultSet rs = pstmt.executeQuery();
        List<String> out = new ArrayList<>();
        while (rs.next()) out.add(rs.getString("node_address"));
        return out;
    } catch (SQLException e) {
        throw new RuntimeException("Failed to list holders", e);
    }
}

/** Rows [chunkId, node_address] for every location on the given node. */
public List<String[]> locationsOnNode(String nodeAddress) { /* same pattern, SELECT chunk_id FROM chunk_locations WHERE node_address = ? */ }
```

Wire it next to the monitor in metadata `Main`:

```java
RepairJob repair = new RepairJob(store, nodeAddresses, monitor);
ScheduledExecutorService repairScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "repair-job");
    t.setDaemon(true);
    return t;
});
repairScheduler.scheduleWithFixedDelay(repair::runOnce, 30, 30, TimeUnit.SECONDS);
```

Known limitations (state these in your README): races with concurrent deletes can leave
an orphan `.bin` on the target node; no end-to-end verification hash after copy; single
metadata server remains the SPOF.

## 6. Bugs Fixed From Earlier Drafts / Common Mistakes

| # | Mistake | Fix |
|---|---------|-----|
| 1 | Probing `/health` synchronously inside create_file → every upload stalls 3s when a node dies | Background poller; handler reads the map only |
| 2 | Non-daemon scheduler thread keeps JVM alive | Daemon thread factory + shutdown hook calling `stop()` |
| 3 | Plain `HashMap` liveness map mutated by poller while read by request threads | `ConcurrentHashMap` |
| 4 | Probe timeout ≥ poll interval → probes overlap, liveness always stale | connect 2s / request 3s vs 10s interval |
| 5 | Log spam per poll | Log UP/DOWN transitions only |
| 6 | All-nodes-down crash or empty file created | Explicit 503 `ErrorResponse` before touching the store |
| 7 | Optimistic-start omitted → everything "down" until first successful sweep | Seed map with TRUE |

## 7. Verify Build

Walkthrough is in `phase5-instruction.md` §7. Pass criteria:

1. Kill any node → `[health] node localhost:808x -> DOWN` within ~10s.
2. Upload during downtime → response JSON contains zero references to the dead address.
3. Download a file whose chunk primary sits on a dead node → succeeds; stderr shows
   fallback WARNs; output SHA-256 still matches original.
4. Restart the node → `-> UP` logged within ~10s; next upload may use it again.
5. Stop all four nodes → `POST /create_file` returns `503` + JSON error body.
6. (Stretch) With a chunk stranded on a dead node, wait for the repair cycle → log line
   `[repair] c_... localhost:808x -> localhost:808y`, then verify the new `.bin` exists
   on the target node and `get_file_metadata` reflects the move.
