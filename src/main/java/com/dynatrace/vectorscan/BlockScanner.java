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
package com.dynatrace.vectorscan;

import static com.dynatrace.vectorscan.constants.ErrorCode.HS_SCAN_TERMINATED;
import static com.dynatrace.vectorscan.constants.ErrorCode.HS_SUCCESS;
import static com.dynatrace.vectorscan.constants.ExecutionMode.BLOCK_MODE;
import static com.dynatrace.vectorscan.internal.VectorscanNative.hs_scan;

import com.dynatrace.vectorscan.constants.ErrorCode;
import java.lang.foreign.MemorySegment;

public class BlockScanner extends Scanner {
    public BlockScanner(Database db) {
        if (db.getMode() != BLOCK_MODE) {
            throw new IllegalArgumentException("Database must have been compiled in block mode");
        }
        super(db);
    }

    /**
     * Scans bytes from the provided {@link MemorySegment} without copying them into an intermediate
     * buffer.
     *
     * <p>This is an advanced, zero-copy entry point intended for callers that manage off-heap or
     * foreign memory explicitly. The supplied {@code data} segment must remain alive and accessible
     * for the entire duration of this synchronous call.
     *
     * @param data memory region containing the bytes to scan
     * @param handler callback invoked for each match; return {@code true} to continue scanning,
     *     {@code false} to stop early
     */
    public void scan(MemorySegment data, OnMatchEventHandler handler) {
        if (data.byteSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Input MemorySegment is too big.");
        }
        if (database.isClosed()) {
            throw new IllegalStateException("Database was already closed.");
        }
        setHandler(handler);
        var ctx = MemorySegment.NULL;
        int ans = hs_scan(this.database().dbNative, data, (int) data.byteSize(), 0, scratch, funcPtr, ctx);
        ErrorCode errorCode = ErrorCode.fromCode(ans);
        if (!errorCode.equals(HS_SUCCESS) && !errorCode.equals(HS_SCAN_TERMINATED)) {
            throw new VectorscanException(errorCode);
        }
    }
}
