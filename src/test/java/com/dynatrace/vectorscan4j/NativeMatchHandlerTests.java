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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for {@link NativeMatchHandler} and the {@link BlockScanner#scan(MemorySegment,
 * NativeMatchHandler)} overload. Tests that require the test-only native callback library are
 * auto-skipped when it has not been built (via {@code buildTestNativeCallback}).
 */
public class NativeMatchHandlerTests {
    public static boolean nativeLibAvailable() {
        String p = System.getProperty("vs4j.native.countMatches");
        return p != null && Files.exists(Path.of(p));
    }

    private static Path libPath() {
        return Path.of(System.getProperty("vs4j.native.countMatches"));
    }

    // ---------- pure-Java validation (no native lib required) ----------

    @Test
    void rejectsNullFnPtr() {
        assertThrows(IllegalArgumentException.class, () -> new NativeMatchHandler(null, MemorySegment.NULL));
    }

    @Test
    void rejectsNullSegmentAsFnPtr() {
        assertThrows(
                IllegalArgumentException.class, () -> new NativeMatchHandler(MemorySegment.NULL, MemorySegment.NULL));
    }

    @Test
    void nullContextNormalizedToNullSegment() {
        MemorySegment sentinel = MemorySegment.ofAddress(0xDEADBEEFL);
        NativeMatchHandler h = new NativeMatchHandler(sentinel, null);
        assertEquals(MemorySegment.NULL, h.context());
    }

    @Test
    void fromLookupRequiresVersionedSuffix() {
        SymbolLookup dummy = new SymbolLookup() {
            @Override
            public Optional<MemorySegment> find(String name) {
                return Optional.empty();
            }
        };
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeMatchHandler.fromLookup(dummy, "on_match", MemorySegment.NULL));
    }

    @Test
    void scanRejectsNullHandler() {
        List<Expression> exprs = List.of(new Expression("foo"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom("foo");
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(data, (NativeMatchHandler) null));
        }
    }

    // ---------- native-callback path (requires the test shared lib) ----------

    @Test
    @EnabledIf("nativeLibAvailable")
    void nativeCallbackCountsSameAsJavaCallback() {
        byte[] input = "the quick brown fox jumps over the lazy dog. the the the.".getBytes(StandardCharsets.UTF_8);
        List<Expression> exprs = List.of(new Expression("the"));

        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                Arena arena = Arena.ofConfined()) {

            // 1) Java callback baseline
            long[] javaCount = {0};
            scanner.scan(input, (_, _, _, _) -> {
                javaCount[0]++;
                return true;
            });

            // 2) Native callback path
            MemorySegment ctx = arena.allocate(ValueLayout.JAVA_LONG);
            ctx.set(ValueLayout.JAVA_LONG, 0L, 0L);

            NativeMatchHandler handler = NativeMatchHandler.fromLibrary(libPath(), "vs4j_count_matches_v1", ctx, arena);

            MemorySegment data = arena.allocate(input.length);
            MemorySegment.copy(input, 0, data, ValueLayout.JAVA_BYTE, 0, input.length);
            scanner.scan(data, handler);

            long nativeCount = ctx.get(ValueLayout.JAVA_LONG, 0L);
            assertTrue(javaCount[0] > 0, "expected at least one match");
            assertEquals(javaCount[0], nativeCount, "native callback must observe the same matches as Java");
        }
    }

    @Test
    @EnabledIf("nativeLibAvailable")
    void fromLookupResolvesVersionedSymbol() {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(libPath(), arena);
            NativeMatchHandler handler =
                    NativeMatchHandler.fromLookup(lookup, "vs4j_count_matches_v1", MemorySegment.NULL);
            assertNotEquals(MemorySegment.NULL, handler.fnPtr());
        }
    }
}
