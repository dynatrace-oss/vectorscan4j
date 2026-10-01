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

import com.dynatrace.vectorscan4j.native_match_handlers.NativeBatchHandler;
import com.dynatrace.vectorscan4j.native_match_handlers.NativeCollectMatches;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    private static final Path HEADER_DIR = Path.of(System.getProperty("user.dir"), "src/main/resources/native");
    private static final Path HEADER = HEADER_DIR.resolve("vs4j_handler.h");

    static Path libPath;

    /**
     * Compiles every {@code .cpp} file in {@value #NATIVE_DIR} into a {@code .so} of the same name,
     * skipping any file whose {@code .so} is already newer than both the source and
     * {@code vs4j_handler.h}. Aborts the whole class (rather than failing it) if {@code c++} is
     * unavailable or a compile fails -- this lives in {@code @BeforeAll} rather than a static
     * initialiser because a {@code TestAbortedException} thrown there surfaces as
     * {@code ExceptionInInitializerError} instead of a clean skip.
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
                                "c++",
                                "-std=c++17",
                                "-O3",
                                "-fPIC",
                                "-shared",
                                "-I",
                                HEADER_DIR.toString(),
                                cpp.toString(),
                                "-o",
                                so.toString())
                        .inheritIO()
                        .start();
                assumeTrue(
                        proc.waitFor() == 0,
                        "Failed to compile " + cpp.getFileName() + "; native-callback tests will be skipped.");
            }
        }

        libPath = NATIVE_DIR.resolve("count-matches-native.so");
    }

    private static boolean isUpToDate(Path so, Path cpp) throws IOException {
        if (!Files.isRegularFile(so)) {
            return false;
        }
        FileTime soTime = Files.getLastModifiedTime(so);
        return soTime.compareTo(Files.getLastModifiedTime(cpp)) > 0
                && (!Files.isRegularFile(HEADER) || soTime.compareTo(Files.getLastModifiedTime(HEADER)) > 0);
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
        return stateOf(handler, arena, "Counter");
    }

    private static MemorySegment stateOf(NativeMatchHandler handler, Arena arena, String prefix) throws Throwable {
        MemorySegment fn = SymbolLookup.libraryLookup(libPath, arena).findOrThrow(prefix + "_state");
        MethodHandle state = Linker.nativeLinker()
                .downcallHandle(fn, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        return ((MemorySegment) state.invoke(handler.context())).reinterpret(COUNTER_STATE.byteSize());
    }

    @Test
    void initArgIsPassedToConfigure() throws Throwable {
        List<Expression> exprs = List.of(new Expression("aa"), new Expression("bb"));
        try (Database db = new Database(exprs, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                Arena arena = Arena.ofConfined()) {

            // CounterConfig { int32_t maxId } -- only ids <= maxId are counted.
            MemorySegment config = arena.allocate(ValueLayout.JAVA_INT);
            config.set(ValueLayout.JAVA_INT, 0L, 0); // id 0 is "aa", id 1 is "bb"

            try (NativeMatchHandler handler = new NativeMatchHandler(libPath, "Configured", config)) {
                scanner.scan("aa bb aa bb", handler);
                assertEquals(
                        2L,
                        (long) STATE_MATCHES.get(stateOf(handler, arena, "Configured"), 0L),
                        "only the two 'aa' matches should be counted");
            }
        }
    }

    @Test
    void omittedInitArgReachesConfigureAsNull() throws Throwable {
        try (Database db = new Database(List.of(new Expression("aa")), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db);
                Arena arena = Arena.ofConfined();
                // two-arg constructor: configure() sees null and leaves maxId at its default
                NativeMatchHandler handler = new NativeMatchHandler(libPath, "Configured")) {

            scanner.scan("aa aa", handler);
            assertEquals(
                    0L,
                    (long) STATE_MATCHES.get(stateOf(handler, arena, "Configured"), 0L),
                    "unconfigured, maxId stays -1 so nothing is counted");
        }
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

    @Test
    void nativePrintHandler() {
        Path tempPath = Path.of(System.getProperty("user.dir"), "src/test/resources/native/native_print_handler.so");
        NativeMatchHandler2 handler = new NativeMatchHandler2(tempPath);
        List<Expression> exprs = List.of(new Expression("pattern1"));
        Database db = new Database(exprs, BLOCK_MODE);
        BlockScanner scanner = new BlockScanner(db);
        scanner.scan("Here is pattern1. You should get 1 match.", handler);
        handler.close();
    }

    @Test
    void nativeCountMatches() {
        Path tempPath = Path.of(System.getProperty("user.dir"), "src/test/resources/native/native_count_matches.so");
        NativeMatchHandler2 handler = new NativeMatchHandler2(tempPath);
        assertEquals(0, handler.context().get(ValueLayout.JAVA_INT, 0));
        List<Expression> exprs = List.of(new Expression("pattern1"));
        Database db = new Database(exprs, BLOCK_MODE);
        BlockScanner scanner = new BlockScanner(db);
        scanner.scan("Here is pattern1. You should get 1 match.", handler);
        assertEquals(1, handler.context().get(ValueLayout.JAVA_INT, 0));
        scanner.scan("Here is pattern1. You should get 1 match.", handler);
        assertEquals(2, handler.context().get(ValueLayout.JAVA_INT, 0));
        handler.close();
    }

    @Test
    void contextThrowsAfterClose() {
        Path tempPath = Path.of(System.getProperty("user.dir"), "src/test/resources/native/native_count_matches.so");
        NativeMatchHandler2 handler = new NativeMatchHandler2(tempPath);
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
    void nativeCollectMatches() {
        Path tempPath = Path.of(System.getProperty("user.dir"), "src/test/resources/native/native_collect_matches.so");
        NativeCollectMatches handler = new NativeCollectMatches(tempPath);

        assertEquals(0, handler.getInt("count"));
        assertEquals(1, handler.getInt("capacity"));
        List<Expression> exprs = List.of(new Expression("pattern1"));
        Database db = new Database(exprs, BLOCK_MODE);
        BlockScanner scanner = new BlockScanner(db);
        scanner.scan("Here is pattern1. You should get 1 match.", handler);
        assertEquals(1, handler.count());
        assertEquals(1, handler.capacity());
        scanner.scan("Here is pattern1. You should get 1 match.", handler);
        assertEquals(2, handler.count());
        assertEquals(2, handler.capacity());
        scanner.scan("Here is pattern1. You should get 1 match.", handler);
        assertEquals(3, handler.count());
        assertEquals(4, handler.capacity());
        handler.close();
    }

    @Test
    void nativeBatchHandler() {
        Path tempPath = Path.of(System.getProperty("user.dir"), "src/test/resources/native/native_batch_handler.so");

        NativeBatchHandler handler = new NativeBatchHandler(tempPath, 4);
        // List<Expression> exprs = List.of(new Expression("pattern1"));
        // Database db = new Database(exprs, BLOCK_MODE);
        // BlockScanner scanner = new BlockScanner(db);
        // scanner.scan("Here is pattern1. You should get 1 match.", handler);
        handler.close();
        // scanner.close();
    }
}
