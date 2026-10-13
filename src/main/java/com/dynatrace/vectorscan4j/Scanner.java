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

import static com.dynatrace.vectorscan4j.constants.ErrorCode.HS_SCAN_TERMINATED;
import static com.dynatrace.vectorscan4j.constants.ErrorCode.HS_SUCCESS;
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.*;
import static com.dynatrace.vectorscan4j.internal.VectorscanNativeShared.C_LONG;
import static com.dynatrace.vectorscan4j.internal.VectorscanNativeShared.C_POINTER;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.dynatrace.vectorscan4j.internal.VectorscanMatchHandler;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A Scanner object executes a vectorscan scan call on some input.
 *
 * <p>Each Scanner object owns its own scratch space which gets updated during a scan call, and is therefore
 * <strong>not</strong> safe to use concurrently from multiple threads. To scan in parallel,
 * create one {@code BlockScanner} per thread, all sharing the same {@link Database}.
 */
abstract class Scanner implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    /** Off-heap staging buffer that on-heap input is copied into before scanning; grown as needed. */
    protected ByteBuffer dataBuffer = ByteBuffer.allocateDirect(0);

    /** View of {@link #dataBuffer} passed to native code; rebuilt whenever that buffer is reallocated. */
    protected MemorySegment dataSegment = MemorySegment.ofBuffer(dataBuffer);

    /** Owns the upcall stubs; closed during cleanup. */
    protected final Arena arena = Arena.ofShared();

    private final CallHandlerOnMatch callHandler = new CallHandlerOnMatch();

    /** Upcall stub invoked once per match, backing the plain {@link MatchHandler} path. */
    protected final MemorySegment funcPtr;

    /** The compiled pattern database this scanner scans against. */
    protected final Database database;

    /** Native scratch space, private to this scanner and mutated by every scan call. */
    protected final MemorySegment scratchNative;

    private final Cleaner.Cleanable cleanable;

    protected Scanner(Database database) {
        this.database = database;
        this.funcPtr = VectorscanMatchHandler.allocate(callHandler, arena);
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment scratchPtr = temp.allocate(C_POINTER);
            int ans = hs_alloc_scratch(database.dbNative, scratchPtr);
            if (ans != HS_SUCCESS.getCode()) {
                throw new VectorscanException(ans);
            }
            scratchNative = scratchPtr.getAtIndex(C_POINTER, 0);
        }

        this.cleanable = CLEANER.register(this, new CleanupState(scratchNative, arena));
    }

    static class CallHandlerOnMatch implements VectorscanMatchHandler.Function {
        MatchHandler handler;

        @Override
        public int apply(int id, long from, long to, int flags, MemorySegment context) {
            return handler.onMatch(id, from, to) ? 0 : 1;
        }
    }

    protected static final class CleanupState implements Runnable {
        private final MemorySegment scratchNative;
        private final Arena arena;

        private CleanupState(MemorySegment scratchNative, Arena arena) {
            this.scratchNative = scratchNative;
            this.arena = arena;
        }

        @Override
        public void run() {
            try {
                hs_free_scratch(scratchNative);
            } catch (Throwable ignored) {
            }
            try {
                arena.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Ensures {@link #dataBuffer} can hold at least {@code requiredSize} bytes, reallocating it and
     * {@link #dataSegment} if it cannot.
     *
     * @param requiredSize number of bytes the next scan needs to stage
     */
    protected void ensureDataBuffer(int requiredSize) {
        if (requiredSize > dataBuffer.capacity()) {
            dataBuffer = ByteBuffer.allocateDirect(requiredSize);
            dataSegment = MemorySegment.ofBuffer(dataBuffer);
        }
    }

    /**
     * Installs the handler that the per-match upcall will forward to on the next scan.
     *
     * @param handler callback invoked for each match
     */
    protected void setHandler(MatchHandler handler) {
        this.callHandler.handler = handler;
    }

    // --------------------------- scan function overloads ---------------------------

    /**
     * Scans the given data string for the patterns that were compiled in the database, emitting a
     * callback for each match.
     *
     * <p>This is a convenience overload that delegates to {@link #scan(byte[], MatchHandler)}
     * using {@link StandardCharsets#UTF_8}.
     *
     * @param data   text to scan
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    public boolean scan(String data, ScanHandler handler) {
        return scan(data.getBytes(StandardCharsets.UTF_8), handler);
    }

    /**
     * Convenience overload so a {@link MatchHandler} lambda resolves without a cast; delegates to
     * {@link #scan(String, ScanHandler)}.
     *
     * @param data text to scan
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    public boolean scan(String data, MatchHandler handler) {
        return scan(data, (ScanHandler) handler);
    }

    /**
     * Scans the full byte array.
     *
     * @param data    input bytes
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    public boolean scan(byte[] data, ScanHandler handler) {
        ensureDataBuffer(data.length);
        dataBuffer.clear();
        dataBuffer.put(data);
        dataBuffer.flip();
        return scan(dataSegment, dataBuffer.remaining(), handler);
    }

    /**
     * Convenience overload so a {@link MatchHandler} lambda resolves without a cast; delegates to
     * {@link #scan(byte[], ScanHandler)}.
     *
     * @param data input bytes
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    public boolean scan(byte[] data, MatchHandler handler) {
        return scan(data, (ScanHandler) handler);
    }

    /**
     * Scans a subrange of the given byte array.
     *
     * <p>The scanned region starts at {@code offset} and spans {@code length} bytes.
     *
     * @param data    input bytes
     * @param offset  start index in {@code data}
     * @param length  number of bytes to scan
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     * @throws IndexOutOfBoundsException if {@code offset} or {@code length} are invalid for {@code
     *                                   data}
     */
    public boolean scan(byte[] data, int offset, int length, ScanHandler handler) {
        return scan(ByteBuffer.wrap(data, offset, length), handler);
    }

    /**
     * Convenience overload so a {@link MatchHandler} lambda resolves without a cast; delegates to
     * {@link #scan(byte[], int, int, ScanHandler)}.
     *
     * @param data input bytes
     * @param offset start index in {@code data}
     * @param length number of bytes to scan
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    public boolean scan(byte[] data, int offset, int length, MatchHandler handler) {
        return scan(data, offset, length, (ScanHandler) handler);
    }

    /**
     * Scans the content represented by the provided {@link ByteBuffer} (this means the range of bytes
     * starting from the ByteBuffer's position until its limit).
     *
     * <p>Direct buffers are scanned without copying via {@link
     * MemorySegment#ofBuffer(java.nio.Buffer)}. Non-direct buffers are copied into the scanner's
     * internal buffer before scanning.
     *
     * @param buf     input buffer
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    public boolean scan(ByteBuffer buf, ScanHandler handler) {
        if (buf.isDirect()) {
            return scan(MemorySegment.ofBuffer(buf), buf.remaining(), handler);
        } else {
            ensureDataBuffer(buf.remaining());
            dataBuffer.clear();
            dataBuffer.put(buf);
            dataBuffer.flip();
            return scan(dataSegment, dataBuffer.remaining(), handler);
        }
    }

    /**
     * Convenience overload so a {@link MatchHandler} lambda resolves without a cast; delegates to
     * {@link #scan(ByteBuffer, ScanHandler)}.
     *
     * @param buf input buffer
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    public boolean scan(ByteBuffer buf, MatchHandler handler) {
        return scan(buf, (ScanHandler) handler);
    }

    /**
     * Implementation hook used by concrete scanner types.
     *
     * <p>The given {@code data} contains exactly {@code length} bytes that should be scanned against
     * this scanner's compiled database. Implementations call into native vectorscan and forward match
     * callbacks to {@code handler}.
     *
     * @param dataSegment memory region containing the bytes to scan
     * @param length number of bytes to scan, starting at the beginning of {@code dataSegment}
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     * @return {@code true} if the scan ran to the end, {@code false} if the handler stopped it early
     */
    protected abstract boolean scan(MemorySegment dataSegment, int length, ScanHandler handler);

    /**
     * Translates the return code of an {@code hs_scan}-family call.
     *
     * @param ans the native return code
     * @return {@code true} for {@code HS_SUCCESS}, {@code false} for {@code HS_SCAN_TERMINATED}
     * @throws VectorscanException for any other code
     */
    protected static boolean completed(int ans) {
        if (ans == HS_SUCCESS.getCode()) {
            return true;
        }
        if (ans == HS_SCAN_TERMINATED.getCode()) {
            return false;
        }
        throw new VectorscanException(ans);
    }

    /**
     * Returns the size of this scanner's native scratch space.
     *
     * @return scratch size in bytes
     * @throws VectorscanException if the native call fails
     */
    public long getScratchSize() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment scratchSize = temp.allocate(C_LONG, 1);
            int ans = hs_scratch_size(this.scratchNative, scratchSize);
            if (ans != HS_SUCCESS.getCode()) {
                throw new VectorscanException(ans);
            }
            return scratchSize.getAtIndex(JAVA_LONG, 0);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
