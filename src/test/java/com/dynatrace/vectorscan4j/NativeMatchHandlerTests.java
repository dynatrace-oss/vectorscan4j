/*
 Copyright 2026 JKU/Dynatrace Co-Innovation Lab

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package com.dynatrace.vectorscan4j;

import static com.dynatrace.vectorscan4j.constants.ExecutionMode.BLOCK_MODE;
import static com.dynatrace.vectorscan4j.constants.ExecutionMode.STREAM_MODE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dynatrace.vectorscan4j.constants.PatternFlag;
import java.io.IOException;
import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NativeMatchHandler} and the native-callback scan overloads on
 * {@link BlockScanner} / {@link StreamScanner}. Tests that require the test-only native callback
 * library are auto-skipped when it has not been built (via {@code buildTestNativeCallback}).
 *
 * <p>The test library exposes two handlers: {@code counter}, which increments a counter on every match,
 * and {@code stateless}, which returns without doing anything.
 */
public class NativeMatchHandlerTests {
    private static final Path NATIVE_DIR = Path.of(System.getProperty("user.dir"), "src/test/resources/native");

    static Path libPath;

    /**
     * Compiles every {@code .cpp} file in {@value #NATIVE_DIR} into a {@code .so} of the same name,
     * skipping any file whose {@code .so} is already newer than its source.
     * It is compiled here instead of gradle, so that it does not get compiled every time you run a unit test.
     */
    @BeforeAll
    static void buildNativeCallbacks() throws IOException, InterruptedException {
        boolean hasCxx;
        try {
            hasCxx = new ProcessBuilder("c++", "--version")
                            .redirectErrorStream(true)
                            .start()
                            .waitFor()
                    == 0;
        } catch (IOException e) {
            hasCxx = false;
        }
        assumeTrue(hasCxx, "c++ not available; native-callback tests will be skipped.");

        try (Stream<Path> files = Files.list(NATIVE_DIR)) {
            for (Path cpp : files.filter(p -> p.toString().endsWith(".cpp")).toList()) {
                Path so = NATIVE_DIR.resolve(cpp.getFileName().toString().replace(".cpp", ".so"));
                if (isUpToDate(so, cpp)) {
                    continue;
                }
                Process proc = new ProcessBuilder(
                                "c++", "-std=c++17", "-O3", "-fPIC", "-shared", cpp.toString(), "-o", so.toString())
                        .inheritIO()
                        .start();
                assumeTrue(
                        proc.waitFor() == 0,
                        "Failed to compile " + cpp.getFileName() + "; native-callback tests will be skipped.");
            }
        }
        libPath = NATIVE_DIR.resolve("native_count_match.so");
    }

    private static boolean isUpToDate(Path so, Path cpp) throws IOException {
        if (!Files.isRegularFile(so)) {
            return false;
        }
        return Files.getLastModifiedTime(so).compareTo(Files.getLastModifiedTime(cpp)) > 0;
    }

    // ---------- symbol binding (no native lib required) ----------

    @Test
    void scanRejectsNullHandler() {
        List<Expression> exprs = List.of(new Expression("foo"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom("foo");
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(data, (int) data.byteSize(), null));
        }
    }

    // ---------- native-callback path (requires the test shared lib) ----------

    @Test
    void allOverloadsRejectNullNativeHandler() {
        try (Database db = new Database(List.of(new Expression("foo")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                Arena arena = Arena.ofConfined()) {
            byte[] foo = "foo".getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> scanner.scan("foo", (NativeMatchHandler) null));
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(foo, (NativeMatchHandler) null));
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(foo, 0, 3, (NativeMatchHandler) null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scanner.scan(ByteBuffer.wrap(foo), (NativeMatchHandler) null));
            ByteBuffer direct = ByteBuffer.allocateDirect(3);
            direct.put(foo).flip();
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(direct, (NativeMatchHandler) null));
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(arena.allocateFrom("foo"), 3, null));
        }
    }

    @Test
    void nativeCountMatches() {
        Path tempPath = Path.of(System.getProperty("user.dir"), "src/test/resources/native/native_count_match.so");
        NativeMatchHandler handler = new NativeMatchHandler(tempPath);
        assertEquals(0, handler.context().get(ValueLayout.JAVA_INT, 0));
        List<Expression> exprs = List.of(new Expression("pattern1"));
        Database db = new Database(exprs, BLOCK_MODE);
        BlockScanner scanner = new BlockScanner(db);
        scanner.scan("pattern1 pattern1 pattern1", handler);
        assertEquals(3, handler.context().get(ValueLayout.JAVA_INT, 0));
        handler.context().set(ValueLayout.JAVA_INT, 0, 0);
        scanner.scan("pattern1 pattern1 pattern1", handler);
        assertEquals(3, handler.context().get(ValueLayout.JAVA_INT, 0));
        handler.close();
    }

    @Test
    void contextThrowsAfterClose() {
        Path tempPath = Path.of(System.getProperty("user.dir"), "src/test/resources/native/native_count_match.so");
        NativeMatchHandler handler = new NativeMatchHandler(tempPath);
        List<Expression> exprs = List.of(new Expression("pattern1"));
        Database db = new Database(exprs, BLOCK_MODE);
        BlockScanner scanner = new BlockScanner(db);
        scanner.scan("Here is pattern1. You should get 1 match.", handler);

        assertDoesNotThrow(() -> handler.context().get(ValueLayout.JAVA_INT, 0));
        assertEquals(1, handler.context().get(ValueLayout.JAVA_INT, 0));
        handler.close(); // frees the native context and unloads the library

        // Reading context() after close() must fail fast rather than silently return stale or
        // freed memory: close() frees the native object, so this is a use-after-free otherwise.
        assertThrows(IllegalStateException.class, () -> handler.context().get(ValueLayout.JAVA_INT, 0));
    }

    @Test
    void nativeBatchHandler() {
        List<Expression> exprs = List.of(new Expression("pattern1", EnumSet.of(PatternFlag.SOM_LEFTMOST)));
        String input = "pattern1 pattern1 pattern1 pattern1 pattern1";

        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            // baseline: the same scan through a plain per-match Java callback
            List<List<Long>> expected = new ArrayList<>();
            scanner.scan(input, (id, from, to) -> {
                expected.add(List.of((long) id, from, to));
                return true;
            });
            assertEquals(5, expected.size());

            List<List<Long>> actual = new ArrayList<>();
            List<Integer> batchSizes = new ArrayList<>();
            try (BufferedMatchHandler handler = new BufferedMatchHandler(2, (buf, n) -> {
                batchSizes.add(n);
                actual.addAll(decode(buf, n));
                return true;
            })) {
                assertTrue(scanner.scan(input, handler), "no batch asked to stop");
            }

            assertEquals(List.of(2, 2, 1), batchSizes, "two full batches mid-scan, then the remainder after it");
            assertEquals(expected, actual, "batched delivery must see the same matches, in the same order");
        }
    }

    @Test
    void nativeBatchHandlerStopsEarly() {
        try (Database db = new Database(List.of(new Expression("pattern1")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            List<Integer> batchSizes = new ArrayList<>();
            try (BufferedMatchHandler handler = new BufferedMatchHandler(2, (buf, n) -> {
                batchSizes.add(n);
                return false;
            })) {
                assertFalse(scanner.scan("pattern1 pattern1 pattern1 pattern1 pattern1", handler));
            }
            // returning false from the first batch terminates the scan; nothing is left over to flush
            assertEquals(List.of(2), batchSizes);
        }
    }

    @Test
    void nativeBatchHandlerIsReusableAcrossScans() {
        try (Database db = new Database(List.of(new Expression("pattern1")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            List<Integer> batchSizes = new ArrayList<>();
            try (BufferedMatchHandler handler = new BufferedMatchHandler(2, (buf, n) -> {
                batchSizes.add(n);
                return true;
            })) {
                scanner.scan("pattern1 pattern1 pattern1", handler);
                scanner.scan("pattern1 pattern1 pattern1", handler);
            }
            // each scan flushes its own remainder; nothing carries over into the next one
            assertEquals(List.of(2, 1, 2, 1), batchSizes);
        }
    }

    @Test
    void nativeBatchHandlerFlushesRemainderOnStreamScanner() {
        try (Database db = new Database(List.of(new Expression("pattern1")), STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            List<Integer> batchSizes = new ArrayList<>();
            try (BufferedMatchHandler handler = new BufferedMatchHandler(2, (buf, n) -> {
                batchSizes.add(n);
                return true;
            })) {
                scanner.scan("pattern1 pattern1 pattern1", handler);
            }
            // without postScan() the trailing partial batch of 1 would never be delivered
            assertEquals(List.of(2, 1), batchSizes);
        }
    }

    @Test
    void nativeBatchHandlerRejectsNonPositiveBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> new BufferedMatchHandler(0, (buf, n) -> true));
    }

    /** Decodes the 12-byte (id, from, to) entries a batch handler receives. */
    private static List<List<Long>> decode(ByteBuffer buf, int n) {
        List<List<Long>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int b = i * 12;
            out.add(List.of((long) buf.getInt(b), (long) buf.getInt(b + 4), (long) buf.getInt(b + 8)));
        }
        return out;
    }
}
