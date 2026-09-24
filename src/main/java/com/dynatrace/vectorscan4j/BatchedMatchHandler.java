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
 * Same as MatchHandler, except the BulkMatchHandler one will collect a native buffer of matches, and then handle
 * all those matches at once.
 * Use this when your scans produce a large number of matches, and you want to save on the native-to-Java upcall overhead.
 * Important note: This delays the handling of any one match until its batch has been collected, meaning
 * the check for any early stopping condition will also be delayed. If your workload often stops your scanning early,
 * this would likely run slower compared to using the default MatchHandler.
 * @param batchSize how many matches to collect before handling all at once.
 * @param handler how to handle a whole batch of collected matches.
 */
public record BatchedMatchHandler(int batchSize, BatchHandler handler) implements ScanHandler {}
