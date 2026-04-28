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
package com.dynatrace.vectorscan4j.constants;

import com.dynatrace.vectorscan4j.BlockScanner;
import com.dynatrace.vectorscan4j.StreamScanner;

/**
 * Selects how vectorscan executes pattern matching.
 *
 * <p>The mode is fixed at database-compilation time and determines which scanner type can use the
 * compiled database: A database that was compiled in BLOCK mode can only instantiate BlockScanners,
 * and STREAM mode for StreamScanners.
 */
public enum ExecutionMode {
    /**
     * Block mode (stateless): each scan starts from the initial matcher state and processes one
     * isolated input buffer.
     *
     * <p>Use this for independent payloads where matches do not need to span multiple scan calls.
     * Databases compiled with this mode are used with {@link BlockScanner}.
     */
    BLOCK_MODE,

    /**
     * Stream mode (stateful): matcher state is preserved across successive scan calls.
     *
     * <p>Use this for chunked/network/file-stream input where matches may cross chunk boundaries.
     * Databases compiled with this mode are used with {@link StreamScanner}.
     */
    STREAM_MODE
}
