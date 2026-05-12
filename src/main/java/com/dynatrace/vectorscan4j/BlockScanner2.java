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
import static com.dynatrace.vectorscan4j.constants.ExecutionMode.BLOCK_MODE;
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.hs_scan;

import com.dynatrace.vectorscan4j.constants.ErrorCode;
import com.dynatrace.vectorscan4j.internal.VectorscanMatchEventHandler;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Same scanning behavior as {@link BlockScanner}, but implemented without inheriting from
 * {@link Scanner}.
 */
public class BlockScanner2 implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private long bufferCapacity = 1024L;
    private int bufferLength;
    private Arena dataArena = Arena.ofConfined();
    private MemorySegment dataSegment = dataArena.allocate(bufferCapacity);
    private final Arena arena = Arena.ofConfined();
    private final Database database;
    private OnMatchEventHandler handler;
    private final MemorySegment scratch = null;
    private final MemorySegment funcPtr = VectorscanMatchEventHandler.allocate(new CallHandlerOnMatch(), arena);
    // private final Cleaner.Cleanable cleanable;

    //    private static final class CleanupState implements Runnable {
    //        private final MemorySegment scratch;
    //        private final Arena arena;
    //        private Arena dataArena;
    //
    //        private CleanupState(MemorySegment scratch, Arena dataArena, Arena arena) {
    //            this.scratch = scratch;
    //            this.dataArena = dataArena;
    //            this.arena = arena;
    //        }
    //
    //        private void setDataArena(Arena dataArena) {
    //            this.dataArena = dataArena;
    //        }
    //
    //        @Override
    //        public void run() {
    //            IO.println("Clean up BlockScanner2!");
    //            try {
    //                hs_free_scratch(scratch);
    //            } catch (Throwable ignored) {
    //            }
    //            try {
    //                dataArena.close();
    //            } catch (Throwable ignored) {
    //            }
    //            try {
    //                arena.close();
    //            } catch (Throwable ignored) {
    //            }
    //        }
    //    }

    private final class CallHandlerOnMatch implements VectorscanMatchEventHandler.Function {
        @Override
        public int apply(int id, long from, long to, int flags, MemorySegment context) {
            return handler.onMatch(id, from, to, flags) ? 0 : 1;
        }
    }

    public BlockScanner2(Database db) {
        if (db.getMode() != BLOCK_MODE) {
            throw new IllegalArgumentException("Database must have been compiled in block mode");
        }
        this.database = db;

        // MemorySegment scratchPtr = arena.allocate(C_POINTER);
        // int ans = hs_alloc_scratch(database.dbNative, scratchPtr);
        // ErrorCode errorCode = ErrorCode.fromCode(ans);
        // if (!errorCode.equals(HS_SUCCESS)) {
        //    throw new VectorscanException(errorCode);
        // }
        // scratch = scratchPtr.getAtIndex(C_POINTER, 0);
        // cleanable = CLEANER.register(this, new CleanupState(scratch, dataArena, arena));
        // IO.println("BlockScanner2 Cleaner registered");
    }

    private void setHandler(OnMatchEventHandler handler) {
        this.handler = handler;
    }

    private void ensureBufferCapacity(long needed) {
        if (needed > bufferCapacity) {
            resizeBuffer(needed);
        }
    }

    private void setBuffer(ByteBuffer input) {
        int length = input.remaining();
        ensureBufferCapacity(length);
        dataSegment.asByteBuffer().put(input);
        bufferLength = length;
    }

    private void resizeBuffer(long newSize) {
        dataArena.close();
        dataArena = Arena.ofConfined();
        // cleanupState.setDataArena(dataArena);
        dataSegment = dataArena.allocate(newSize);
        bufferCapacity = newSize;
    }

    public void scan(String input, OnMatchEventHandler handler) {
        scan(input.getBytes(StandardCharsets.UTF_8), handler);
    }

    public void scan(byte[] data, int offset, int length, OnMatchEventHandler handler) {
        scan(ByteBuffer.wrap(data, offset, length), handler);
    }

    public void scan(byte[] data, OnMatchEventHandler handler) {
        scan(ByteBuffer.wrap(data), handler);
    }

    public void scan(ByteBuffer buf, OnMatchEventHandler handler) {
        if (buf.isDirect()) {
            MemorySegment data = MemorySegment.ofBuffer(buf);
            scan(data, handler);
        } else {
            setBuffer(buf);
            scan(dataSegment.asSlice(0, bufferLength), handler);
        }
    }

    public void scan(MemorySegment data, OnMatchEventHandler handler) {
        if (data.byteSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Input MemorySegment is too big.");
        }
        if (database.isClosed()) {
            throw new IllegalStateException("Database was already closed.");
        }
        setHandler(handler);
        int ans = hs_scan(database.dbNative, data, (int) data.byteSize(), 0, scratch, funcPtr, MemorySegment.NULL);
        ErrorCode errorCode = ErrorCode.fromCode(ans);
        if (!errorCode.equals(HS_SUCCESS) && !errorCode.equals(HS_SCAN_TERMINATED)) {
            throw new VectorscanException(errorCode);
        }
    }

    public Database database() {
        return database;
    }

    @Override
    public void close() {
        //        cleanable.clean();
    }
}
