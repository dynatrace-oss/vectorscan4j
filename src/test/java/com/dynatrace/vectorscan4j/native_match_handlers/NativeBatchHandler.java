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
package com.dynatrace.vectorscan4j.native_match_handlers;

import com.dynatrace.vectorscan4j.BatchHandler;
import com.dynatrace.vectorscan4j.BatchedMatchHandler;
import com.dynatrace.vectorscan4j.NativeMatchHandler2;
import com.dynatrace.vectorscan4j.internal.VectorscanBatchedMatchHandler;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public class NativeBatchHandler extends NativeMatchHandler2 {
    private BatchHandler batchHandler;
    protected final BatchCallHandler batchCallHandler = new BatchCallHandler();

    private int batchSize;

    public NativeBatchHandler(Path libraryPath, int batchSize) {
        this.batchSize = batchSize;
        Arena temp = Arena.ofConfined();
        super(libraryPath, temp.allocateFrom(ValueLayout.JAVA_INT, batchSize));
        temp.close();
    }

    private NativeBatchHandler(Path libraryPath) {
        super(libraryPath);
    }

    static class BatchCallHandler implements VectorscanBatchedMatchHandler.Function {
        BatchedMatchHandler batchedMatchHandler;
        ByteBuffer batchBuffer;

        @Override
        public int apply(MemorySegment ignored, int count) {
            return batchedMatchHandler.handler().matchAll(batchBuffer, count) ? 0 : 1;
        }
    }

    @Override
    public void preScan() {}

    @Override
    public void postScan() {}
}
