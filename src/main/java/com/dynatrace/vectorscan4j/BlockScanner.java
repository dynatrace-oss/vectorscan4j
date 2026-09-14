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
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.*;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

/**
 * A {@link Scanner} that operates in block mode: each call to {@code scan(...)} processes a single,
 * self-contained input buffer in isolation. Each scan starts from the initial DFA/NFA state and ends when the entire input
 * has been consumed (or the {@link MatchHandler} requests early termination by returning {@code false}).
 */
public class BlockScanner extends Scanner {
    /**
     * Creates a new block-mode scanner backed by the given compiled {@link Database}.
     *
     * @param db a database that was compiled with {@link
     *     com.dynatrace.vectorscan4j.constants.ExecutionMode#BLOCK_MODE BLOCK_MODE}
     * @throws IllegalArgumentException if {@code db} was not compiled in block mode
     */
    public BlockScanner(Database db) {
        if (db.getMode() != BLOCK_MODE) {
            throw new IllegalArgumentException("Database must have been compiled in block mode");
        }
        super(db);
    }

    public void scan(MemorySegment dataSegment, int length, ScanHandler handler) {
        if (database.isClosed()) {
            throw new IllegalStateException("Database was already closed.");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        switch (handler) {
            case MatchHandler h -> scan(dataSegment, length, h);
            case BulkMatchHandler h -> scan(dataSegment, length, h);
            case NativeMatchHandler h -> scan(dataSegment, length, h);
        }
    }

    /**
     * Scans bytes from the provided {@link MemorySegment} without copying them into an intermediate
     * buffer.
     *
     * <p>This is an advanced, zero-copy entry point intended for callers that manage off-heap or
     * foreign memory explicitly. The supplied {@code data} segment must remain alive and accessible
     * for the entire duration of this synchronous call.
     *
     * <p>The full range {@code [0, data.byteSize())} of the segment is scanned. To scan only a
     * sub-range, use {@link MemorySegment#asSlice(long, long)} before calling this method.
     *
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *     {@code false} to stop early
     * @throws IllegalArgumentException if {@code data.byteSize()} exceeds {@link Integer#MAX_VALUE}
     * @throws IllegalStateException if the underlying {@link Database} or this scanner has already
     *     been closed
     * @throws VectorscanException if the native scan call returns an error other than {@link
     *     com.dynatrace.vectorscan4j.constants.ErrorCode#HS_SCAN_TERMINATED HS_SCAN_TERMINATED}
     */
    private void scan(MemorySegment dataSegment, int length, MatchHandler handler) {
        setHandler(handler);
        int ans = hs_scan(this.database.dbNative, dataSegment, length, 0, scratchNative, funcPtr, MemorySegment.NULL);
        if (ans != HS_SUCCESS.getCode() && ans != HS_SCAN_TERMINATED.getCode()) {
            throw new VectorscanException(ans);
        }
    }

    private void scan(MemorySegment dataSegment, int length, BulkMatchHandler handler) {
        setBulkHandler(handler);
        int ans = hs_scan(
                this.database.dbNative,
                dataSegment,
                length,
                0,
                scratchNative,
                collect_match$address(),
                collectMatchCtx);
        if (ans != HS_SUCCESS.getCode() && ans != HS_SCAN_TERMINATED.getCode()) {
            throw new VectorscanException(ans);
        }
        int remaining = collectMatchCtx.get(JAVA_INT, 8);
        if (remaining > 0) {
            handler.handle(batchCallHandler.batchBuffer, remaining);
            collectMatchCtx.set(JAVA_INT, 8, 0);
        }
    }

    /**
     * Scans {@code data} using a <em>native</em> match-event callback supplied by the caller.
     *
     * This method passes the
     * caller-provided native function pointer directly to vectorscan, so matches do
     * <strong>not</strong> incur an upcall back into Java. This is intended for hot paths
     * where the per-match Java callback dominates cost.
     *
     * <p>The supplied {@code handler} is trusted: the JVM cannot verify the function's true
     * ABI from its address. The caller must ensure the underlying symbol matches the
     * {@code match_event_handler} signature documented on {@link NativeMatchHandler}.
     *
     * @param handler typed wrapper around the native callback and its opaque context
     * @throws IllegalArgumentException if {@code data.byteSize()} exceeds {@link Integer#MAX_VALUE}
     *                                  or {@code handler} is {@code null}
     * @throws IllegalStateException    if the underlying {@link Database} has been closed
     * @throws VectorscanException      if the native scan call returns an error other than
     *                                  {@link com.dynatrace.vectorscan4j.constants.ErrorCode#HS_SCAN_TERMINATED HS_SCAN_TERMINATED}
     */
    private void scan(MemorySegment dataSegment, int length, NativeMatchHandler handler) {
        int ans = hs_scan(
                this.database.dbNative, dataSegment, length, 0, scratchNative, handler.fnPtr(), handler.context());
        if (ans != HS_SUCCESS.getCode() && ans != HS_SCAN_TERMINATED.getCode()) {
            throw new VectorscanException(ans);
        }
    }
}
