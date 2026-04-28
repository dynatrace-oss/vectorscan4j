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

/**
 * Java wrapper for Vectorscan, a high-performance multi-regex pattern matching engine.
 *
 * <p>This module provides access to pattern compilation and scanning capabilities through:
 *
 * <ul>
 *   <li>{@link com.dynatrace.vectorscan4j.Database} - for compiling patterns into a searchable
 *       database
 *   <li>{@link com.dynatrace.vectorscan4j.BlockScanner} - for stateless (block) pattern matching
 *   <li>{@link com.dynatrace.vectorscan4j.StreamScanner} - for stateful (streaming) pattern matching
 *   <li>{@link com.dynatrace.vectorscan4j.constants} - for execution modes, pattern flags, and error
 *       codes
 * </ul>
 */
module com.dynatrace.vectorscan4j {
    exports com.dynatrace.vectorscan4j;
    exports com.dynatrace.vectorscan4j.constants;

    requires java.base;
}
