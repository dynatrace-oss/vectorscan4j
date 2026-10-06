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

import com.dynatrace.vectorscan4j.internal.VectorscanBatchedMatchHandler;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Same behaviour as {@link com.dynatrace.vectorscan4j.BatchedMatchHandler}, as a {@link NativeMatchHandler}:
 * matches are collected natively and handed to a {@link BatchHandler} a batch at a time, so a scan pays one
 * upcall per batch rather than one per match. Whatever is left in a partial batch is delivered after the scan.
 */
public class NativeBatchHandler extends NativeMatchHandler {
    /** Bytes per collected match: id, from and to as 32-bit values. */
    private static final int ENTRY_BYTES = 12;

    /** Mirror of {@code config} in native_batch_handler.cpp, handed to {@code init()}. */
    private static final MemoryLayout CONFIG_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("batchSize"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("javaHandler"));

    private static final VarHandle CONFIG_BATCH_SIZE =
            CONFIG_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("batchSize"));
    private static final VarHandle CONFIG_JAVA_HANDLER =
            CONFIG_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("javaHandler"));

    /** Mirror of {@code context} in native_batch_handler.cpp, read through {@link #context()}. */
    private static final MemoryLayout CONTEXT_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("buffer"),
            ValueLayout.JAVA_INT.withName("count"),
            ValueLayout.JAVA_INT.withName("batchSize"),
            ValueLayout.ADDRESS.withName("javaHandler"));

    private static final VarHandle CTX_BUFFER =
            CONTEXT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("buffer"));
    private static final VarHandle CTX_COUNT = CONTEXT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("count"));

    /**
     * Owns the upcall stub. Native code holds a raw pointer to that stub, so this field is what keeps it alive:
     * an auto arena is freed once unreachable, and nothing else on the Java side references the stub.
     */
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final Arena upcallArena;

    private final BatchCallHandler callHandler;

    /**
     * @param libraryPath the compiled native_batch_handler library
     * @param batchSize how many matches to collect before handing them to {@code handler}; at least 1
     * @param handler receives each batch; return {@code false} to stop the scan
     */
    public NativeBatchHandler(Path libraryPath, int batchSize, BatchHandler handler) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        Objects.requireNonNull(handler, "handler must not be null");

        Arena upcallArena = Arena.ofAuto();
        BatchCallHandler callHandler = new BatchCallHandler(handler);
        MemorySegment config = upcallArena.allocate(CONFIG_LAYOUT);
        CONFIG_BATCH_SIZE.set(config, 0L, batchSize);
        CONFIG_JAVA_HANDLER.set(config, 0L, VectorscanBatchedMatchHandler.allocate(callHandler, upcallArena));
        this.upcallArena = upcallArena;
        this.callHandler = callHandler;

        super(libraryPath, config);

        // The buffer is allocated once by init() and never reallocated, so one view serves every batch.
        MemorySegment buffer = (MemorySegment) CTX_BUFFER.get(context(), 0L);
        callHandler.buffer = buffer.reinterpret((long) batchSize * ENTRY_BYTES)
                .asByteBuffer()
                .order(ByteOrder.nativeOrder());
    }

    /** Discards a partial batch left behind by a scan that ended in an exception. */
    @Override
    public void preScan() {
        CTX_COUNT.set(context(), 0L, 0);
    }

    /** Delivers whatever is left in a partial batch once the scan has finished. */
    @Override
    public void postScan() {
        int remaining = (int) CTX_COUNT.get(context(), 0L);
        if (remaining > 0) {
            callHandler.deliver(remaining);
            CTX_COUNT.set(context(), 0L, 0);
        }
    }

    private static final class BatchCallHandler implements VectorscanBatchedMatchHandler.Function {
        private final BatchHandler handler;
        private ByteBuffer buffer;

        private BatchCallHandler(BatchHandler handler) {
            this.handler = handler;
        }

        /** Called by native code each time a batch fills up. */
        @Override
        public int apply(MemorySegment ignored, int count) {
            return deliver(count) ? 0 : 1;
        }

        boolean deliver(int count) {
            buffer.limit(count * ENTRY_BYTES).position(0);
            return handler.matchAll(buffer, count);
        }
    }
}
