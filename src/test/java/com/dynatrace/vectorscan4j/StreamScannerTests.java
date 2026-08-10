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

import static com.dynatrace.vectorscan4j.constants.ExecutionMode.STREAM_MODE;
import static com.dynatrace.vectorscan4j.constants.PatternFlag.CASELESS;
import static com.dynatrace.vectorscan4j.constants.PatternFlag.SOM_LEFTMOST;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

import com.dynatrace.vectorscan4j.constants.ExecutionMode;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

public class StreamScannerTests {
    static final List<Expression> expressions = Arrays.asList(
            new Expression("pattern1", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Expression("pattern2", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Expression("pattern3", EnumSet.of(SOM_LEFTMOST, CASELESS)));
    static int nMatches = 0;

    MatchHandler doNothing = (_, _, _) -> true;

    @Test
    void databaseWrongMode() {
        Database db = new Database(expressions, ExecutionMode.BLOCK_MODE);
        assertThrows(IllegalArgumentException.class, () -> new StreamScanner(db));
        db.close();
    }

    @Test
    void matchCanSpanMultipleScans() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            List<Integer> matchIds = new ArrayList<>();
            MatchHandler collectMatches = (id, _, _) -> {
                matchIds.add(id);
                return true;
            };

            scanner.scan("This ends with patt", collectMatches);
            scanner.scan("ern1 and now the pattern is complete.", collectMatches);

            assertEquals(List.of(0), matchIds);
        }
    }

    @Test
    void resetStreamPreventsMatchAcrossScans() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            List<Integer> matchIds = new ArrayList<>();
            MatchHandler collectMatches = (id, _, _) -> {
                matchIds.add(id);
                return true;
            };

            scanner.scan("This ends with patt", collectMatches);
            scanner.resetStream(doNothing);
            scanner.scan("ern1 and would only match without reset.", collectMatches);

            assertEquals(List.of(), matchIds);
        }
    }

    @Test
    void closeAndReopenStreamPreventsMatchAcrossScans() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            List<Integer> matchIds = new ArrayList<>();
            MatchHandler collectMatches = (id, _, _) -> {
                matchIds.add(id);
                return true;
            };

            scanner.scan("This ends with patt", collectMatches);
            scanner.closeStream((_, _, _) -> true);
            scanner.openStream();
            scanner.scan("ern1 and would only match without reopening.", collectMatches);

            assertEquals(List.of(), matchIds);
        }
    }

    @Test
    void earlystopScan() {
        // if a MatchHandler returns false, the execution of the engine stops early.
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            MatchHandler countMatchAndStop = (_, _, _) -> {
                nMatches += 1;
                return false;
            };
            String s = "Here is pattern1 and here is pattern2!";
            nMatches = 0;
            scanner.scan(s, countMatchAndStop);
            assertEquals(1, nMatches);
            // With a StreamScanner, stopping the execution in one scan also stops the next scan.
            // Thus, the second scan stops immediately, and we get no additional match.
            scanner.scan(s, countMatchAndStop);
            assertEquals(1, nMatches);

            // But if we reset the scanner, it would again start the next scan
            scanner.resetStream(doNothing);
            scanner.scan(s, countMatchAndStop);
            assertEquals(2, nMatches);
        }
    }

    @Test
    void scanAfterClosingDatabase() {
        Database db = new Database(expressions, STREAM_MODE);
        StreamScanner scanner = new StreamScanner(db);
        db.close();
        assertThrows(IllegalStateException.class, () -> scanner.scan("Test", doNothing));
    }

    @Test
    void scanAfterClosingStream() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            scanner.closeStream(doNothing);
            assertFalse(scanner.isStreamOpen());
            assertThrows(IllegalStateException.class, () -> scanner.scan("Hello, there!", doNothing));
        }
    }

    @Test
    void closeScanner() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            List<Integer> matchIds = new ArrayList<>();
            MatchHandler collectMatches = (id, _, _) -> {
                matchIds.add(id);
                return true;
            };
            scanner.closeStream(collectMatches);
            assertEquals(0, matchIds.size());
        }
    }

    @Test
    void closeStreamScannerIsIdempotent() {
        try (Database db = new Database(expressions, STREAM_MODE)) {
            StreamScanner scanner = new StreamScanner(db);
            assertDoesNotThrow(scanner::close);
            assertDoesNotThrow(scanner::close);
        }
    }

    @Test
    void tooBigInput() {
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db);
                Arena arena = Arena.ofConfined()) {
            MemorySegment tiny = arena.allocate(1);
            MemorySegment oversized = tiny.reinterpret((long) Integer.MAX_VALUE + 1L);

            assertThrows(IllegalArgumentException.class, () -> scanner.scan(oversized, doNothing));
        }
    }

    @Test
    void openStreamIsIdempotent() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            scanner.openStream();
            assertTrue(scanner.isStreamOpen());
            scanner.openStream();
            assertTrue(scanner.isStreamOpen());
        }
    }

    @Test
    void resetStreamIsIdempotent() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            scanner.resetStream(doNothing);
            assertTrue(scanner.isStreamOpen());
            scanner.resetStream(doNothing);
            assertTrue(scanner.isStreamOpen());
        }
    }

    @Test
    void closeStreamIsIdempotent() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            assertTrue(scanner.isStreamOpen());
            scanner.closeStream(doNothing);
            assertFalse(scanner.isStreamOpen());
            scanner.closeStream(doNothing);
            assertFalse(scanner.isStreamOpen());
        }
    }

    @Test
    void scannerOperationsWithInvalidDb() {
        try (Database db = new Database(expressions, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {

            // make the fifth byte in the native scratch space non-zero -> marks the scratch space as "being currently
            // in use"
            assertEquals((byte) 0, scanner.scratch.get(JAVA_BYTE, 4));
            scanner.scratch.set(JAVA_BYTE, 4, (byte) 0xFF);
            assertThrows(VectorscanException.class, () -> scanner.scan("Hello", doNothing));
            assertThrows(VectorscanException.class, () -> scanner.closeStream(doNothing));
            assertThrows(VectorscanException.class, () -> scanner.resetStream(doNothing));

            // flip the 5th byte back, to make the scratch space valid again
            scanner.scratch.set(JAVA_BYTE, 4, (byte) 0);
        }
    }

    @Test
    void closingStreamScannerSkipsLastMatches() {
        List<Expression> exprs = List.of(new Expression("pattern1$"));

        // Explicit closeStream(handler): deferred end-of-stream match is delivered to handler.
        try (Database db = new Database(exprs, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            List<Integer> matchIds = new ArrayList<>();
            MatchHandler collectMatches = (id, _, _) -> {
                matchIds.add(id);
                return true;
            };

            scanner.scan("This ends with pattern1", collectMatches);
            assertTrue(matchIds.isEmpty());

            scanner.closeStream(collectMatches);
            assertEquals(List.of(0), matchIds);
        }

        // Closing StreamScanner itself uses its internal close handler, so user handler sees no final event.
        List<Integer> userObserved = new ArrayList<>();
        try (Database db = new Database(exprs, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            MatchHandler collectMatches = (id, _, _) -> {
                userObserved.add(id);
                return true;
            };

            scanner.scan("This ends with pattern1", collectMatches);
            assertTrue(userObserved.isEmpty());
            // no explicit closeStream(collectMatches) call here
        }

        assertTrue(userObserved.isEmpty());
    }
}
