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

/**
 * Marker for the ways a scan can deliver matches. Every {@code scan} call takes one of the
 * permitted implementations:
 *
 * <ul>
 *   <li>{@link MatchHandler} — a Java callback invoked for each match
 *   <li>{@link NativeMatchHandler} — a native callback invoked for each match (to avoid JVM upcalls)
 * </ul>
 */
public sealed interface ScanHandler permits MatchHandler, NativeMatchHandler {}
