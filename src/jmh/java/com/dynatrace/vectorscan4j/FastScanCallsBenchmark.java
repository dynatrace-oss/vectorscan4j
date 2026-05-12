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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark for workloads with a very high number of tiny scan calls.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(1)
public class FastScanCallsBenchmark {

    @Benchmark
    public void manySmallByteArrayScans(BenchmarkState state, Blackhole bh) {
        state.resetMatches();
        for (int i = 0; i < state.scansPerInvocation; i++) {
            state.scanner.scan(state.inputBytes, state.countMatches);
        }
        bh.consume(state.matches);
    }

    @Benchmark
    public void manySmallDirectByteBufferScans(BenchmarkState state, Blackhole bh) {
        state.resetMatches();
        for (int i = 0; i < state.scansPerInvocation; i++) {
            state.inputBuffer.position(0);
            state.inputBuffer.limit(state.inputBuffer.capacity());
            state.scanner.scan(state.inputBuffer, state.countMatches);
        }
        bh.consume(state.matches);
    }

    @Benchmark
    public void manySmallNoMatchByteArrayScans(BenchmarkState state, Blackhole bh) {
        state.resetMatches();
        for (int i = 0; i < state.scansPerInvocation; i++) {
            state.scanner.scan(state.noMatchInputBytes, state.countMatches);
        }
        // Should remain 0 because input is built to contain no 'a'.
        bh.consume(state.matches);
    }

    @State(Scope.Thread)
    public static class BenchmarkState {
        @Param({"10000"})
        public int scansPerInvocation;

        @Param({"128"})
        public int inputSize;

        BlockScanner scanner;
        Database db;
        byte[] inputBytes;
        byte[] noMatchInputBytes;
        ByteBuffer inputBuffer;
        int matches;

        final OnMatchEventHandler countMatches = ((_, _, _, _) -> {
            matches += 1;
            return true;
        });

        @Setup(Level.Trial)
        public void setup() {
            db = new Database(List.of(new Expression("a")), BLOCK_MODE);
            scanner = new BlockScanner(db);

            String payload = "z".repeat(Math.max(1, inputSize - 1)) + "a";
            inputBytes = payload.getBytes(StandardCharsets.US_ASCII);
            noMatchInputBytes = "z".repeat(Math.max(1, inputSize)).getBytes(StandardCharsets.US_ASCII);

            inputBuffer = ByteBuffer.allocateDirect(inputBytes.length);
            inputBuffer.put(inputBytes);
            inputBuffer.flip();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            scanner.close();
            db.close();
        }

        void resetMatches() {
            matches = 0;
        }
    }
}
