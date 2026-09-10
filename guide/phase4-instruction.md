# Phase 4: Chunker + Client CLI (Upload/Download) - Java Version

## Objective
Tie metadata + storage together into an actual client that can upload, download, list, and delete files. After this phase the system is *usable*: `dfs upload big.zip` → `dfs download <id> out.zip` produces a byte-identical file.

## 1. Component Layout

```
src/main/java/com/dfs/
├── chunker/Chunker.java           # pure byte-splitting logic (no I/O deps beyond files)
├── client/
│   ├── HttpUtil.java              # thin wrapper over java.net.http.HttpClient
│   └── DfsClient.java             # upload/download/delete/ls orchestration
└── cmd/client/Main.java           # CLI entry point (replace Phase-1 stub)
```

## 2. Chunker (`com.dfs.chunker.Chunker`)

Two static methods — keep them free of any HTTP/metadata knowledge so they're trivially
unit-testable:

| Method | Behavior |
|---|---|
| `static List<byte[]> splitFile(Path path, int chunkSize)` | streams through the file with `InputStream.readNBytes(chunkSize)`; last chunk may be smaller; empty file → empty list |
| `static void joinChunks(List<byte[]> chunks, Path outputPath)` | writes each chunk in order to a new file via `OutputStream` |

Edge cases to handle deliberately (write tests for them):
- File smaller than one chunk → exactly 1 chunk.
- File size an exact multiple of chunkSize → no trailing empty chunk.
- **Zero-byte file** → decide: empty chunk list (download of it joins nothing) or refuse.
  Pick one behavior and document it; the naive `ceil` math breaks here.

## 3. HTTP Client Helper (`com.dfs.client.HttpUtil`)

Use `java.net.http.HttpClient` (Java 11+, stdlib) — far cleaner than `HttpURLConnection`:

- One shared static `HttpClient` with a **connect timeout (~3s)** — this becomes crucial
  in Phase 5 when nodes are dead.
- Methods: `postJson(url, jsonBody) -> String`, `getJson(url) -> String`,
  `postBytes(url, byte[])`, `getBytes(url) -> byte[]`, `delete(url) -> String`.
- Convention: non-2xx status → throw `IOException("HTTP <status>: <body>")`. Callers can
  then just try/catch. Read `response.body()` regardless of status before throwing so the
  connection is properly drained.

## 4. Client Logic (`com.dfs.client.DfsClient`)

Constructor takes the metadata URL (default `http://localhost:9090`). Uses your existing
`com.dfs.types.*` records + Jackson for both directions (records serialize/deserialize
with Jackson out of the box).

### Upload flow
1. Compute `size = Files.size(path)`, `chunkCount = ceil(size / chunkSize)`
   using integer math `(size + chunkSize - 1) / chunkSize`.
2. `POST /create_file` with `{filename, size, chunkCount}` → `CreateFileResponse`
   (fileId + per-chunk node assignments).
3. Split the local file with `Chunker`.
4. Sanity-check: `localChunks.size() == response.chunks().size()` — abort otherwise.
5. For each chunk: `POST /store_chunk?chunk_id=...` to its **primary** node, then to each
   **replica**. Print progress (`chunk i/N -> nodeX`).
6. Return/print the fileId prominently — **the UUID is the only handle to the file later**.

### Download flow
1. `GET /get_file_metadata?file_id=...` → chunks with locations.
2. Iterate chunks **ordered by index**; fetch each from primary with fallback to replicas:
   wrap candidates `[primary, replicas...]` in order, first success wins, only fail after
   all are exhausted. (This is your first taste of failover; Phase 5 makes it systematic.)
3. `joinChunks(...)` to the output path.

### Delete flow — ORDER MATTERS
The delete endpoint returns only chunk IDs, and once metadata rows are gone you can never
learn which nodes held the chunks. So:

1. **First** `GET /get_file_metadata?file_id=...` → collect every node holding any chunk
   (primaries + replicas).
2. Then `DELETE /delete_file?file_id=...` → `DeleteFileResponse` with `chunksToDelete`.
3. For each chunk ID, `DELETE /delete_chunk?chunk_id=...` on **every** node collected in
   step 1. Treat individual failures as warnings (a dead node gets cleaned by Phase-5
   re-replication logic or manual sweep).

### ls flow
`GET /list_files` → print a table: truncated fileId, filename, human-readable size,
chunk count if you want.

## 5. CLI (`com.dfs.cmd.client.Main`)

Plain `args[]` parsing — four commands don't justify a library:

```
dfs upload <local-path>
dfs download <file-id> <output-path>
dfs delete <file-id>
dfs ls
```

- Metadata URL override via env var (e.g. `METADATA_URL`), default `http://localhost:9090`.
- Unknown command / missing args → print usage, exit code 1.
- Catch exceptions at top level → `ERROR: <message>` on stderr, nonzero exit.

## 6. Pitfalls To Avoid

1. **Delete-order bug** — deleting metadata first loses chunk locations forever; orphaned
   `.bin` files accumulate on storage nodes. Fetch locations BEFORE calling delete_file.
2. **ceil() math** — `(int) Math.ceil((double) size / chunkSize)` invites float rounding;
   prefer `(size + chunkSize - 1) / chunkSize`. And zero-byte files give chunkCount 0 —
   decide explicitly what happens.
3. **Assuming counts match** — always assert local chunk count equals server-assigned
   count before writing bytes.
4. **No timeouts on HttpClient** — a dead storage node hangs the client forever on TCP
   connect (can be minutes). Always set connect timeout; Phase 5 adds request timeout too.
5. **Checksum trust gap** — "it worked" isn't verification. Compare SHA-256 of original vs
   downloaded file (wrap in a helper; print both hex digests). This catches chunker bugs,
   partial writes, and wrong-chunk-order mistakes instantly.
6. **Primary write failure policy** — if the primary store fails, abort the whole upload
   (you'd have an under-written file). Replica failures only warn. Keep this asymmetry —
   Phase 5 builds on it.
7. **Sorting chunks** — SQL orders by index, but don't rely on JSON array order surviving;
   sort by `index()` client-side before joining.

## 7. Checklist for Phase 4

- [ ] Chunker splits/joins byte-for-byte correctly (unit test with a >1MB random file)
- [ ] `dfs upload` stores primary + replica copies (verify `.bin` files exist on the
      right nodes' directories per metadata)
- [ ] `dfs download` reconstructs an identical file (SHA-256 compare)
- [ ] `dfs delete` removes metadata AND purges `.bin` files from all holders
- [ ] `dfs ls` lists uploaded files
- [ ] End-to-end script: upload → download → checksum diff → delete → confirm clean

### Manual end-to-end script

```bash
# prerequisites: metadata server (9090) + 4 storage nodes running

# make a test file > several chunks (64KB each): ~300KB = 5 chunks
head -c 300000 /dev/urandom > test.bin     # Git Bash; PowerShell alternative below
certutil -encodehex ...                    # or use PowerShell: -join (1..300000 | % {[char](Get-Random -Max 256)}) > test.bin

mvn compile exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=upload test.bin"
# => uploaded file <FILE_ID>

mvn compile exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" \
  "-Dexec.args=download <FILE_ID> restored.bin"

sha256sum test.bin restored.bin            # Git Bash; PowerShell: Get-FileHash
# digests must match

mvn compile exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" "-Dexec.args=ls"
mvn compile exec:java "-Dexec.mainClass=com.dfs.cmd.client.Main" \
  "-Dexec.args=delete <FILE_ID>"

# verify cleanup: no .bin files left on any node for that file
dir .\data\node1\chunks\
curl http://localhost:9090/list_files       # empty
```

## 8. Theory: Why Clients Orchestrate

Notice who does the work: the metadata server plans (assigns chunks→nodes), but the
*client* pushes bytes to storage nodes directly. This is the GFS/HDFS trick — metadata
never touches data, so it stays a tiny bottleneck-free coordinator while data flows
client↔datanode at full bandwidth. The cost is that clients need failover logic (they
talk to many fallible machines), which is why Phase 5 exists.
