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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NativeMatchHandler} and the native-callback scan overloads on
 * {@link BlockScanner} / {@link StreamScanner}. Tests that require the test-only native callback
 * library are auto-skipped when it has not been built (via {@code buildTestNativeCallback}).
 */
public class NativeMatchHandlerTests {
    private static Path requireNativeCallbackLibrary() {
        String lib = System.getProperty("vs4j.native.countMatches");
        assumeTrue(lib != null && !lib.isBlank(), "Native callback test library not configured.");
        Path libPath = Path.of(lib);
        assumeTrue(Files.isRegularFile(libPath), "Native callback test library not built: " + libPath);
        return libPath;
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
    void fromLookupFailsWhenSymbolCannotBeResolved() {
        SymbolLookup dummy = new SymbolLookup() {
            @Override
            public Optional<MemorySegment> find(String name) {
                return Optional.empty();
            }
        };
        assertThrows(
                RuntimeException.class, () -> NativeMatchHandler.fromLookup(dummy, "on_match", MemorySegment.NULL));
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
    void nativeCallbackCountsSameAsJavaCallback() {
        Path libPath = requireNativeCallbackLibrary();
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

            NativeMatchHandler handler = NativeMatchHandler.fromLibrary(libPath, "vs4j_count_matches", ctx, arena);
            scanner.scan(input, handler);

            long nativeCount = ctx.get(ValueLayout.JAVA_LONG, 0L);
            assertTrue(javaCount[0] > 0, "expected at least one match");
            assertEquals(javaCount[0], nativeCount, "native callback must observe the same matches as Java");
        }
    }

    @Test
    void fromLookupResolvesVersionedSymbol() {
        Path libPath = requireNativeCallbackLibrary();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, arena);
            NativeMatchHandler handler =
                    NativeMatchHandler.fromLookup(lookup, "vs4j_count_matches", MemorySegment.NULL);
            assertNotEquals(MemorySegment.NULL, handler.fnPtr());
        }
    }
    // ---------- parity across input-type overloads (requires native lib) ----------
    @Test
    void allOverloadsAgreeWithJavaCallback() {
        Path libPath = requireNativeCallbackLibrary();
        String text = "the the the quick the brown fox the the the the the the the the";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        List<Expression> exprs = List.of(new Expression("the"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                Arena arena = Arena.ofConfined()) {
            long[] javaCount = {0};
            scanner.scan(text, (_, _, _, _) -> {
                javaCount[0]++;
                return true;
            });
            assertTrue(javaCount[0] > 0, "expected at least one match");
            class CounterCtx {
                final MemorySegment ctx = arena.allocate(ValueLayout.JAVA_LONG);
                final NativeMatchHandler handler =
                        NativeMatchHandler.fromLibrary(libPath, "vs4j_count_matches", ctx, arena);

                long count() {
                    return ctx.get(ValueLayout.JAVA_LONG, 0L);
                }
            }
            CounterCtx s = new CounterCtx();
            scanner.scan(text, s.handler);
            assertEquals(javaCount[0], s.count(), "String overload mismatch");
            CounterCtx b = new CounterCtx();
            scanner.scan(bytes, b.handler);
            assertEquals(javaCount[0], b.count(), "byte[] overload mismatch");
            int off = 4;
            int len = bytes.length - 8;
            long[] javaSubCount = {0};
            scanner.scan(bytes, off, len, (_, _, _, _) -> {
                javaSubCount[0]++;
                return true;
            });
            CounterCtx javaSub = new CounterCtx();
            scanner.scan(bytes, off, len, javaSub.handler);
            assertEquals(javaSubCount[0], javaSub.count(), "byte[]+offset+length overload mismatch");
            CounterCtx heap = new CounterCtx();
            scanner.scan(ByteBuffer.wrap(bytes), heap.handler);
            assertEquals(javaCount[0], heap.count(), "heap ByteBuffer overload mismatch");
            ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
            direct.put(bytes).flip();
            CounterCtx d = new CounterCtx();
            scanner.scan(direct, d.handler);
            assertEquals(javaCount[0], d.count(), "direct ByteBuffer overload mismatch");
        }
    }
    // ---------- null-handler uniformity across overloads (no native lib required) ----------
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
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scanner.scan(arena.allocateFrom("foo"), (NativeMatchHandler) null));
        }
    }

    @Test
    void streamScannerNativeCallbackCountsSameAsJavaCallback() {
        Path libPath = requireNativeCallbackLibrary();
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db);
                Arena arena = Arena.ofConfined()) {
            // Java callback baseline over two chunks (first match spans chunk boundary).
            long[] javaCount = {0};
            scanner.scan("this ends with patt", (_, _, _, _) -> {
                javaCount[0]++;
                return true;
            });
            scanner.scan("ern1 and pattern1 again", (_, _, _, _) -> {
                javaCount[0]++;
                return true;
            });
            assertEquals(2L, javaCount[0]);

            // Native callback path over the same chunks.
            scanner.resetStream((_, _, _, _) -> true);
            MemorySegment ctx = arena.allocate(ValueLayout.JAVA_LONG);
            ctx.set(ValueLayout.JAVA_LONG, 0L, 0L);
            NativeMatchHandler handler = NativeMatchHandler.fromLibrary(libPath, "vs4j_count_matches", ctx, arena);
            scanner.scan("this ends with patt", handler);
            scanner.scan("ern1 and pattern1 again", handler);

            long nativeCount = ctx.get(ValueLayout.JAVA_LONG, 0L);
            assertEquals(javaCount[0], nativeCount, "native stream callback must observe same matches as Java");
        }
    }
}
