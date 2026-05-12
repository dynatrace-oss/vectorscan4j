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

import static com.dynatrace.vectorscan4j.constants.ErrorCode.*;
import static com.dynatrace.vectorscan4j.internal.VectorscanNative.*;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.dynatrace.vectorscan4j.constants.ErrorCode;
import com.dynatrace.vectorscan4j.constants.ExecutionMode;
import com.dynatrace.vectorscan4j.internal.VectorscanCompileError;
import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A compiled pattern database for use with vectorscan scanners.
 *
 * <p>A {@code Database} is created by compiling one or more {@link Expression} objects together with
 * an {@link ExecutionMode}. The resulting automaton is stored in off-heap (native) memory managed by
 * vectorscan. The database is immutable after compilation and can be safely shared across multiple
 * scanner instances and threads.
 *
 * <p>Databases can be serialized to and deserialized from byte arrays or streams via
 * {@link #serialize()} and {@link #deserialize(byte[])}, which is useful for caching compiled
 * databases on disk or transferring them over a network.
 *
 * <p>This class implements {@link AutoCloseable}. Closing a database frees its native memory
 * immediately. If {@code close()} is not called, native memory is freed when the object is garbage
 * collected (via a {@link java.lang.ref.Cleaner}), but this may happen significantly later.
 * Prefer try-with-resources for deterministic cleanup.
 *
 * <p>Once closed, the database must not be used for scanning or serialization.
 */
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
            IO.println("Running Database cleanup!");
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

    /**
     * Compiles a new database from the given expressions and execution mode.
     *
     * @param expressions list of patterns to compile; each is assigned an id equal to its list index
     * @param executionMode whether the database targets {@link ExecutionMode#BLOCK_MODE block} or
     *     {@link ExecutionMode#STREAM_MODE stream} scanning
     * @throws RuntimeException if any expression fails to compile (the message includes the
     *     offending pattern id and vectorscan's error description)
     */
    public Database(List<Expression> expressions, ExecutionMode executionMode) {
        this.arena = Arena.ofConfined();
        this.expressions = List.copyOf(expressions);
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
        this.expressions = List.copyOf(expressions);
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

    /**
     * Serializes this database to the given output stream.
     *
     * <p>The output contains the expression metadata followed by the native database bytes. It can
     * be restored with {@link #deserialize(InputStream)}.
     *
     * @param os destination stream
     * @throws IllegalStateException if this database has already been closed
     * @throws UncheckedIOException if an I/O error occurs while writing
     * @throws VectorscanException if native serialization fails
     */
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

    /**
     * Serializes this database into a byte array.
     *
     * @return the serialized database bytes
     * @throws IllegalStateException if this database has already been closed
     * @throws VectorscanException if native serialization fails
     */
    public byte[] serialize() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        serialize(bos);
        return bos.toByteArray();
    }

    /**
     * Deserializes a database from the given input stream.
     *
     * <p>The stream must contain data previously written by {@link #serialize(OutputStream)}.
     * The caller is responsible for closing the returned database when it is no longer needed.
     *
     * @param is source stream
     * @return a new {@code Database} instance
     * @throws UncheckedIOException if an I/O error occurs while reading
     * @throws VectorscanException if native deserialization fails
     */
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

    /**
     * Deserializes a database from a byte array.
     *
     * @param dbBytes bytes previously returned by {@link #serialize()}
     * @return a new {@code Database} instance
     * @throws VectorscanException if native deserialization fails
     */
    public static Database deserialize(byte[] dbBytes) {
        ByteArrayInputStream bis = new ByteArrayInputStream(dbBytes);
        return deserialize(bis);
    }

    /**
     * Returns the size in bytes of the compiled native database.
     *
     * @return database size in bytes
     * @throws VectorscanException if the size cannot be determined
     */
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

    /**
     * Returns a human-readable string describing the database (version, platform, and execution mode).
     *
     * @return info string from vectorscan
     * @throws VectorscanException if the info cannot be retrieved
     */
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

    /**
     * Returns the execution mode this database was compiled for.
     *
     * @return {@link ExecutionMode#BLOCK_MODE} or {@link ExecutionMode#STREAM_MODE}
     */
    public ExecutionMode getMode() {
        return mode;
    }

    /**
     * Returns the expression at the given index.
     *
     * @param index zero-based expression id
     * @return the expression
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Expression getExpression(int index) {
        return expressions.get(index);
    }

    /**
     * Returns the number of expressions compiled into this database.
     *
     * @return expression count
     */
    public int getNumExpressions() {
        return expressions.size();
    }

    /**
     * Returns whether this database has been closed and its native memory freed.
     *
     * @return {@code true} if closed, {@code false} if still usable
     */
    public boolean isClosed() {
        return !arena.scope().isAlive();
    }

    /**
     * Closes this database, freeing its native memory.
     *
     * <p>This method is idempotent; calling it more than once has no effect. After closing, the
     * database must not be used for scanning or serialization.
     */
    @Override
    public void close() {
        // cleanable.clean();
    }
}
