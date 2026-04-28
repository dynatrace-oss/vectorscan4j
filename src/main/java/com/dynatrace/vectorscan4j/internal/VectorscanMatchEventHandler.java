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
package com.dynatrace.vectorscan4j.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

public final class VectorscanMatchEventHandler {
    /** The function pointer signature, expressed as a functional interface */
    public interface Function {
        int apply(int id, long from, long to, int flags, MemorySegment context);
    }

    private static final FunctionDescriptor $DESC = FunctionDescriptor.of(
            VectorscanNative.C_INT,
            VectorscanNative.C_INT,
            VectorscanNative.C_LONG_LONG,
            VectorscanNative.C_LONG_LONG,
            VectorscanNative.C_INT,
            VectorscanNative.C_POINTER);

    private static final MethodHandle UP$MH = VectorscanNative.upcallHandle(Function.class, "apply", $DESC);

    /**
     * Allocates a new upcall stub, whose implementation is defined by {@code fi}. The lifetime of the
     * returned segment is managed by {@code arena}
     */
    public static MemorySegment allocate(Function fi, Arena arena) {
        return Linker.nativeLinker().upcallStub(UP$MH.bindTo(fi), $DESC, arena);
    }
}
