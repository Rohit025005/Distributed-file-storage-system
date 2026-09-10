package com.dfs.chunker;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Chunker {

    public static List<byte[]> splitFile(Path path, int chunkSize) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        try (InputStream is = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            while (true) {
                byte[] chunk = is.readNBytes(chunkSize);
                if (chunk.length == 0) break;
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    public static void joinChunks(List<byte[]> chunks, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            outputPath.getParent().toFile().mkdirs();
        }
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outputPath.toFile()))) {
            for (byte[] chunk : chunks) {
                os.write(chunk);
            }
        }
    }
}