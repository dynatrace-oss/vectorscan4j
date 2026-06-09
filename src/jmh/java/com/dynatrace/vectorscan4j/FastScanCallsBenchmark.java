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

import static com.dynatrace.vectorscan4j.constants.ExecutionMode.BLOCK_MODE;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Benchmark for workloads with a large number of tiny scan calls.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 5)
@Fork(1)
public class FastScanCallsBenchmark {
    @Benchmark
    public void byteBuffer(BenchmarkState state, CounterState cstate) {
        state.input.clear();
        state.scanner.scan(state.input, cstate.countMatches);
        cstate.MBperSecond += (double) state.inputSize / (1024.0 * 1024.0);
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class CounterState {
        public double MBperSecond;
        public long matchesPerSecond;

        final OnMatchEventHandler countMatches = ((_, _, _, _) -> {
            matchesPerSecond += 1;
            return true;
        });
    }

    @State(Scope.Thread)
    public static class BenchmarkState {
        @Param({"256", "16384", "65536"})
        public int inputSize;

        @Param({"false", "true"})
        public boolean inputBufferIsDirect;

        @Param({"false", "true"})
        public boolean inputHasMatches;

        BlockScanner scanner;
        Database db;
        ByteBuffer input;

        @Setup(Level.Trial)
        public void setup() {
            db = new Database(List.of(new Expression("a")), BLOCK_MODE);
            scanner = new BlockScanner(db);

            String payload = "z".repeat(inputSize - 1);
            payload += inputHasMatches ? "a" : "z";
            byte[] inputBytes = payload.getBytes(StandardCharsets.US_ASCII);
            input = inputBufferIsDirect
                    ? ByteBuffer.allocateDirect(inputBytes.length)
                    : ByteBuffer.allocate(inputBytes.length);
            input.put(inputBytes);
            input.flip();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            scanner.close();
            db.close();
        }
    }
}
