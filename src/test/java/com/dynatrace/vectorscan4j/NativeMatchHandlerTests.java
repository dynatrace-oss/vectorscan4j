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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    static Path libPath;

    /**
     * Resolves the test-only native callback library, aborting the whole class if it is not there.
     * The assumption lives here rather than in a static initialiser because a {@code TestAbortedException}
     * thrown during class initialisation surfaces as {@code ExceptionInInitializerError} instead of a skip.
     */
    @BeforeAll
    static void requireNativeCallbackLibrary() {
        String lib = System.getProperty("vs4j.native.countMatches");
        assumeTrue(lib != null && !lib.isBlank(), "Native callback test library not configured.");
        libPath = Path.of(lib);
        assumeTrue(Files.isRegularFile(libPath), "Native callback test library not built: " + libPath);
    }

    /** Reads a field of a {@code Counter} handler's state struct. */
    private static long stateField(NativeMatchHandler handler, VarHandle field) {
        try (Arena arena = Arena.ofConfined()) {
            return (long) field.get(stateOf(handler, arena), 0L);
        } catch (Throwable t) {
            throw new AssertionError("failed to read handler state", t);
        }
    }

    /** Number of matches the handler has seen, read from its state struct. */
    private static long countOf(NativeMatchHandler handler) {
        return stateField(handler, STATE_MATCHES);
    }

    // ---------- symbol binding (no native lib required) ----------

    @Test
    void missingOnMatchSymbolThrows() {
        // The library itself loads fine, but exposes no "nosuch_on_match", so binding must fail.
        assertThrows(IllegalArgumentException.class, () -> new NativeMatchHandler(libPath, "nosuch"));
    }

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
    void initProvidesContextAndOnMatchResolves() {
        try (NativeMatchHandler handler = new NativeMatchHandler(libPath, "Counter")) {
            assertNotEquals(MemorySegment.NULL, handler.nativeOnMatch);
            assertNotEquals(MemorySegment.NULL, handler.context(), "Counter_init must supply a context");
            assertEquals(0L, countOf(handler), "a freshly initialised counter starts at zero");
        }
    }

    @Test
    void initAndFreeAreOptional() {
        // stateless exposes only _on_match, so construction and close must both work without
        // an _init or _free symbol, and the handler scans with a NULL context.
        NativeMatchHandler handler = new NativeMatchHandler(libPath, "stateless");
        assertNotEquals(MemorySegment.NULL, handler.nativeOnMatch);
        assertEquals(MemorySegment.NULL, handler.context());

        try (Database db = new Database(List.of(new Expression("the")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            assertDoesNotThrow(() -> scanner.scan("the the the", handler));
        }
        assertDoesNotThrow(handler::close);
    }

    @Test
    void closeIsIdempotent() {
        NativeMatchHandler handler = new NativeMatchHandler(libPath, "Counter");
        handler.close();
        assertDoesNotThrow(handler::close, "closing twice must not free the native object twice");
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(NullPointerException.class, () -> new NativeMatchHandler(null, "Counter"));
        assertThrows(NullPointerException.class, () -> new NativeMatchHandler(libPath, null));
        assertThrows(IllegalArgumentException.class, () -> new NativeMatchHandler(libPath, ""));
        assertThrows(IllegalArgumentException.class, () -> new NativeMatchHandler(libPath, "   "));
    }

    @Test
    void scanWithClosedHandlerThrows() {
        // Use a dedicated handler: closing the shared one would break the other tests.
        NativeMatchHandler handler = new NativeMatchHandler(libPath, "Counter");
        handler.close();
        try (Database db = new Database(List.of(new Expression("the")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            // close() releases the arena backing the library, which kills the scope of fnPtr.
            // FFM's downcall scope check rejects the dead segment, so a use-after-close surfaces
            // as an exception rather than a jump into unmapped memory.
            assertThrows(IllegalStateException.class, () -> scanner.scan("the the the", handler));
        }
    }

    @Test
    void canDelete() {
        Arena arena = Arena.ofConfined();
        SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, arena);
        var method = lookup.find("Counter_on_match").orElseThrow();
    }

    @Test
    void nativeCallbackCountsSameAsJavaCallback() {
        byte[] input = "the quick brown fox jumps over the lazy dog. the the the.".getBytes(StandardCharsets.UTF_8);
        List<Expression> exprs = List.of(new Expression("the"));

        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {

            long[] javaCount = {0};
            scanner.scan(input, (_, _, _) -> {
                javaCount[0]++;
                return true;
            });

            try (NativeMatchHandler handler = new NativeMatchHandler(libPath, "Counter")) {
                scanner.scan(input, handler);
                assertTrue(javaCount[0] > 0, "expected at least one match");
                assertEquals(javaCount[0], countOf(handler), "native callback must observe the same matches as Java");
            }
        }
    }

    /**
     * Mirror of {@code CounterState} in count-matches-native.cpp. The C++ side pins this layout with
     * {@code static_assert(offsetof(...))}, so a change there fails that build before it can silently
     * desync from this.
     */
    private static final MemoryLayout COUNTER_STATE = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("matches"), ValueLayout.JAVA_LONG.withName("lastEnd"));

    private static final VarHandle STATE_MATCHES =
            COUNTER_STATE.varHandle(MemoryLayout.PathElement.groupElement("matches"));
    private static final VarHandle STATE_LAST_END =
            COUNTER_STATE.varHandle(MemoryLayout.PathElement.groupElement("lastEnd"));

    /** Resolves the handler's {@code CounterState} through the exported {@code Counter_state} pointer. */
    private static MemorySegment stateOf(NativeMatchHandler handler, Arena arena) throws Throwable {
        MemorySegment fn = SymbolLookup.libraryLookup(libPath, arena).findOrThrow("Counter_state");
        MethodHandle state = Linker.nativeLinker()
                .downcallHandle(fn, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        return ((MemorySegment) state.invoke(handler.context())).reinterpret(COUNTER_STATE.byteSize());
    }

    @Test
    void readsStateStructThroughMemoryLayout() throws Throwable {
        try (Database db = new Database(List.of(new Expression("the")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                NativeMatchHandler handler = new NativeMatchHandler(libPath, "Counter");
                Arena arena = Arena.ofConfined()) {

            scanner.scan("the cat and the dog", handler);

            // One downcall to locate the struct, then plain VarHandle reads for every field.
            MemorySegment state = stateOf(handler, arena);
            assertEquals(2L, (long) STATE_MATCHES.get(state, 0L));
            assertEquals(
                    15L, (long) STATE_LAST_END.get(state, 0L), "second 'the' spans [12,15) in 'the cat and the dog'");
        }
    }

    @Test
    void handlerIsReusableAcrossScans() {
        try (Database db = new Database(List.of(new Expression("the")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                NativeMatchHandler handler = new NativeMatchHandler(libPath, "Counter")) {
            scanner.scan("the the", handler);
            assertEquals(2L, countOf(handler));
            // the native object survives between scans, so the count keeps accumulating
            scanner.scan("the the", handler);
            assertEquals(4L, countOf(handler));
        }
    }

    // ---------- parity across input-type overloads (requires native lib) ----------

    @Test
    void allOverloadsAgreeWithJavaCallback() {
        String text = "the the the quick the brown fox the the the the the the the the";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        List<Expression> exprs = List.of(new Expression("the"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            long[] javaCount = {0};
            scanner.scan(text, (_, _, _) -> {
                javaCount[0]++;
                return true;
            });
            assertTrue(javaCount[0] > 0, "expected at least one match");

            try (NativeMatchHandler h = new NativeMatchHandler(libPath, "Counter")) {
                scanner.scan(text, h);
                assertEquals(javaCount[0], countOf(h), "String overload mismatch");
            }
            try (NativeMatchHandler h = new NativeMatchHandler(libPath, "Counter")) {
                scanner.scan(bytes, h);
                assertEquals(javaCount[0], countOf(h), "byte[] overload mismatch");
            }

            int off = 4;
            int len = bytes.length - 8;
            long[] javaSubCount = {0};
            scanner.scan(bytes, off, len, (_, _, _) -> {
                javaSubCount[0]++;
                return true;
            });
            try (NativeMatchHandler h = new NativeMatchHandler(libPath, "Counter")) {
                scanner.scan(bytes, off, len, h);
                assertEquals(javaSubCount[0], countOf(h), "byte[]+offset+length overload mismatch");
            }

            try (NativeMatchHandler h = new NativeMatchHandler(libPath, "Counter")) {
                scanner.scan(ByteBuffer.wrap(bytes), h);
                assertEquals(javaCount[0], countOf(h), "heap ByteBuffer overload mismatch");
            }

            ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
            direct.put(bytes).flip();
            try (NativeMatchHandler h = new NativeMatchHandler(libPath, "Counter")) {
                scanner.scan(direct, h);
                assertEquals(javaCount[0], countOf(h), "direct ByteBuffer overload mismatch");
            }
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
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(arena.allocateFrom("foo"), 3, null));
        }
    }

    long javaCount = 0;

    @Test
    void streamScannerNativeCallbackCountsSameAsJavaCallback() {
        List<Expression> exprs = List.of(new Expression("pattern1"));
        try (Database db = new Database(exprs, STREAM_MODE);
                StreamScanner scanner = new StreamScanner(db)) {
            // Java callback baseline over two chunks (first match spans chunk boundary).
            MatchHandler javaIncrement = (_, _, _) -> {
                javaCount++;
                return true;
            };
            scanner.scan("this ends with patt", javaIncrement);
            scanner.scan("ern1 and pattern1 again", javaIncrement);
            assertEquals(2L, javaCount);

            // Native callback path over the same chunks.
            scanner.resetStream((_, _, _) -> true);
            try (NativeMatchHandler handler = new NativeMatchHandler(libPath, "Counter")) {
                scanner.scan("this ends with patt", handler);
                scanner.scan("ern1 and pattern1 again", handler);
                assertEquals(javaCount, countOf(handler), "native stream callback must observe same matches as Java");
            }
        }
    }
}
