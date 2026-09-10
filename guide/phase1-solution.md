# Phase 1: Solution - Project Scaffolding & Shared Types (Java)

## 1. Create Directory Structure

```bash
cd E:\projects\final DFS\DFS
mkdir -p src/main/java/com/dfs/types src/main/java/com/dfs/config
mkdir -p src/main/java/com/dfs/cmd/metadata src/main/java/com/dfs/cmd/storage src/main/java/com/dfs/cmd/client
mkdir -p src/main/java/com/dfs/api src/main/java/com/dfs/storage src/main/java/com/dfs/metadata
mkdir -p src/main/java/com/dfs/chunker src/main/java/com/dfs/client
mkdir -p config
```

## 2. Create Maven `pom.xml`

Create `E:\projects\final DFS\DFS\pom.xml` with the content from the instruction file.

## 3. Create Shared Types (`src/main/java/com/dfs/types/`)

Create `E:\projects\final DFS\DFS\src\main\java\com\dfs\types\Types.java`:

```java
package com.dfs.types;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Represents a storage node in the cluster.
 */
public record Node(
    String address,       // e.g., "localhost:8081"
    String id             // e.g., "node1"
) {}

/**
 * Represents a chunk of a file.
 */
public record Chunk(
    String id,            // e.g., "c_abc123def456"
    int index,            // Position in file (0, 1, 2...)
    int size,             // Actual size (last chunk may be smaller)
    String primaryNode,   // Primary storage node
    List<String> replicaNodes  // Replica storage nodes
) {}

/**
 * Tracks where a chunk is stored.
 */
public record ChunkLocation(
    String chunkId,       // Chunk identifier
    String nodeAddress,   // Node address
    boolean isPrimary     // Is this the primary node
) {}

/**
 * Request to create a new file metadata entry.
 */
public record CreateFileRequest(
    String filename,      // Filename
    long size,            // File size in bytes
    int chunkCount        // Number of chunks to split file into
) {}

/**
 * Response containing allocated chunk IDs and node assignments.
 */
public record CreateFileResponse(
    String fileId,        // UUID-generated file identifier
    List<Chunk> chunks    // Chunks with node assignments
) {}

/**
 * Response returning file metadata with chunk locations.
 */
public record GetFileMetadataResponse(
    String fileId,        // File identifier
    String filename,      // Filename
    long size,            // File size in bytes
    int chunkSize,        // Default: 64KB = 65536
    List<Chunk> chunks    // Chunks with node assignments
) {}

/**
 * Response confirming deletion and returning chunk IDs to delete.
 */
public record DeleteFileResponse(
    boolean deleted,      // Whether deletion was successful
    List<String> chunksToDelete  // Chunk IDs to delete on storage nodes
) {}

/**
 * Response confirming chunk storage.
 */
public record StoreChunkResponse(
    boolean stored,       // Whether chunk was stored successfully
    int bytes             // Size of stored data in bytes
) {}

/**
 * Health response containing node health information.
 */
public record HealthResponse(
    String status,        // e.g., "healthy", "unhealthy"
    double diskFreeGB,    // Free disk space in GB
    int chunksStored      // Number of chunks stored on this node
) {}

/**
 * Error response used for API errors.
 */
public record ErrorResponse(
    String error,         // Error message
    String details        // Optional detailed error information
) {}

/**
 * Constants shared across the system.
 */
public final class Constants {
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024; // 64KB
    public static final int REPLICATION_FACTOR = 2;          // Number of copies per chunk
    
    private Constants() { // Prevent instantiation
    }
}
```

## 4. Create Configuration File (`config/nodes.yaml`)

Create `E:\projects\final DFS\DFS\config\nodes.yaml`:

```yaml
# Storage node configuration
nodes:
  - id: node1
    address: localhost:8081
  - id: node2
    address: localhost:8082
  - id: node3
    address: localhost:8083
  - id: node4
    address: localhost:8084

# Replication settings
replication:
  factor: 2
  chunkSize: 65536  # 64KB
```

## 5. Create Configuration Loader (`src/main/java/com/dfs/config/Config.java`)

Create `E:\projects\final DFS\DFS\src\main\java\com\dfs\config\Config.java`:

```java
package com.dfs.config;

import com.dfs.types.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class Config {
    private List<Node> nodes;
    private int replicationFactor;
    private int chunkSize;

    public Config() {
        // Default values
        this.replicationFactor = Constants.REPLICATION_FACTOR;
        this.chunkSize = Constants.DEFAULT_CHUNK_SIZE;
    }

    public static Config loadFromFile(String path) throws IOException {
        Config config = new Config();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        File file = new File(path);
        
        if (file.exists()) {
            Config yamlConfig = yamlMapper.readValue(file, Config.class);
            config.nodes = yamlConfig.getNodes();
            config.replicationFactor = yamlConfig.getReplicationFactor();
            config.chunkSize = yamlConfig.getChunkSize();
        }
        
        // Set defaults if not configured
        if (config.nodes == null || config.nodes.isEmpty()) {
            config.nodes = List.of(
                new Node("localhost:8081", "node1"),
                new Node("localhost:8082", "node2"),
                new Node("localhost:8083", "node3"),
                new Node("localhost:8084", "node4")
            );
        }
        
        if (config.replicationFactor <= 0) {
            config.replicationFactor = Constants.REPLICATION_FACTOR;
        }
        
        if (config.chunkSize <= 0) {
            config.chunkSize = Constants.DEFAULT_CHUNK_SIZE;
        }
        
        return config;
    }

    // Getters
    public List<Node> getNodes() { return nodes; }
    public int getReplicationFactor() { return replicationFactor; }
    public int getChunkSize() { return chunkSize; }
    
    // Method to get all node addresses
    public List<String> getNodeAddresses() {
        return nodes.stream()
                    .map(Node::address)
                    .collect(Collectors.toList());
    }
}
```

**Note:** Add Jackson dependency to `pom.xml`:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <version>2.15.2</version>
</dependency>
```

## 6. Create Minimal Entry Points

### `src/main/java/com/dfs/cmd/metadata/Main.java`

Create `E:\projects\final DFS\DFS\src\main\java\com\dfs\cmd\metadata\Main.java`:

```java
package com.dfs.cmd.metadata;

public class Main {
    public static void main(String[] args) {
        System.out.println("Metadata server - to be implemented");
    }
}
```

### `src/main/java/com/dfs/cmd/storage/Main.java`

Create `E:\projects\final DFS\DFS\src\main\java\com\dfs\cmd\storage\Main.java`:

```java
package com.dfs.cmd.storage;

public class Main {
    public static void main(String[] args) {
        System.out.println("Storage node - to be implemented");
    }
}
```

### `src/main/java/com/dfs/cmd/client/Main.java`

Create `E:\projects\final DFS\DFS\src\main\java\com\dfs\cmd\client\Main.java`:

```java
package com.dfs.cmd.client;

public class Main {
    public static void main(String[] args) {
        System.out.println("CLI client - to be implemented");
    }
}
```

## 7. Verify Build

```bash
cd E:\projects\final DFS\DFS
mvn compile
```

Should compile without errors.

## 8. Testing (Optional)

### `src/test/java/com/dfs/types/TypesTest.java`

Create `E:\projects\final DFS\DFS\src\test\java\com\dfs\types\TypesTest.java`:

```java
package com.dfs.types;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypesTest {
    
    @Test
    void testDefaultChunkSize() {
        assertEquals(65536, Constants.DEFAULT_CHUNK_SIZE);
    }
    
    @Test
    void testReplicationFactor() {
        assertEquals(2, Constants.REPLICATION_FACTOR);
    }
    
    @Test
    void testNodeRecord() {
        Node node = new Node("localhost:8081", "node1");
        assertEquals("localhost:8081", node.address());
        assertEquals("node1", node.id());
    }
    
    @Test
    void testChunkRecord() {
        Chunk chunk = new Chunk("c_abc123", 0, 65536, "localhost:8081", List.of("localhost:8082"));
        assertEquals("c_abc123", chunk.id());
        assertEquals(0, chunk.index());
        assertEquals(65536, chunk.size());
        assertEquals("localhost:8081", chunk.primaryNode());
        assertEquals(1, chunk.replicaNodes().size());
    }
}
```

Run tests:
```bash
mvn test -Dtest=TypesTest
```

## 9. Project Verification

Run the minimal main classes to verify they work:

```bash
cd E:\projects\final DFS\DFS
mvn exec:java -Dexec.mainClass="com.dfs.cmd.metadata.Main"
mvn exec:java -Dexec.mainClass="com.dfs.cmd.storage.Main"
mvn exec:java -Dexec.mainClass="com.dfs.cmd.client.Main"
```

Expected output for each:
```
Metadata server - to be implemented
Storage node - to be implemented
CLI client - to be implemented
```

## 10. Key Learning Points (SOLUTION Summary)

1. **Package Layout**: `src/main/java/com/dfs/**` — package/directory names follow component naming (`cmd`, `types`, `config`, ...), matching how a Go project splits `cmd/`, `internal/`, and `pkg/`
2. **Java Records**: Use Java 16+ records for immutable data carriers (Node, Chunk, etc.)
3. **Separation of Concerns**: Types are shared, config is separate from business logic
4. **YAML Configuration**: Common pattern for external configuration using SnakeYAML/Jackson
5. **Constants**: Magic numbers like chunk size should be constants
6. **Maven/Gradle**: Build automation tools
7. **Records are immutable and concise**: Perfect for DTOs and data carriers
8. **Component-based structure**: cmd/ directories for each binary entry point