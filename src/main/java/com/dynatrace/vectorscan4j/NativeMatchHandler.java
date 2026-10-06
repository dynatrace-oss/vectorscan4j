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

import static com.dynatrace.vectorscan4j.internal.VectorscanNativeShared.C_POINTER;

import com.dynatrace.vectorscan4j.internal.VectorscanNative;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A <em>native</em> vectorscan match-event callback, bound to a native object that owns its own state.
 *
 * <p>The exact signatures, how to write and build a handler, and the constraints that apply are
 * documented in
 * <a href="https://github.com/dynatrace-oss/vectorscan4j/blob/main/docs/native-match-handlers.md">
 * docs/native-match-handlers.md</a>.
 */
public non-sealed class NativeMatchHandler implements ScanHandler, AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    public static final FunctionDescriptor INIT_DESC =
            FunctionDescriptor.of(VectorscanNative.C_POINTER, VectorscanNative.C_POINTER);
    private static final FunctionDescriptor FREE_DESC = FunctionDescriptor.ofVoid(C_POINTER);

    final MemorySegment nativeOnMatch;
    private final MemorySegment context;
    private final Cleaner.Cleanable cleanable;

    public NativeMatchHandler(Path libraryPath) {
        this(libraryPath, MemorySegment.NULL);
    }

    public NativeMatchHandler(Path libraryPath, MemorySegment initCtx) {
        Objects.requireNonNull(libraryPath, "libraryPath must not be null");
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            this.nativeOnMatch = resolveOnMatch(lookup);
            this.context = callInit(lookup, arena, initCtx);
            this.cleanable = CLEANER.register(this, new CleanupState(freeHandle(lookup), context, arena));
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    private static MemorySegment resolveOnMatch(SymbolLookup lookup) {
        return lookup.find("on_match")
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Could not resolve required symbol on_match. A native match handler must at least provide an on_match entry point."));
    }

    private static MemorySegment callInit(SymbolLookup lookup, Arena arena, MemorySegment initCtx) {
        MemorySegment init = lookup.find("init").orElse(null);
        if (init == null) {
            return MemorySegment.NULL;
        }
        MemorySegment raw;
        try {
            // call the correct constructor, based on whether there exists an init arg or not
            raw = (MemorySegment)
                    Linker.nativeLinker().downcallHandle(init, INIT_DESC).invokeExact(initCtx);
        } catch (Throwable t) {
            throw new IllegalStateException("Call to init failed", t);
        }
        if (raw.equals(MemorySegment.NULL)) {
            return raw;
        }
        // Tie the raw MemorySegment to the same arena that owns this handler's lifecycle.
        // => use-after-close() fails fast with IllegalStateException.
        return raw.reinterpret(Long.MAX_VALUE, arena, null);
    }

    private static MethodHandle freeHandle(SymbolLookup lookup) {
        return lookup.find("close")
                .map(addr -> Linker.nativeLinker().downcallHandle(addr, FREE_DESC))
                .orElse(null);
    }

    /**
     * Returns the native object this handler scans with.
     *
     * @return the object returned by {@code _init}, or {@link MemorySegment#NULL} if the library has no
     *     {@code _init}
     */
    public MemorySegment context() {
        return context;
    }

    public void preScan() {}

    public void postScan() {}

    /** Runs {@code _free} (if present) and releases the library. Idempotent. */
    @Override
    public void close() {
        cleanable.clean();
    }

    private record CleanupState(MethodHandle freeHandle, MemorySegment context, Arena arena) implements Runnable {
        @Override
        public void run() {
            if (freeHandle != null) {
                try {
                    freeHandle.invoke(context);
                } catch (Throwable ignored) {
                }
            }
            try {
                arena.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
