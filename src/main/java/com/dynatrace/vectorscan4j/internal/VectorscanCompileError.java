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

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.OfInt;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;

public class VectorscanCompileError {

    private static final GroupLayout $LAYOUT = MemoryLayout.structLayout(
                    VectorscanNative.C_POINTER.withName("message"),
                    VectorscanNative.C_INT.withName("expression"),
                    MemoryLayout.paddingLayout(4))
            .withName("hs_compile_error");

    private static final AddressLayout message$LAYOUT = (AddressLayout) $LAYOUT.select(groupElement("message"));

    private static final long message$OFFSET = $LAYOUT.byteOffset(groupElement("message"));

    public static MemorySegment message(MemorySegment struct) {
        return struct.get(message$LAYOUT, message$OFFSET);
    }

    private static final OfInt expression$LAYOUT = (OfInt) $LAYOUT.select(groupElement("expression"));

    private static final long expression$OFFSET = $LAYOUT.byteOffset(groupElement("expression"));

    public static int expression(MemorySegment struct) {
        return struct.get(expression$LAYOUT, expression$OFFSET);
    }
}
