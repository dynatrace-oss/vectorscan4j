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

import java.nio.ByteBuffer;

/**
 * Handles a whole batch of collected matches at once. Wrapped by {@link BatchedMatchHandler}, which pairs it
 * with the batch size to use.
 *
 * <p>The buffer holds {@code nMatches} entries of 12 bytes each: the pattern id, the start offset and the
 * exclusive end offset, in that order, as 32-bit values. It points at scanner-owned native memory and is
 * only valid for the duration of the call.
 */
@FunctionalInterface
public interface BatchHandler {
    /**
     * @param buf the collected matches, in FIFO order
     * @param nMatches how many matches {@code buf} holds
     * @return {@code true} to continue scanning, {@code false} to stop early
     */
    boolean matchAll(ByteBuffer buf, int nMatches);
}
