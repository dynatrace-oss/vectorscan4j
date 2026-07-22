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

import com.dynatrace.vectorscan4j.constants.Flags;

/**
 * Callback invoked by vectorscan for each reported pattern match.
 *
 * <p>Implementations receive match metadata (pattern id and offsets) and decide whether scanning
 * should continue.
 */
@FunctionalInterface
public interface MatchHandler {

    /**
     * Called for every match found during a scan.
     *
     * @param id id of the matched expression (pattern)
     * @param from start byte offset of the match in the scanned input; this is only populated when
     *     the expression uses {@link Flags#SOM_LEFTMOST}, otherwise it is typically {@code 0}
     * @param to end byte offset of the match in the scanned input
     * @return {@code true} to continue scanning, {@code false} to stop scanning early
     */
    boolean onMatch(int id, long from, long to);
}
