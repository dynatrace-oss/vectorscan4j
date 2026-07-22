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
import static com.dynatrace.vectorscan4j.constants.ExecutionMode.STREAM_MODE;
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.*;
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.hs_close_stream;
import static com.dynatrace.vectorscan4j.internal.VectorscanNativeShared.C_POINTER;

import java.lang.foreign.MemorySegment;

/**
 * Stateful scanner for vectorscan stream mode.
 *
 * <p>Stream scanning preserves matcher state across successive {@code
 * scan(...)} calls, which allows matches to span chunk boundaries. The stream state can be reset
 * with {@link #resetStream(MatchHandler handler)} or terminated with {@link #closeStream(MatchHandler)}. After
 * closing, a new state can be created via {@link #openStream()}.
 *
 * <p>Always close this scanner to release native resources deterministically. Prefer
 * try-with-resources, or call {@link #close()} manually when done.
 *
 * <p>This class is not thread-safe. Use each instance from one thread at a time.
 */
public class StreamScanner extends Scanner {
    private final MemorySegment streamPtr;
    private MemorySegment stream;
    private boolean streamOpen = false;

    /**
     * Builds a stream scanner from an already compiled database.
     *
     * <p>The database must have been compiled in stream mode; otherwise construction fails. A stream
     * is opened immediately.
     *
     * @param db stream-mode database
     * @throws IllegalArgumentException if {@code db} was not compiled with {@code HS_MODE_STREAM}
     */
    public StreamScanner(Database db) {
        if (db.getMode() != STREAM_MODE) {
            throw new IllegalArgumentException("Database must have been compiled in stream mode");
        }
        super(db);
        streamPtr = arena.allocate(C_POINTER);
        openStream();
    }

    /**
     * Scans bytes from the provided {@link MemorySegment} without copying them into an intermediate
     * buffer.
     *
     * <p>This is an advanced, zero-copy entry point intended for callers that manage off-heap or
     * foreign memory explicitly. The supplied {@code data} segment must remain alive and accessible
     * for the entire duration of this synchronous call.
     *
     * <p>Only the first {@code length} bytes of {@code data} are scanned. Callers must ensure that
     * {@code length} is non-negative and does not exceed {@code data.byteSize()}.
     *
     * @param data memory region containing the bytes to scan
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *     {@code false} to stop early
     * @throws VectorscanException if vectorscan reports an error other than early termination
     */
    public void scan(MemorySegment data, MatchHandler handler) {
        if (!streamOpen) {
            throw new IllegalStateException("Stream is closed. Open a new stream first.");
        }
        if (data.byteSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Input MemorySegment is too big.");
        }
        if (database.isClosed()) {
            throw new IllegalStateException("Database was already closed.");
        }
        setHandler(handler);
        int ans = hs_scan_stream(stream, data, (int) data.byteSize(), 0, scratch, funcPtr, MemorySegment.NULL);
        if (ans != HS_SUCCESS.getCode() && ans != HS_SCAN_TERMINATED.getCode()) {
            throw new VectorscanException(ans);
        }
    }

    /**
     * Opens a new native stream state for this scanner.
     *
     * <p>Use this after {@link #closeStream(MatchHandler)} when starting a fresh stream.
     *
     * @throws VectorscanException if vectorscan cannot open the stream
     */
    public void openStream() {
        if (streamOpen) return;
        int ans = hs_open_stream(database.dbNative, 0, streamPtr);
        if (ans != HS_SUCCESS.getCode()) {
            throw new VectorscanException(ans);
        }
        stream = streamPtr.getAtIndex(C_POINTER, 0);
        streamOpen = true;
    }

    /**
     * Resets the current stream back to its initial state.
     *
     * <p>After reset, subsequent scans do not retain any partial match context from earlier chunks.
     * Vectorscan may emit end-of-stream matches during the reset; these are delivered through
     * {@code handler}.
     *
     * @param handler callback used for any matches emitted during the reset
     * @throws VectorscanException if vectorscan reports an error during reset
     */
    public void resetStream(MatchHandler handler) {
        setHandler(handler);
        int ans = hs_reset_stream(stream, 0, scratch, funcPtr, MemorySegment.NULL);
        if (ans != HS_SUCCESS.getCode()) {
            throw new VectorscanException(ans);
        }
        streamOpen = true;
    }

    /**
     * Closes the current stream and releases its native state.
     *
     * <p>Vectorscan may emit end-of-stream matches while closing; these are delivered through {@code
     * handler}. If you do not care about close-time matches, pass a no-op handler.
     *
     * @param handler callback used for any close-time matches
     * @throws VectorscanException if vectorscan reports an error while closing the stream
     */
    public void closeStream(MatchHandler handler) {
        if (!streamOpen) return;
        setHandler(handler);
        int ans = hs_close_stream(stream, scratch, funcPtr, MemorySegment.NULL);
        if (ans != HS_SUCCESS.getCode()) {
            throw new VectorscanException(ans);
        }
        stream = MemorySegment.NULL;
        streamOpen = false;
    }

    /**
     * Closes this scanner, releasing all native resources.
     *
     * <p>If the stream is still open, it is closed first (any end-of-stream matches are discarded).
     * After this call, the scanner must not be used again.
     */
    @Override
    public void close() {
        if (streamOpen) {
            closeStream((_, _, _) -> false);
        }
        super.close();
    }

    /**
     * Returns whether the native stream is currently open.
     *
     * @return {@code true} if a stream is open and ready for scanning, {@code false} otherwise
     */
    public boolean isStreamOpen() {
        return streamOpen;
    }

    /**
     * Scans {@code data} using a <em>native</em> match-event callback supplied by the caller.
     *
     * <p>Unlike {@link #scan(MemorySegment, MatchHandler)}, this method passes the caller-provided
     * native function pointer directly to vectorscan, so matches do <strong>not</strong> incur an
     * upcall back into Java.
     *
     * @param data memory region containing the bytes to scan; {@code byteSize()} must fit into a Java
     *     {@code int}
     * @param handler typed wrapper around the native callback and its opaque context
     * @throws IllegalArgumentException if {@code data.byteSize()} exceeds {@link Integer#MAX_VALUE}
     *     or {@code handler} is {@code null}
     * @throws IllegalStateException if the underlying {@link Database} has been closed or stream is
     *     currently closed
     * @throws VectorscanException if the native scan call returns an error other than
     *     {@link com.dynatrace.vectorscan4j.constants.ErrorCode#HS_SCAN_TERMINATED HS_SCAN_TERMINATED}
     */
    @Override
    public void scan(MemorySegment data, NativeMatchHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (!streamOpen) {
            throw new IllegalStateException("Stream is closed. Open a new stream first.");
        }
        if (data.byteSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Input MemorySegment is too big.");
        }
        if (database.isClosed()) {
            throw new IllegalStateException("Database was already closed.");
        }
        int ans = hs_scan_stream(stream, data, (int) data.byteSize(), 0, scratch, handler.fnPtr(), handler.context());
        if (ans != HS_SUCCESS.getCode() && ans != HS_SCAN_TERMINATED.getCode()) {
            throw new VectorscanException(ans);
        }
    }
}
