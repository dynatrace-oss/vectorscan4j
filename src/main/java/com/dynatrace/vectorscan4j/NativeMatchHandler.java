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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
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
public final class NativeMatchHandler implements ScanHandler, AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final FunctionDescriptor INIT_DESC = FunctionDescriptor.of(C_POINTER);
    private static final FunctionDescriptor FREE_DESC = FunctionDescriptor.ofVoid(C_POINTER);

    final MemorySegment nativeOnMatch;
    private final MemorySegment context;
    private final Cleaner.Cleanable cleanable;

    /**
     * Opens {@code libraryPath} and binds the {@code name}-prefixed entry points. The library stays
     * loaded until this handler is closed.
     *
     * @param libraryPath shared library containing the entry points
     * @param name symbol prefix — the handler's class name, for example {@code "Counter"} for
     *     {@code Counter_on_match}
     * @throws NullPointerException if {@code libraryPath} or {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank, if the library cannot be opened,
     *     or if {@code name_on_match} cannot be resolved
     */
    public NativeMatchHandler(Path libraryPath, String name) {
        Objects.requireNonNull(libraryPath, "libraryPath must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            // A blank prefix could only ever resolve "_on_match", which is a reserved identifier
            // and cannot be produced by VS4J_REGISTER_HANDLER. It always means a lost config value.
            throw new IllegalArgumentException("name must not be blank");
        }
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            this.nativeOnMatch = resolveOnMatch(lookup, name);
            this.context = callInit(lookup, name);
            this.cleanable = CLEANER.register(this, new CleanupState(freeHandle(lookup, name), context, arena));
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    private static MemorySegment resolveOnMatch(SymbolLookup lookup, String className) {
        return lookup.find(className + "_on_match")
                .orElseThrow(() -> new IllegalArgumentException("Could not resolve required symbol " + className
                        + "_on_match. A native match handler must at least provide an on_match entry point."));
    }

    private static MemorySegment callInit(SymbolLookup lookup, String name) {
        MemorySegment init = lookup.find(name + "_init").orElse(null);
        if (init == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment)
                    Linker.nativeLinker().downcallHandle(init, INIT_DESC).invoke();
        } catch (Throwable t) {
            throw new IllegalStateException("Call to " + name + "_init failed", t);
        }
    }

    private static MethodHandle freeHandle(SymbolLookup lookup, String name) {
        return lookup.find(name + "_free")
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
