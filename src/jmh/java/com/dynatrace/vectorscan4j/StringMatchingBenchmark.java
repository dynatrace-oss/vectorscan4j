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
import static com.dynatrace.vectorscan4j.constants.ExecutionMode.STREAM_MODE;

import com.dynatrace.vectorscan4j.constants.Flags;
import com.dynatrace.vectorscan4j.utils.LoadGenerator;
import com.dynatrace.vectorscan4j.utils.RegexGenerator;
import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * A Benchmark for different usage patterns of the vectorscan4j wrapper when doing one full pattern
 * matching scan on a large file, counting the total number of matches.
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 30)
@Warmup(iterations = 1, time = 10)
@Fork(1)
public class StringMatchingBenchmark {
    private static int nMatches;

    private static final OnMatchEventHandler countMatch = ((_, _, _, _) -> {
        nMatches += 1;
        return true;
    });

    @Benchmark
    public void bufferedInputStreamScanBatchByBatch(BenchmarkState state, Blackhole bh) throws IOException {
        nMatches = 0;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(state.inputFile))) {
            byte[] buf = new byte[8192];
            int nread;
            while ((nread = in.read(buf)) != -1) {
                state.streamScanner.scan(buf, 0, nread, countMatch);
            }
            bh.consume(nMatches);
        }
    }

    @Benchmark
    public void bufferedReaderScanLineByLine(BenchmarkState state) throws Exception {
        nMatches = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(state.inputFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                state.blockScanner.scan(line, countMatch);
            }
        }
    }

    @Benchmark
    public void directByteBufferMultipleScans(BenchmarkState state) throws IOException {
        nMatches = 0;
        try (FileInputStream fis = new FileInputStream(state.inputFile);
                FileChannel channel = fis.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
            while ((channel.read(buffer)) > 0) {
                buffer.flip();
                state.streamScanner.scan(buffer, countMatch);
                buffer.clear();
            }
        }
    }

    @Benchmark
    public void memoryMappedByteBufferOneScan(BenchmarkState state) throws IOException {
        nMatches = 0;
        try (FileInputStream fis = new FileInputStream(state.inputFile);
                FileChannel channel = fis.getChannel()) {
            long fileSize = channel.size();
            if (fileSize > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("File too large to map as a single region");
            }
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buffer.rewind();
            state.blockScanner.scan(buffer, countMatch);
        }
    }

    @Benchmark
    public void memoryMappedMemorySegmentOneScan(BenchmarkState state) throws IOException {
        nMatches = 0;
        try (FileInputStream fis = new FileInputStream(state.inputFile);
                FileChannel channel = fis.getChannel();
                Arena arena = Arena.ofConfined()) {
            long fileSize = channel.size();
            if (fileSize > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("File too large to map as a single region");
            }
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
            state.blockScanner.scan(segment, countMatch);
        }
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"100", "10000"})
        public int nExpressions;

        @Param({"10"})
        public int patternSize;

        @Param({"26"})
        public int alphabetSize;

        Database blockDb;
        Database streamDb;
        BlockScanner blockScanner;
        StreamScanner streamScanner;
        final File inputFile = new File("build/resources/jmh/in.txt");

        @Setup(Level.Trial)
        public void setupScanners() {
            var expressions = RegexGenerator.randomExpressions(
                    nExpressions, patternSize, EnumSet.noneOf(Flags.class), alphabetSize);

            blockDb = new Database(expressions, BLOCK_MODE);
            streamDb = new Database(expressions, STREAM_MODE);
            blockScanner = new BlockScanner(blockDb);
            streamScanner = new StreamScanner(streamDb);
            IO.println("Vectorscan4j blockDatabase size: "
                    + blockScanner.database().getSize());
        }

        @Setup(Level.Trial)
        public void setupInput() throws IOException {
            IO.println("Creating random data file...");
            Files.createDirectory(inputFile.toPath().getParent());
            Files.deleteIfExists(inputFile.toPath());
            // Create 1GB input file of random lower-case characters
            try (FileWriter writer = new FileWriter(inputFile)) {
                for (long i = 0; i < 1024 * 1024 * 8; i++) {
                    var chArr = LoadGenerator.randomCharArr(128, alphabetSize);
                    chArr[chArr.length - 1] = '\n';
                    writer.write(chArr);
                }
            }
            IO.println("Creation done.");
        }
    }
}
