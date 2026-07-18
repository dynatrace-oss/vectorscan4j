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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

/**
 * A typed wrapper around a <em>native</em> vectorscan match-event callback.
 *
 * <p>The wrapped {@link #fnPtr} must point at a C function with the exact signature
 * required by vectorscan's {@code match_event_handler}:
 *
 * <pre>{@code
 * int on_match(unsigned int id,
 *              unsigned long long from,
 *              unsigned long long to,
 *              unsigned int flags,
 *              void *context);
 * }</pre>
 *
 * <p>Returning {@code 0} continues scanning; any non-zero value terminates the scan
 * early (vectorscan will then return {@code HS_SCAN_TERMINATED}).
 *
 * <p>This type is the project's "trust boundary" for native callbacks: the JVM cannot
 * verify the function's true ABI from its address, so callers are responsible for
 * ensuring the symbol they resolve actually matches the signature above.
 *
 * @param fnPtr   address of the native callback; must be a valid, non-null function pointer
 * @param context optional opaque pointer passed unchanged to the callback;
 *                use {@link MemorySegment#NULL} if not needed
 */
public record NativeMatchHandler(MemorySegment fnPtr, MemorySegment context) {
    public NativeMatchHandler {
        if (fnPtr == null || fnPtr.equals(MemorySegment.NULL) || fnPtr.address() == 0L) {
            throw new IllegalArgumentException("fnPtr must be a non-null native function pointer");
        }
        if (context == null) {
            context = MemorySegment.NULL;
        }
    }

    /** Resolves {@code symbolName} from {@code lookup} and wraps it. */
    public static NativeMatchHandler fromLookup(SymbolLookup lookup, String symbolName, MemorySegment context) {
        MemorySegment addr = lookup.findOrThrow(symbolName);
        return new NativeMatchHandler(addr, context == null ? MemorySegment.NULL : context);
    }

    /**
     * Convenience that opens a {@link SymbolLookup} on the given shared-library path
     * within {@code arena} and resolves {@code symbolName}.
     */
    public static NativeMatchHandler fromLibrary(
            Path libraryPath, String symbolName, MemorySegment context, Arena arena) {
        SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        return fromLookup(lookup, symbolName, context);
    }
}
