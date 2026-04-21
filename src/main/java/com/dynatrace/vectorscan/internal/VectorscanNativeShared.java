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

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class VectorscanNativeShared {

    VectorscanNativeShared() {
        // Should not be called directly
    }

    public static final OfByte C_CHAR =
            (OfByte) Linker.nativeLinker().canonicalLayouts().get("char");
    public static final OfInt C_INT =
            (OfInt) Linker.nativeLinker().canonicalLayouts().get("int");
    public static final OfLong C_LONG_LONG =
            (OfLong) Linker.nativeLinker().canonicalLayouts().get("long long");
    public static final AddressLayout C_POINTER = ((AddressLayout)
                    Linker.nativeLinker().canonicalLayouts().get("void*"))
            .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, C_CHAR));
    public static final OfLong C_LONG =
            (OfLong) Linker.nativeLinker().canonicalLayouts().get("long");

    static MethodHandle upcallHandle(Class<?> fi, String name, FunctionDescriptor fdesc) {
        try {
            return MethodHandles.lookup().findVirtual(fi, name, fdesc.toMethodType());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
