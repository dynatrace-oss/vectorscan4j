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
package com.dynatrace.vectorscan.internal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

public class VectorscanNative extends VectorscanNativeShared {
    static {
        NativeLoader.load("vectorscan");
    }

    static final SymbolLookup SYMBOL_LOOKUP =
            SymbolLookup.loaderLookup().or(Linker.nativeLinker().defaultLookup());

    private static class hs_free_database {
        public static final FunctionDescriptor DESC =
                FunctionDescriptor.of(VectorscanNative.C_INT, VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_free_database");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_free_database(MemorySegment db) {
        var mh$ = hs_free_database.HANDLE;
        try {
            return (int) mh$.invokeExact(db);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_serialize_database {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_serialize_database");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_serialize_database(MemorySegment db, MemorySegment bytes, MemorySegment length) {
        var mh$ = hs_serialize_database.HANDLE;
        try {
            return (int) mh$.invokeExact(db, bytes, length);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_deserialize_database {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_LONG,
                VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_deserialize_database");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_deserialize_database(MemorySegment bytes, long length, MemorySegment db) {
        var mh$ = hs_deserialize_database.HANDLE;
        try {
            return (int) mh$.invokeExact(bytes, length, db);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_database_size {
        public static final FunctionDescriptor DESC =
                FunctionDescriptor.of(VectorscanNative.C_INT, VectorscanNative.C_POINTER, VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_database_size");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_database_size(MemorySegment database, MemorySegment database_size) {
        var mh$ = hs_database_size.HANDLE;
        try {
            return (int) mh$.invokeExact(database, database_size);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_database_info {
        public static final FunctionDescriptor DESC =
                FunctionDescriptor.of(VectorscanNative.C_INT, VectorscanNative.C_POINTER, VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_database_info");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_database_info(MemorySegment database, MemorySegment info) {
        var mh$ = hs_database_info.HANDLE;
        try {
            return (int) mh$.invokeExact(database, info);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_compile_multi {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_INT,
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_compile_multi");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_compile_multi(
            MemorySegment expressions,
            MemorySegment flags,
            MemorySegment ids,
            int elements,
            int mode,
            MemorySegment platform,
            MemorySegment db,
            MemorySegment error) {
        var mh$ = hs_compile_multi.HANDLE;
        try {
            return (int) mh$.invokeExact(expressions, flags, ids, elements, mode, platform, db, error);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_free_compile_error {
        public static final FunctionDescriptor DESC =
                FunctionDescriptor.of(VectorscanNative.C_INT, VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_free_compile_error");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_free_compile_error(MemorySegment error) {
        var mh$ = hs_free_compile_error.HANDLE;
        try {
            return (int) mh$.invokeExact(error);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_free_scratch {
        public static final FunctionDescriptor DESC =
                FunctionDescriptor.of(VectorscanNative.C_INT, VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_free_scratch");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_free_scratch(MemorySegment scratch) {
        var mh$ = hs_free_scratch.HANDLE;
        try {
            return (int) mh$.invokeExact(scratch);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_open_stream {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT, VectorscanNative.C_POINTER, VectorscanNative.C_INT, VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_open_stream");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_open_stream(MemorySegment db, int flags, MemorySegment stream) {
        var mh$ = hs_open_stream.HANDLE;
        try {
            return (int) mh$.invokeExact(db, flags, stream);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static class hs_scan_stream {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_INT,
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_scan_stream");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_scan_stream(
            MemorySegment id,
            MemorySegment data,
            int length,
            int flags,
            MemorySegment scratch,
            MemorySegment onEvent,
            MemorySegment ctxt) {
        var mh$ = hs_scan_stream.HANDLE;
        try {
            return (int) mh$.invokeExact(id, data, length, flags, scratch, onEvent, ctxt);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_close_stream {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_close_stream");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_close_stream(
            MemorySegment id, MemorySegment scratch, MemorySegment onEvent, MemorySegment ctxt) {
        var mh$ = hs_close_stream.HANDLE;
        try {
            return (int) mh$.invokeExact(id, scratch, onEvent, ctxt);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_reset_stream {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_reset_stream");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_reset_stream(
            MemorySegment id, int flags, MemorySegment scratch, MemorySegment onEvent, MemorySegment context) {
        var mh$ = hs_reset_stream.HANDLE;
        try {
            return (int) mh$.invokeExact(id, flags, scratch, onEvent, context);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_scan {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_INT,
                VectorscanNative.C_INT,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER,
                VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_scan");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_scan(
            MemorySegment db,
            MemorySegment data,
            int length,
            int flags,
            MemorySegment scratch,
            MemorySegment onEvent,
            MemorySegment context) {
        var mh$ = hs_scan.HANDLE;
        try {
            return (int) mh$.invokeExact(db, data, length, flags, scratch, onEvent, context);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }

    private static class hs_alloc_scratch {
        public static final FunctionDescriptor DESC =
                FunctionDescriptor.of(VectorscanNative.C_INT, VectorscanNative.C_POINTER, VectorscanNative.C_POINTER);

        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("hs_alloc_scratch");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static int hs_alloc_scratch(MemorySegment db, MemorySegment scratch) {
        var mh$ = hs_alloc_scratch.HANDLE;
        try {
            return (int) mh$.invokeExact(db, scratch);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
}
