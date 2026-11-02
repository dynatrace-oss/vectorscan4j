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
import static com.dynatrace.vectorscan4j.constants.PatternFlag.CASELESS;
import static com.dynatrace.vectorscan4j.constants.PatternFlag.SOM_LEFTMOST;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

import com.dynatrace.vectorscan4j.constants.ExecutionMode;
import com.dynatrace.vectorscan4j.constants.PatternFlag;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class DatabaseTests {
    static final List<Pattern> PATTERNS = Arrays.asList(
            new Pattern("pattern1", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Pattern("pattern2", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Pattern("pattern3", EnumSet.of(SOM_LEFTMOST, CASELESS)));
    static int nMatches = 0;

    private final MatchHandler countMatch = (_, _, _) -> {
        nMatches += 1;
        return true;
    };

    @Test
    void executionModes() {
        var dbBlock = new Database(PATTERNS, BLOCK_MODE);
        var dbStream = new Database(PATTERNS, STREAM_MODE);
        dbBlock.close();
        dbStream.close();
    }

    @Test
    void emptyPatterns() {
        // empty pattern lists throw an exception
        assertThrows(RuntimeException.class, () -> new Database(new ArrayList<>(), BLOCK_MODE).close());
    }

    @Test
    void invalidPatterns() {
        // invalid patterns throw an exception
        List<Pattern> invalidPattern = List.of(new Pattern(
                "* is a quantifier with nothing in front of it -> should throw exception",
                EnumSet.noneOf(PatternFlag.class)));
        assertThrows(RuntimeException.class, () -> {
            var db = new Database(invalidPattern, BLOCK_MODE);
            db.close();
        });
    }

    @Test
    void getPattern() {
        try (Database database = new Database(PATTERNS, ExecutionMode.BLOCK_MODE)) {
            Pattern e = database.getPattern(2);
            assertEquals("pattern3", e.pattern());
            assertEquals(EnumSet.of(SOM_LEFTMOST, CASELESS), e.flags());
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> database.getPattern(-1));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> database.getPattern(PATTERNS.size()));
        }
    }

    @Test
    void modifyPatterns() {
        List<Pattern> patterns = Arrays.asList(
                new Pattern("pat1", EnumSet.noneOf(PatternFlag.class)),
                new Pattern("pat2", EnumSet.noneOf(PatternFlag.class)));
        try (Database database = new Database(patterns, ExecutionMode.BLOCK_MODE)) {
            patterns.set(0, new Pattern("pat3", EnumSet.noneOf(PatternFlag.class)));
        }
    }

    @Test
    void serializeDeserializeDatabase() {
        String input = "We have pat1 and pat2 int this input string.";
        List<Pattern> patterns = List.of(
                new Pattern("pat1", EnumSet.noneOf(PatternFlag.class)),
                new Pattern("pat2", EnumSet.noneOf(PatternFlag.class)));

        try (Database database = new Database(patterns, ExecutionMode.BLOCK_MODE)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            database.serialize(bos);
            byte[] payload = bos.toByteArray();
            ByteArrayInputStream bis = new ByteArrayInputStream(payload);
            Database roundTripDb = Database.deserialize(bis);
            assertEquals(database.getSize(), roundTripDb.getSize());
            assertEquals(database.getInfo(), roundTripDb.getInfo());
            assertEquals(database.getNumPatterns(), roundTripDb.getNumPatterns());
            assertEquals(database.getMode(), roundTripDb.getMode());
            assertEquals(database.getPattern(1), roundTripDb.getPattern(1));

            BlockScanner scanner = new BlockScanner(database);
            BlockScanner roundTripScanner = new BlockScanner(roundTripDb);
            nMatches = 0;
            scanner.scan(input, countMatch);
            int scanner1matches = nMatches;

            nMatches = 0;
            roundTripScanner.scan(input, countMatch);
            int scanner2matches = nMatches;
            assertEquals(scanner1matches, scanner2matches);
        }
    }

    @Test
    void serializeDeserializeStreamDatabaseFromByteArr() {
        String input = "We have pat1 and pat2 int this input string.";
        List<Pattern> patterns = List.of(
                new Pattern("pat1", EnumSet.noneOf(PatternFlag.class)),
                new Pattern("pat2", EnumSet.noneOf(PatternFlag.class)));

        try (Database database = new Database(patterns, STREAM_MODE);
                Database roundTripDb = Database.deserialize(database.serialize())) {
            assertEquals(database.getSize(), roundTripDb.getSize());
            assertEquals(database.getInfo(), roundTripDb.getInfo());
            assertEquals(database.getNumPatterns(), roundTripDb.getNumPatterns());
            assertEquals(database.getMode(), roundTripDb.getMode());
            assertEquals(database.modeNative, roundTripDb.modeNative);
            assertEquals(database.getPattern(1), roundTripDb.getPattern(1));

            StreamScanner scanner = new StreamScanner(database);
            StreamScanner roundTripScanner = new StreamScanner(roundTripDb);
            nMatches = 0;
            scanner.scan(input, countMatch);
            int scanner1matches = nMatches;

            nMatches = 0;
            roundTripScanner.scan(input, countMatch);
            int scanner2matches = nMatches;
            assertEquals(scanner1matches, scanner2matches);
        }
    }

    @Test
    void serializeThrowsUncheckedIOException() throws IOException {
        List<Pattern> patterns = List.of(new Pattern("test"));
        try (Database db = new Database(patterns, BLOCK_MODE);
                // Create a custom OutputStream that fails during write
                OutputStream failingStream = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("Simulated write failure");
                    }
                }) {
            assertThrows(UncheckedIOException.class, () -> db.serialize(failingStream));
        }
    }

    @Test
    void deserializeThrowsUncheckedIOException() throws IOException {
        // Create a custom OutputStream that fails during write
        try (InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Simulated read failure");
            }
        }) {
            assertThrows(UncheckedIOException.class, () -> Database.deserialize(failingStream));
        }
    }

    @Test
    void deserializeWrapsClassNotFoundException() {
        List<Pattern> patterns = List.of(new Pattern("pat1"));

        byte[] bytes;
        try (Database db = new Database(patterns, BLOCK_MODE)) {
            bytes = db.serialize();
        }

        byte[] original = "com.dynatrace.vectorscan4j.Pattern".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] patched = "com.dynatrace.vectorscan4j.PatterX".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(original.length, patched.length);

        int pos = indexOf(bytes, original);
        assertTrue(pos >= 0, "Serialized class name not found in payload");

        System.arraycopy(patched, 0, bytes, pos, patched.length);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            Database db = Database.deserialize(bytes);
            db.close();
        });
        assertInstanceOf(ClassNotFoundException.class, ex.getCause());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    void deserializeInvalidByteSequence() throws IOException, ClassNotFoundException {
        try (Database db = new Database(PATTERNS, BLOCK_MODE)) {
            byte[] dbBytes = db.serialize();

            // in order to make db invalid, we first figure out the serialized database's byte offset
            ByteArrayInputStream bis = new ByteArrayInputStream(dbBytes);
            DataInputStream dis = new DataInputStream(bis);
            ObjectInputStream ois = new ObjectInputStream(bis);

            // same read order as Database.deserialize(...)
            int nPatterns = dis.readInt();
            for (int i = 0; i < nPatterns; i++) {
                ois.readObject();
            }
            dis.readInt(); // mode ordinal
            int nativeOffset = dbBytes.length - bis.available(); // first byte of serialized native DB

            // then we flip the first native db byte
            dbBytes[nativeOffset] = (byte) ~dbBytes[nativeOffset];

            assertThrows(VectorscanException.class, () -> {
                var roundTripDb = Database.deserialize(dbBytes);
                roundTripDb.close();
            });
        }
    }

    @Test
    void closeDatabaseIsIdempotent() {
        Database db = new Database(PATTERNS, BLOCK_MODE);
        db.close();
        assertDoesNotThrow(db::close);
    }

    @Test
    void serializeAfterClose() {
        Database db = new Database(PATTERNS, STREAM_MODE);
        db.close();
        assertThrows(IllegalStateException.class, db::serialize);
    }

    @Test
    void patternsAreDefensivelyCopied() {
        List<Pattern> mutable = new ArrayList<>(List.of(new Pattern("pat1"), new Pattern("pat2")));

        try (Database db = new Database(mutable, BLOCK_MODE)) {
            BlockScanner scannerBefore = new BlockScanner(db);

            // Mutate the original list after database creation
            mutable.set(0, new Pattern("REPLACED"));
            mutable.add(new Pattern("pat3"));

            // The database should still reflect the original patterns
            assertEquals(2, db.getNumPatterns());
            assertEquals("pat1", db.getPattern(0).pattern());
            assertEquals("pat2", db.getPattern(1).pattern());

            // Scanners created before and after mutations should find the same patterns
            BlockScanner scannerAfter = new BlockScanner(db);
            String input = "We have pat1 and pat2 in this string";

            List<Integer> beforeMatches = new ArrayList<>();
            scannerBefore.scan(input, (id, _, _) -> {
                beforeMatches.add(id);
                return true;
            });

            List<Integer> afterMatches = new ArrayList<>();
            scannerAfter.scan(input, (id, _, _) -> {
                afterMatches.add(id);
                return true;
            });

            // Both scanners should find pat1 (id=0) and pat2 (id=1) once
            assertEquals(List.of(0, 1), beforeMatches);
            assertEquals(List.of(0, 1), afterMatches);

            scannerBefore.close();
            scannerAfter.close();
        }
    }

    @Test
    void operationsOnInvalidDb() {
        try (Database db = new Database(PATTERNS, STREAM_MODE)) {
            // flip the first byte in the native database, to make the encoded DB invalid
            byte b0 = db.dbNative.get(JAVA_BYTE, 0);
            db.dbNative.set(JAVA_BYTE, 0, (byte) (b0 ^ 0xFF));

            // then, call all the vectorscan native methods
            assertThrows(VectorscanException.class, db::getSize);
            assertThrows(VectorscanException.class, db::getInfo);
            assertThrows(VectorscanException.class, db::serialize);

            // flip the first byte back, to make the encoded DB valid again
            db.dbNative.set(JAVA_BYTE, 0, b0);

            assertDoesNotThrow(db::getSize);
            assertDoesNotThrow(db::getInfo);
            assertDoesNotThrow(() -> db.serialize());
        }
    }

    @Test
    void notClosingStillFreesMemory() throws InterruptedException {
        Database db = new Database(PATTERNS, BLOCK_MODE);
        WeakReference<Database> dbRef = new WeakReference<>(db);
        assertNotNull(dbRef.get(), "Database was freed pre-emptively.");
        db = null;

        System.gc();
        Thread.sleep(100);
        assertNull(dbRef.get(), "Database did not get freed.");
    }

    @Test
    void useDatabaseFromMultipleThreads() throws Exception {
        List<Pattern> patterns = List.of(new Pattern("pat1"), new Pattern("pat2"));
        String input = "pat1 xx pat1 yy pat1";

        int nThreads = 256;
        Database db = new Database(patterns, BLOCK_MODE);
        List<BlockScanner> scanners =
                Stream.generate(() -> new BlockScanner(db)).limit(nThreads).toList();
        CyclicBarrier allMatched = new CyclicBarrier(nThreads);
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        try {
            List<Future<List<Integer>>> futures = new ArrayList<>();
            for (int i = 0; i < nThreads; i++) {
                int finalI = i;
                Future<List<Integer>> f = pool.submit(() -> {
                    List<Integer> matchedIds = new ArrayList<>();
                    scanners.get(finalI).scan(input, (id, _, _) -> {
                        try {
                            allMatched.await(); // all scanners wait for each other to find a match until they continue.
                            matchedIds.add(id);
                        } catch (Exception _) {
                        }
                        return true;
                    });
                    return matchedIds;
                });
                futures.add(f);
            }

            List<List<Integer>> allMatches = new ArrayList<>();
            for (Future<List<Integer>> f : futures) {
                allMatches.add(f.get());
            }

            // Every scanner scanned the same input, so each should have found the same match IDs.
            List<Integer> expected = List.of(0, 0, 0);
            for (List<Integer> matches : allMatches) {
                assertEquals(expected, matches);
            }
        } finally {
            pool.shutdownNow();
        }

        db.close();
        for (var scanner : scanners) {
            scanner.close();
        }
    }
}
