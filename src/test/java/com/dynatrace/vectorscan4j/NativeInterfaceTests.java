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

import static com.dynatrace.vectorscan4j.internal.VectorscanNative.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

public class NativeInterfaceTests {
    @Test
    void invalidMemorySegments() {
        Arena arena = Arena.ofConfined();
        MemorySegment seg = arena.allocateFrom("seg data");
        arena.close();

        // passing already closed MemorySegment to the native handles throws a RuntimeException
        assertThrows(IllegalStateException.class, () -> hs_free_database(seg));
        assertThrows(IllegalStateException.class, () -> hs_serialize_database(seg, seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_deserialize_database(seg, 0, seg));
        assertThrows(IllegalStateException.class, () -> hs_database_size(seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_database_info(seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_compile_multi(seg, seg, seg, 0, 0, seg, seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_free_compile_error(seg));
        assertThrows(IllegalStateException.class, () -> hs_open_stream(seg, 0, seg));
        assertThrows(IllegalStateException.class, () -> hs_scan_stream(seg, seg, 0, 0, seg, seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_close_stream(seg, seg, seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_reset_stream(seg, 0, seg, seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_scan(seg, seg, 0, 0, seg, seg, seg));
        assertThrows(IllegalStateException.class, () -> hs_alloc_scratch(seg, seg));
    }
}
