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

import static com.dynatrace.vectorscan.constants.ErrorCode.*;
import static com.dynatrace.vectorscan.internal.VectorscanNative.*;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.dynatrace.vectorscan.constants.ErrorCode;
import com.dynatrace.vectorscan.constants.ExecutionMode;
import com.dynatrace.vectorscan.internal.VectorscanCompileError;
import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class Database implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private final Arena arena;
    protected final MemorySegment dbNative;
    protected final int modeNative;
    protected final List<Expression> expressions;
    private final ExecutionMode mode;
    private final Cleaner.Cleanable cleanable;

    private static final class CleanupState implements Runnable {
        private final MemorySegment dbNative;
        private final Arena arena;

        private CleanupState(MemorySegment dbNative, Arena arena) {
            this.dbNative = dbNative;
            this.arena = arena;
        }

        @Override
        public void run() {
            try {
                int _ = hs_free_database(dbNative);
            } catch (Throwable ignored) {
            }
            try {
                arena.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public Database(List<Expression> expressions, ExecutionMode executionMode) {
        this.arena = Arena.ofConfined();
        this.expressions = expressions;
        this.mode = executionMode;
        this.modeNative = executionMode.equals(ExecutionMode.BLOCK_MODE) ? 1 : 2 + (1 << 24);
        // allocate all required function arguments for database compilation as MemorySegments
        int nElems = expressions.size();
        MemorySegment patterns = arena.allocate(C_POINTER, nElems);
        MemorySegment flags = arena.allocate(C_INT, nElems);
        MemorySegment ids = arena.allocate(C_INT, nElems);
        for (int i = 0; i < nElems; i++) {
            Expression expression = expressions.get(i);
            MemorySegment pattern = arena.allocateFrom(expression.pattern());
            patterns.setAtIndex(C_POINTER, i, pattern);
            flags.setAtIndex(C_INT, i, expression.valueOfFlags());
            ids.setAtIndex(C_INT, i, i);
        }
        MemorySegment dbPtr = arena.allocate(C_POINTER);
        MemorySegment errorPtr = arena.allocate(C_POINTER);

        // compile
        var platform = MemorySegment.NULL;
        int ans = hs_compile_multi(patterns, flags, ids, nElems, modeNative, platform, dbPtr, errorPtr);
        ErrorCode errorCode = ErrorCode.fromCode(ans);
        if (errorCode.equals(HS_COMPILER_ERROR)) {
            var compileError = errorPtr.getAtIndex(C_POINTER, 0);
            int exprId = VectorscanCompileError.expression(compileError);
            String msg = VectorscanCompileError.message(compileError).getString(0);
            throw new RuntimeException(String.format("Unable to compile pattern with id %d: %s", exprId, msg));
        }

        // free compile error
        int _ = hs_free_compile_error(errorPtr.getAtIndex(C_POINTER, 0));
        this.dbNative = dbPtr.getAtIndex(C_POINTER, 0);

        cleanable = CLEANER.register(this, new CleanupState(this.dbNative, this.arena));
    }

    private Database(Arena arena, MemorySegment dbNative, List<Expression> expressions, ExecutionMode mode) {
        this.arena = arena;
        this.dbNative = dbNative;
        this.expressions = expressions;
        this.mode = mode;
        this.modeNative = mode.equals(ExecutionMode.BLOCK_MODE) ? 1 : 2 + (1 << 24);

        cleanable = CLEANER.register(this, new CleanupState(this.dbNative, this.arena));
    }

    private byte[] serializeDbNative() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment bytesPtr = temp.allocate(C_POINTER);
            MemorySegment lengthPtr = temp.allocate(C_LONG);
            int ans = hs_serialize_database(dbNative, bytesPtr, lengthPtr);
            ErrorCode errorCode = ErrorCode.fromCode(ans);
            if (!errorCode.equals(HS_SUCCESS)) throw new VectorscanException(errorCode);

            long length = lengthPtr.getAtIndex(JAVA_LONG, 0);
            MemorySegment bytesSeg = bytesPtr.getAtIndex(C_POINTER, 0);

            // copy the data from the memory segment into a byte[]
            ByteBuffer bb = bytesSeg.asSlice(0, length).asByteBuffer();
            bb.position(0);
            byte[] out = new byte[(int) length];
            bb.get(out, 0, (int) length);
            return out;
        }
    }

    public void serialize(OutputStream os) {
        if (isClosed()) {
            throw new IllegalStateException("Trying to serialize an already closed database.");
        }
        try {
            DataOutputStream dos = new DataOutputStream(os);
            ObjectOutputStream oos = new ObjectOutputStream(dos);
            dos.writeInt(expressions.size());
            for (Expression e : expressions) {
                oos.writeObject(e);
            }
            dos.writeInt(mode.ordinal());
            dos.write(serializeDbNative());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] serialize() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        serialize(bos);
        return bos.toByteArray();
    }

    public static Database deserialize(InputStream is) {
        try (Arena temp = Arena.ofConfined()) {
            DataInputStream dis = new DataInputStream(is);
            ObjectInputStream ois = new ObjectInputStream(is);
            int nExpressions = dis.readInt();
            List<Expression> expressions = new ArrayList<>();
            for (int i = 0; i < nExpressions; i++) {
                expressions.add((Expression) ois.readObject());
            }
            ExecutionMode mode = ExecutionMode.values()[(dis.readInt())];
            byte[] dbBytes = is.readAllBytes();

            MemorySegment dbBytesPtr = temp.allocate(dbBytes.length);
            dbBytesPtr.asByteBuffer().put(dbBytes);

            Arena arena = Arena.ofConfined();
            try {
                MemorySegment dbPtr = arena.allocate(C_POINTER);
                int ans = hs_deserialize_database(dbBytesPtr, dbBytes.length, dbPtr);
                ErrorCode errorCode = ErrorCode.fromCode(ans);
                if (!errorCode.equals(HS_SUCCESS)) {
                    throw new VectorscanException(errorCode);
                }
                MemorySegment dbNative = dbPtr.getAtIndex(C_POINTER, 0);
                return new Database(arena, dbNative, expressions, mode);
            } catch (Exception e) {
                arena.close();
                throw e;
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Database deserialize(byte[] dbBytes) {
        ByteArrayInputStream bis = new ByteArrayInputStream(dbBytes);
        return deserialize(bis);
    }

    public long getSize() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment databaseSize = temp.allocate(C_LONG, 1);
            int ans = hs_database_size(this.dbNative, databaseSize);
            ErrorCode errorCode = ErrorCode.fromCode(ans);
            if (!errorCode.equals(HS_SUCCESS)) {
                throw new VectorscanException(errorCode);
            }
            return databaseSize.getAtIndex(JAVA_LONG, 0);
        }
    }

    public String getInfo() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment infoPtr = temp.allocate(C_POINTER, 1);
            int ans = hs_database_info(this.dbNative, infoPtr);
            ErrorCode errorCode = ErrorCode.fromCode(ans);
            if (!errorCode.equals(HS_SUCCESS)) {
                throw new VectorscanException(errorCode);
            }
            MemorySegment databaseInfo = infoPtr.get(C_POINTER, 0);

            // append the individual bytes in databaseInfo to the StringBuilder, until you hit the
            // null-terminator
            StringBuilder builder = new StringBuilder();
            int i = 0;
            byte b;
            while ((b = databaseInfo.getAtIndex(C_CHAR, i++)) != 0) {
                builder.append((char) b);
            }
            return builder.toString();
        }
    }

    public ExecutionMode getMode() {
        return mode;
    }

    public Expression getExpression(int index) {
        return expressions.get(index);
    }

    public int getNumExpressions() {
        return expressions.size();
    }

    public boolean isClosed() {
        return !arena.scope().isAlive();
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
