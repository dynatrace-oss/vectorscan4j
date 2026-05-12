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
import static com.dynatrace.vectorscan4j.constants.Flags.CASELESS;
import static com.dynatrace.vectorscan4j.constants.Flags.SOM_LEFTMOST;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

import com.dynatrace.vectorscan4j.constants.ExecutionMode;
import com.dynatrace.vectorscan4j.constants.Flags;
import com.dynatrace.vectorscan4j.utils.LoadGenerator;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

public class BlockScanner2Tests {
    static final List<Expression> expressions = Arrays.asList(
            new Expression("pattern1", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Expression("pattern2", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Expression("pattern3", EnumSet.of(SOM_LEFTMOST, CASELESS)));
    static int nMatches = 0;

    final OnMatchEventHandler doNothing = (_, _, _, _) -> true;

    @Test
    void databaseWrongMode() {
        Database db = new Database(expressions, ExecutionMode.STREAM_MODE);
        assertThrows(IllegalArgumentException.class, () -> new BlockScanner2(db));
        db.close();
    }

    @Test
    void defaultScan() {
        try (Database db = new Database(expressions, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            String input = "I am searching for pattern1, has anyone seen pattern1?";
            List<Integer> matchedIds = new ArrayList<>();

            scanner.scan(input, (id, from, to, _) -> {
                IO.println(String.format("Match pattern with id %d, from %d to %d", id, from, to));
                matchedIds.add(id);
                return true;
            });

            // "pattern1" (id=0) appears twice in the input, no other pattern matches
            assertEquals(List.of(0, 0), matchedIds);
        }
    }

    @Test
    void earlyStopScan() {
        // if an OnMatchEventHandler returns false, the execution of the engine stops early.
        OnMatchEventHandler incrementAndStop = (_, _, _, _) -> {
            nMatches += 1;
            return false;
        };
        List<Expression> exprs = List.of(new Expression("pat1"), new Expression("pat2"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            nMatches = 0;
            scanner.scan("Here is pat1 and here is pat2!", incrementAndStop);
            assertEquals(1, nMatches);
            // With a BlockScanner2, this stopping early does not influence the next scan, so the second
            // scan
            // would again match pat1 and then stop.
            scanner.scan("Here is pat1 and here is pat2!", incrementAndStop);
            assertEquals(2, nMatches);
        }
    }

    @Test
    void invalidLength() {
        try (Database db = new Database(expressions, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            byte[] data = "Hello".getBytes();
            assertDoesNotThrow(() -> scanner.scan(data, 0, 5, doNothing));
            assertDoesNotThrow(() -> scanner.scan(data, 1, 4, doNothing));
            assertThrows(IndexOutOfBoundsException.class, () -> scanner.scan(data, -1, 2, doNothing));
            assertThrows(IndexOutOfBoundsException.class, () -> scanner.scan(data, 0, 6, doNothing));
            assertThrows(IndexOutOfBoundsException.class, () -> scanner.scan(data, 1, 5, doNothing));
        }
    }

    @Test
    void noMatch() {
        try (Database db = new Database(expressions, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            List<Integer> matchedIds = new ArrayList<>();
            scanner.scan("this input contains nothing of interest", (id, _, _, _) -> {
                matchedIds.add(id);
                return true;
            });
            assertTrue(matchedIds.isEmpty());
        }
    }

    @Test
    void emptyInput() {
        try (Database db = new Database(expressions, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            List<Integer> matchedIds = new ArrayList<>();
            OnMatchEventHandler collect = (id, _, _, _) -> {
                matchedIds.add(id);
                return true;
            };

            assertDoesNotThrow(() -> scanner.scan("", collect));
            assertDoesNotThrow(() -> scanner.scan(new byte[0], collect));
            assertDoesNotThrow(() -> scanner.scan(ByteBuffer.wrap(new byte[0]), collect));

            assertTrue(matchedIds.isEmpty());
        }
    }

    @Test
    void tooBigInput() {
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db);
                Arena arena = Arena.ofConfined()) {
            MemorySegment tiny = arena.allocate(1);
            MemorySegment oversized = tiny.reinterpret((long) Integer.MAX_VALUE + 1L);

            assertThrows(IllegalArgumentException.class, () -> scanner.scan(oversized, doNothing));
        }
    }

    @Test
    void directByteBuffer() {
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            byte[] bytes = "found pattern1 here".getBytes(StandardCharsets.UTF_8);
            ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
            direct.put(bytes);
            direct.flip(); // position=0, limit=bytes.length

            List<Integer> matchedIds = new ArrayList<>();
            scanner.scan(direct, (id, _, _, _) -> {
                matchedIds.add(id);
                return true;
            });
            assertEquals(List.of(0), matchedIds);
        }
    }

    @Test
    void byteBufferRangeRespected() {
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            ByteBuffer buf = ByteBuffer.wrap("pattern1__pattern1__pattern1".getBytes(StandardCharsets.UTF_8));
            List<Integer> matchedIds = new ArrayList<>();
            OnMatchEventHandler collect = (id, _, _, _) -> {
                matchedIds.add(id);
                return true;
            };

            // Full range contains three occurrences.
            scanner.scan(buf, collect);
            assertEquals(List.of(0, 0, 0), matchedIds);

            // Restrict view to only the middle occurrence: bytes[9..18).
            matchedIds.clear();
            buf.position(9);
            buf.limit(18);
            scanner.scan(buf, collect);
            assertEquals(List.of(0), matchedIds);
        }
    }

    @Test
    void scanByteArray() {
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            byte[] data = "found pattern1 twice: pattern1".getBytes(StandardCharsets.UTF_8);

            List<Integer> matchedIds = new ArrayList<>();
            scanner.scan(data, (id, _, _, _) -> {
                matchedIds.add(id);
                return true;
            });
            assertEquals(List.of(0, 0), matchedIds);
        }
    }

    @Test
    void bufferResizing() {
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            // large input triggers internal buffer reallocation
            byte[] bigData = ("pattern1" + "x".repeat(1024 * 1024 * 10)).getBytes(StandardCharsets.UTF_8);
            List<Integer> bigMatches = new ArrayList<>();
            scanner.scan(bigData, (id, _, _, _) -> {
                bigMatches.add(id);
                return true;
            });
            assertEquals(List.of(0), bigMatches);

            // scanner must still produce correct results after the buffer was resized
            List<Integer> smallMatches = new ArrayList<>();
            scanner.scan("pattern1", (id, _, _, _) -> {
                smallMatches.add(id);
                return true;
            });
            assertEquals(List.of(0), smallMatches);
        }
    }

    @Test
    void multiplePatternsSameInput() {
        List<Expression> exprs = List.of(new Expression("foo"), new Expression("bar"), new Expression("baz"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            List<Integer> matchedIds = new ArrayList<>();
            scanner.scan("foo bar baz", (id, _, _, _) -> {
                matchedIds.add(id);
                return true;
            });

            assertEquals(3, matchedIds.size());
            assertTrue(matchedIds.containsAll(List.of(0, 1, 2)));
        }
    }

    @Test
    void countLiteralPatternOccurrences() {
        List<Expression> exprs = List.of(
                new Expression("foo"), new Expression("bar", EnumSet.of(Flags.CASELESS)), new Expression("qux"));

        String input = "foo bar foo baz Bar foo";
        int[] countsById = new int[exprs.size()];

        try (Database database = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(database)) {
            scanner.scan(input, (id, _, _, _) -> {
                countsById[id]++;
                return true;
            });
        }
        for (int i = 0; i < exprs.size(); i++) {
            IO.println(String.format("%s -> matched %d times.", exprs.get(i).pattern(), countsById[i]));
        }
        // foo -> matched 3 times.
        // bar -> matched 2 times.
        // qux -> matched 0 times.
    }

    @Test
    void multipleCores() throws Exception {
        // Generate random data for testing (10MB)
        char[] randomData = LoadGenerator.randomCharArr(1024 * 1024 * 10);
        byte[] fileBytes = new String(randomData).getBytes(StandardCharsets.UTF_8);

        List<Expression> exprs = List.of(new Expression("a"), new Expression("b"));

        // Reference: scan each section sequentially to get the expected match count
        int nCores = 4;
        int sectionSize = fileBytes.length / nCores;
        int expectedMatches = 0;
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            for (int i = 0; i < nCores; i++) {
                final int start = i * sectionSize;
                final int len = (i == nCores - 1) ? fileBytes.length - start : sectionSize;
                int[] count = {0};
                scanner.scan(fileBytes, start, len, (_, _, _, _) -> {
                    count[0]++;
                    return true;
                });
                expectedMatches += count[0];
            }
        }

        // Parallel: one BlockScanner2 per thread, sharing a single compiled Database
        Database db = new Database(exprs, BLOCK_MODE);
        List<Integer> parallelMatches = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(nCores);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < nCores; i++) {
            final int start = i * sectionSize;
            final int len = (i == nCores - 1) ? fileBytes.length - start : sectionSize;
            futures.add(pool.submit(() -> {
                try (BlockScanner2 scanner = new BlockScanner2(db)) {
                    scanner.scan(fileBytes, start, len, (id, _, _, _) -> {
                        parallelMatches.add(id);
                        return true;
                    });
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        db.close();
        assertEquals(expectedMatches, parallelMatches.size());
    }

    @Test
    void scanAfterClosingDatabase() {
        Database db = new Database(expressions, BLOCK_MODE);
        BlockScanner2 scanner = new BlockScanner2(db);
        db.close();
        assertThrows(IllegalStateException.class, () -> scanner.scan("Test", doNothing));
    }

    @Test
    void notClosingStillFreesMemory() {
        Database db = new Database(expressions, BLOCK_MODE);
        BlockScanner2 scanner = new BlockScanner2(db);
        scanner = null;
        db = null;

        // Ask the JVM to collect and run pending cleanup actions.
        for (int i = 0; i < 10; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                IO.println("Thread interrupted.");
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Main method finished");
    }

    @Test
    void scanAfterClosingScanner() {
        try (Database db = new Database(expressions, BLOCK_MODE)) {
            BlockScanner2 scanner = new BlockScanner2(db);
            scanner.close();
            // The underlying Arena is closed; any subsequent scan must throw
            assertThrows(IllegalStateException.class, () -> scanner.scan("pattern1", doNothing));
        }
    }

    @Test
    void closeBlockScanner2IsIdempotent() {
        try (Database db = new Database(expressions, BLOCK_MODE)) {
            BlockScanner2 scanner = new BlockScanner2(db);
            assertDoesNotThrow(scanner::close);
            assertDoesNotThrow(scanner::close);
        }
    }

    @Test
    void invalidScan() {
        try (Database db = new Database(expressions, BLOCK_MODE)) {
            BlockScanner2 scanner = new BlockScanner2(db);
            scanner.close();
            // The underlying Arena is closed; any subsequent scan must throw
            assertThrows(Exception.class, () -> scanner.scan("pattern1", doNothing));
        }
    }

    @Test
    void scanClosedMemorySegment() {
        try (Database db = new Database(expressions, BLOCK_MODE)) {
            BlockScanner2 scanner = new BlockScanner2(db);
            Arena arena = Arena.ofConfined();
            MemorySegment data = arena.allocateFrom("Input String");
            arena.close();
            assertThrows(IllegalStateException.class, () -> scanner.scan(data, doNothing));
        }
    }

    @Test
    void createScannerFromInvalidDb() {
        try (Database db = new Database(expressions, BLOCK_MODE)) {
            // flip the first byte in the native database, to make the encoded DB invalid
            byte b0 = db.dbNative.get(JAVA_BYTE, 0);
            db.dbNative.set(JAVA_BYTE, 0, (byte) (b0 ^ 0xFF));
            assertThrows(VectorscanException.class, () -> new BlockScanner2(db));

            // flip the first byte back, to make the encoded DB valid again (so it can close properly)
            db.dbNative.set(JAVA_BYTE, 0, b0);
        }
    }

    @Test
    void scannerOperationsWithInvalidDb() {
        try (Database db = new Database(expressions, BLOCK_MODE);
                BlockScanner2 scanner = new BlockScanner2(db)) {
            // flip the first byte in the native database, to make the encoded DB invalid
            byte b0 = db.dbNative.get(JAVA_BYTE, 0);
            db.dbNative.set(JAVA_BYTE, 0, (byte) (b0 ^ 0xFF));
            assertThrows(VectorscanException.class, () -> scanner.scan("Hello", doNothing));

            // flip the first byte back, to make the encoded DB valid again (so it can close properly)
            db.dbNative.set(JAVA_BYTE, 0, b0);
        }
    }
}
