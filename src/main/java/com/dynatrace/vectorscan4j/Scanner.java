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

import static com.dynatrace.vectorscan4j.constants.ErrorCode.HS_SUCCESS;
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.*;
import static com.dynatrace.vectorscan4j.internal.VectorscanNativeShared.C_LONG;
import static com.dynatrace.vectorscan4j.internal.VectorscanNativeShared.C_POINTER;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.dynatrace.vectorscan4j.internal.VectorscanBatchedMatchHandler;
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
    private ByteBuffer dataBuffer = ByteBuffer.allocateDirect(0);
    protected final Arena arena = Arena.ofShared();
    private final CallHandlerOnMatch callHandler = new CallHandlerOnMatch();
    private final BatchCallHandler batchCallHandler = new BatchCallHandler();
    protected final MemorySegment funcPtr;
    protected final MemorySegment batchFuncPtr;
    protected final Database database;
    protected final MemorySegment scratchNative;
    protected final MemorySegment collectMatchCtx;
    private int batchBufferCapacity = 0;
    private final Cleaner.Cleanable cleanable;

    protected Scanner(Database database) {
        this.database = database;
        this.funcPtr = VectorscanMatchHandler.allocate(callHandler, arena);
        this.batchFuncPtr = VectorscanBatchedMatchHandler.allocate(batchCallHandler, arena);
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment scratchPtr = temp.allocate(C_POINTER);
            int ans = hs_alloc_scratch(database.dbNative, scratchPtr);
            if (ans != HS_SUCCESS.getCode()) {
                throw new VectorscanException(ans);
            }
            scratchNative = scratchPtr.getAtIndex(C_POINTER, 0);

            MemorySegment ctxPtr = temp.allocate(C_POINTER);
            alloc_context(batchFuncPtr, ctxPtr);
            collectMatchCtx = ctxPtr.getAtIndex(C_POINTER, 0);
        }

        this.cleanable = CLEANER.register(this, new CleanupState(scratchNative, collectMatchCtx, arena));
    }

    static class CallHandlerOnMatch implements VectorscanMatchHandler.Function {
        MatchHandler handler;

        @Override
        public int apply(int id, long from, long to, int flags, MemorySegment context) {
            return handler.onMatch(id, from, to) ? 0 : 1;
        }
    }

    static class BatchCallHandler implements VectorscanBatchedMatchHandler.Function {
        BulkMatchHandler handler;

        @Override
        public int apply(MemorySegment buffer, int count) {
            return handler.handle(buffer, count);
        }
    }

    protected static final class CleanupState implements Runnable {
        private final MemorySegment scratchNative;
        private final MemorySegment collectMatchCtx;
        private final Arena arena;

        private CleanupState(MemorySegment scratchNative, MemorySegment collectMatchCtx, Arena arena) {
            this.scratchNative = scratchNative;
            this.collectMatchCtx = collectMatchCtx;
            this.arena = arena;
        }

        @Override
        public void run() {
            try {
                hs_free_scratch(scratchNative);
            } catch (Throwable ignored) {
            }
            try {
                free_context(collectMatchCtx);
            } catch (Throwable ignored) {
            }
            try {
                arena.close();
            } catch (Throwable ignored) {
            }
        }
    }

    protected void setBuffer(ByteBuffer input) {
        int requiredSize = input.remaining();
        if (requiredSize > dataBuffer.capacity()) {
            dataBuffer = ByteBuffer.allocateDirect(requiredSize);
        }
        dataBuffer.clear();
        dataBuffer.put(input);
        dataBuffer.flip();
    }

    protected void setHandler(MatchHandler handler) {
        this.callHandler.handler = handler;
    }

    protected void setBulkHandler(BulkMatchHandler handler) {
        this.batchCallHandler.handler = handler;
        if (handler.bulkSize() > batchBufferCapacity) {
            resize_buffer(collectMatchCtx, handler.bulkSize());
            batchBufferCapacity = handler.bulkSize();
        }
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
     */
    public void scan(String data, ScanHandler handler) {
        scan(data.getBytes(StandardCharsets.UTF_8), handler);
    }

    public void scan(String data, MatchHandler handler) {
        scan(data, (ScanHandler) handler);
    }

    /**
     * Scans the full byte array.
     *
     * @param data    input bytes
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     */
    public void scan(byte[] data, ScanHandler handler) {
        scan(ByteBuffer.wrap(data), handler);
    }

    public void scan(byte[] data, MatchHandler handler) {
        scan(data, (ScanHandler) handler);
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
     * @throws IndexOutOfBoundsException if {@code offset} or {@code length} are invalid for {@code
     *                                   data}
     */
    public void scan(byte[] data, int offset, int length, ScanHandler handler) {
        scan(ByteBuffer.wrap(data, offset, length), handler);
    }

    public void scan(byte[] data, int offset, int length, MatchHandler handler) {
        scan(data, offset, length, (ScanHandler) handler);
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
     */
    public void scan(ByteBuffer buf, ScanHandler handler) {
        if (buf.isDirect()) {
            scan(MemorySegment.ofBuffer(buf), handler);
        } else {
            setBuffer(buf);
            scan(MemorySegment.ofBuffer(dataBuffer), handler);
        }
    }

    public void scan(ByteBuffer buf, MatchHandler handler) {
        scan(buf, (ScanHandler) handler);
    }

    /**
     * Implementation hook used by concrete scanner types.
     *
     * <p>The given {@code data} contains exactly {@code length} bytes that should be scanned against
     * this scanner's compiled database. Implementations call into native vectorscan and forward match
     * callbacks to {@code handler}.
     *
     * @param data    memory region containing scan input
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     */
    protected abstract void scan(MemorySegment data, ScanHandler handler);

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
