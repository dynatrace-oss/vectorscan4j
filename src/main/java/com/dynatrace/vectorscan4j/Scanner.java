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
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.hs_alloc_scratch;
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.hs_free_scratch;
import static com.dynatrace.vectorscan4j.internal.VectorscanNativeShared.C_POINTER;

import com.dynatrace.vectorscan4j.internal.VectorscanMatchEventHandler;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

abstract class Scanner implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    private long bufferCapacity = 0L; // initally internal buffer is empty
    private int bufferLength;
    private Arena dataArena = Arena.ofShared();
    private MemorySegment dataSegment = dataArena.allocate(bufferCapacity);
    protected final Arena arena = Arena.ofShared();
    protected final Database database;
    protected final MemorySegment scratch;
    private final CallHandlerOnMatch callHandler = new CallHandlerOnMatch();
    protected MemorySegment funcPtr = VectorscanMatchEventHandler.allocate(callHandler, arena);

    protected final CleanupState cleanupState;
    private final Cleaner.Cleanable cleanable;

    static class CallHandlerOnMatch implements VectorscanMatchEventHandler.Function {
        OnMatchEventHandler handler;

        @Override
        public int apply(int id, long from, long to, int flags, MemorySegment context) {
            return handler.onMatch(id, from, to, flags) ? 0 : 1;
        }
    }

    protected static final class CleanupState implements Runnable {
        private final MemorySegment scratch;
        private final Arena arena; // manages lifetime of internal scratch space.
        private Arena dataArena; // manages lifetime of internal data segment.

        private CleanupState(MemorySegment scratch, Arena dataArena, Arena arena) {
            this.scratch = scratch;
            this.dataArena = dataArena;
            this.arena = arena;
        }

        private void setDataArena(Arena dataArena) {
            this.dataArena = dataArena;
        }

        @Override
        public void run() {
            try {
                hs_free_scratch(scratch);
            } catch (Throwable ignored) {
            }
            try {
                dataArena.close();
            } catch (Throwable ignored) {
            }
            try {
                arena.close();
            } catch (Throwable ignored) {
            }
        }
    }

    protected void setHandler(OnMatchEventHandler handler) {
        this.callHandler.handler = handler;
    }

    protected Scanner(Database database) {
        this.database = database;

        // allocate scratch space
        MemorySegment scratchPtr = arena.allocate(C_POINTER);
        int ans = hs_alloc_scratch(database.dbNative, scratchPtr);
        if (ans != HS_SUCCESS.getCode()) {
            throw new VectorscanException(ans);
        }
        scratch = scratchPtr.getAtIndex(C_POINTER, 0);
        this.cleanupState = new CleanupState(scratch, dataArena, arena);
        this.cleanable = CLEANER.register(this, cleanupState);
    }

    private void ensureBufferCapacity(long needed) {
        if (needed > bufferCapacity) {
            resizeBuffer(needed);
        }
    }

    protected void setBuffer(ByteBuffer input) {
        int length = input.remaining();
        // if the internal direct ByteBuffer is too small to fit the whole input, allocate a bigger ByteBuffer
        ensureBufferCapacity(length);
        dataSegment.asByteBuffer().put(input);
        bufferLength = length;
    }

    private void resizeBuffer(long newSize) {
        dataArena.close();
        dataArena = Arena.ofShared();
        cleanupState.setDataArena(dataArena);
        dataSegment = dataArena.allocate(newSize);
        bufferCapacity = newSize;
    }

    /**
     * Scans the given input string for the patterns that were compiled in the database, emitting a
     * callback for each match.
     *
     * <p>This is a convenience overload that delegates to {@link #scan(byte[], OnMatchEventHandler)}
     * using {@link StandardCharsets#UTF_8}.
     *
     * @param input   text to scan
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     */
    public void scan(String input, OnMatchEventHandler handler) {
        scan(input.getBytes(StandardCharsets.UTF_8), handler);
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
    public void scan(byte[] data, int offset, int length, OnMatchEventHandler handler) {
        scan(ByteBuffer.wrap(data, offset, length), handler);
    }

    /**
     * Scans the full byte array.
     *
     * @param data    input bytes
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *                {@code false} to stop early
     */
    public void scan(byte[] data, OnMatchEventHandler handler) {
        scan(ByteBuffer.wrap(data), handler);
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
    public void scan(ByteBuffer buf, OnMatchEventHandler handler) {
        if (buf.isDirect()) {
            MemorySegment data = MemorySegment.ofBuffer(buf);
            scan(data, handler);
        } else {
            setBuffer(buf);
            scan(dataSegment.asSlice(0, bufferLength), handler);
        }
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
    protected abstract void scan(MemorySegment data, OnMatchEventHandler handler);

    public Database database() {
        return database;
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
