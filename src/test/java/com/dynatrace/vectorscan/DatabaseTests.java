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
package com.dynatrace.vectorscan;

import static com.dynatrace.vectorscan.constants.ExecutionMode.BLOCK_MODE;
import static com.dynatrace.vectorscan.constants.ExecutionMode.STREAM_MODE;
import static com.dynatrace.vectorscan.constants.Flags.CASELESS;
import static com.dynatrace.vectorscan.constants.Flags.SOM_LEFTMOST;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

import com.dynatrace.vectorscan.constants.ExecutionMode;
import com.dynatrace.vectorscan.constants.Flags;
import com.dynatrace.vectorscan.internal.NativeLoader;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

public class DatabaseTests {
    static final List<Expression> expressions = Arrays.asList(
            new Expression("pattern1", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Expression("pattern2", EnumSet.of(SOM_LEFTMOST, CASELESS)),
            new Expression("pattern3", EnumSet.of(SOM_LEFTMOST, CASELESS)));
    static int nMatches = 0;

    private final OnMatchEventHandler countMatch = (_, _, _, _) -> {
        nMatches += 1;
        return true;
    };

    @Test
    void executionModes() {
        var dbBlock = new Database(expressions, BLOCK_MODE);
        var dbStream = new Database(expressions, STREAM_MODE);
        dbBlock.close();
        dbStream.close();
    }

    @Test
    void emptyExpressions() {
        // empty expression lists throw an exception
        assertThrows(RuntimeException.class, () -> new Database(new ArrayList<>(), BLOCK_MODE).close());
    }

    @Test
    void invalidPatterns() {
        // invalid expressions throw an exception
        List<Expression> invalidExpr = List.of(new Expression(
                "* is a quantifier with nothing in front of it -> should throw exception",
                EnumSet.noneOf(Flags.class)));
        assertThrows(RuntimeException.class, () -> {
            var db = new Database(invalidExpr, BLOCK_MODE);
            db.close();
        });
    }

    @Test
    void getExpression() {
        try (Database database = new Database(expressions, ExecutionMode.BLOCK_MODE)) {
            Expression e = database.getExpression(2);
            assertEquals("pattern3", e.pattern());
            assertEquals(EnumSet.of(SOM_LEFTMOST, CASELESS), e.flags());
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> database.getExpression(-1));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> database.getExpression(expressions.size()));
        }
    }

    @Test
    void modifyExpressions() {
        List<Expression> expr = Arrays.asList(
                new Expression("pat1", EnumSet.noneOf(Flags.class)),
                new Expression("pat2", EnumSet.noneOf(Flags.class)));
        try (Database database = new Database(expr, ExecutionMode.BLOCK_MODE)) {
            IO.println(database.getExpression(0));
            expr.set(0, new Expression("pat3", EnumSet.noneOf(Flags.class)));
            IO.println(database.getExpression(0));
        }
    }

    @Test
    void serializeDeserializeDatabase() {
        String input = "We have pat1 and pat2 int this input string.";
        List<Expression> expressions = List.of(
                new Expression("pat1", EnumSet.noneOf(Flags.class)),
                new Expression("pat2", EnumSet.noneOf(Flags.class)));

        try (Database database = new Database(expressions, ExecutionMode.BLOCK_MODE)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            database.serialize(bos);
            byte[] payload = bos.toByteArray();
            ByteArrayInputStream bis = new ByteArrayInputStream(payload);
            Database roundTripDb = Database.deserialize(bis);
            assertEquals(database.getSize(), roundTripDb.getSize());
            assertEquals(database.getInfo(), roundTripDb.getInfo());
            assertEquals(database.getNumExpressions(), roundTripDb.getNumExpressions());
            assertEquals(database.getMode(), roundTripDb.getMode());
            assertEquals(database.getExpression(1), roundTripDb.getExpression(1));

            BlockScanner scanner = new BlockScanner(database);
            BlockScanner roundTripScanner = new BlockScanner(roundTripDb);
            nMatches = 0;
            scanner.scan(input, countMatch);
            int scanner1matches = nMatches;

            nMatches = 0;
            roundTripScanner.scan(input, countMatch);
            int scanner2matches = nMatches;
            assertEquals(scanner1matches, scanner2matches);
        }
    }

    @Test
    void serializeDeserializeStreamDatabaseFromByteArr() {
        String input = "We have pat1 and pat2 int this input string.";
        List<Expression> expressions = List.of(
                new Expression("pat1", EnumSet.noneOf(Flags.class)),
                new Expression("pat2", EnumSet.noneOf(Flags.class)));

        try (Database database = new Database(expressions, STREAM_MODE);
                Database roundTripDb = Database.deserialize(database.serialize())) {
            assertEquals(database.getSize(), roundTripDb.getSize());
            assertEquals(database.getInfo(), roundTripDb.getInfo());
            assertEquals(database.getNumExpressions(), roundTripDb.getNumExpressions());
            assertEquals(database.getMode(), roundTripDb.getMode());
            assertEquals(database.modeNative, roundTripDb.modeNative);
            assertEquals(database.getExpression(1), roundTripDb.getExpression(1));

            StreamScanner scanner = new StreamScanner(database);
            StreamScanner roundTripScanner = new StreamScanner(roundTripDb);
            nMatches = 0;
            scanner.scan(input, countMatch);
            int scanner1matches = nMatches;

            nMatches = 0;
            roundTripScanner.scan(input, countMatch);
            int scanner2matches = nMatches;
            assertEquals(scanner1matches, scanner2matches);
        }
    }

    @Test
    void serializeThrowsUncheckedIOException() throws IOException {
        List<Expression> expressions = List.of(new Expression("test"));
        try (Database db = new Database(expressions, BLOCK_MODE);
                // Create a custom OutputStream that fails during write
                OutputStream failingStream = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("Simulated write failure");
                    }
                }) {
            assertThrows(UncheckedIOException.class, () -> db.serialize(failingStream));
        }
    }

    @Test
    void deserializeThrowsUncheckedIOException() throws IOException {
        // Create a custom OutputStream that fails during write
        try (InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Simulated read failure");
            }
        }) {
            assertThrows(UncheckedIOException.class, () -> Database.deserialize(failingStream));
        }
    }

    @Test
    void deserializeWrapsClassNotFoundException() {
        List<Expression> exprs = List.of(new Expression("pat1"));

        byte[] bytes;
        try (Database db = new Database(exprs, BLOCK_MODE)) {
            bytes = db.serialize();
        }

        byte[] original = "com.dynatrace.vectorscan.Expression".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] patched = "com.dynatrace.vectorscan.ExpressioX".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(original.length, patched.length);

        int pos = indexOf(bytes, original);
        assertTrue(pos >= 0, "Serialized class name not found in payload");

        System.arraycopy(patched, 0, bytes, pos, patched.length);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            Database db = Database.deserialize(bytes);
            db.close();
        });
        assertInstanceOf(ClassNotFoundException.class, ex.getCause());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    void deserializeInvalidByteSequence() throws IOException, ClassNotFoundException {
        try (Database db = new Database(expressions, BLOCK_MODE)) {
            byte[] dbBytes = db.serialize();

            // in order to make db invalid, we first figure out the serialized database's byte offset
            ByteArrayInputStream bis = new ByteArrayInputStream(dbBytes);
            DataInputStream dis = new DataInputStream(bis);
            ObjectInputStream ois = new ObjectInputStream(bis);

            // same read order as Database.deserialize(...)
            int nExpressions = dis.readInt();
            for (int i = 0; i < nExpressions; i++) {
                ois.readObject();
            }
            dis.readInt(); // mode ordinal
            int nativeOffset = dbBytes.length - bis.available(); // first byte of serialized native DB

            // then we flip the first native db byte
            dbBytes[nativeOffset] = (byte) ~dbBytes[nativeOffset];

            assertThrows(VectorscanException.class, () -> {
                var roundTripDb = Database.deserialize(dbBytes);
                roundTripDb.close();
            });
        }
    }

    @Test
    void closeDatabaseIsIdempotent() {
        Database db = new Database(expressions, BLOCK_MODE);
        db.close();
        assertDoesNotThrow(db::close);
    }

    @Test
    void loadNonexistentNativeLibrary() {
        assertThrows(RuntimeException.class, () -> NativeLoader.load("nonexistent"));
    }

    @Test
    void serializeAfterClose() {
        Database db = new Database(expressions, STREAM_MODE);
        db.close();
        assertThrows(IllegalStateException.class, db::serialize);
    }

    @Test
    void expressionsAreDefensivelyCopied() {
        List<Expression> mutable = new ArrayList<>(List.of(new Expression("pat1"), new Expression("pat2")));

        try (Database db = new Database(mutable, BLOCK_MODE)) {
            // Mutate the original list after database creation
            mutable.set(0, new Expression("REPLACED"));
            mutable.add(new Expression("pat3"));

            // The database should still reflect the original expressions
            assertEquals(2, db.getNumExpressions());
            assertEquals("pat1", db.getExpression(0).pattern());
            assertEquals("pat2", db.getExpression(1).pattern());
        }
    }

    @Test
    void operationsOnInvalidDb() {
        try (Database db = new Database(expressions, STREAM_MODE)) {
            // flip the first byte in the native database, to make the encoded DB invalid
            byte b0 = db.dbNative.get(JAVA_BYTE, 0);
            db.dbNative.set(JAVA_BYTE, 0, (byte) (b0 ^ 0xFF));

            // then, call all the vectorscan native methods
            assertThrows(VectorscanException.class, db::getSize);
            assertThrows(VectorscanException.class, db::getInfo);
            assertThrows(VectorscanException.class, db::serialize);

            // flip the first byte back, to make the encoded DB valid again
            db.dbNative.set(JAVA_BYTE, 0, b0);

            assertDoesNotThrow(db::getSize);
            assertDoesNotThrow(db::getInfo);
            assertDoesNotThrow(() -> db.serialize());
        }
    }
}
