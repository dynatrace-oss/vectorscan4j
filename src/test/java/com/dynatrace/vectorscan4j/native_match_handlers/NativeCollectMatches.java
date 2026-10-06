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
package com.dynatrace.vectorscan4j.native_match_handlers;

import com.dynatrace.vectorscan4j.NativeMatchHandler;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;

public class NativeCollectMatches extends NativeMatchHandler {
    private static final MemoryLayout CONTEXT_LAYOUT = MemoryLayout.structLayout(
                    ValueLayout.ADDRESS.withName("buffer"),
                    ValueLayout.JAVA_INT.withName("count"),
                    ValueLayout.JAVA_INT.withName("capacity"))
            .withName("context");
    private static final VarHandle COUNT = CONTEXT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("count"));
    private static final VarHandle CAPACITY =
            CONTEXT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("capacity"));

    public NativeCollectMatches(Path libraryPath) {
        super(libraryPath);
    }

    public int count() {
        return (int) COUNT.get(context(), 0L);
    }

    public int capacity() {
        return (int) CAPACITY.get(context(), 0L);
    }

    public int getInt(String name) {
        return (int) CONTEXT_LAYOUT
                .varHandle(MemoryLayout.PathElement.groupElement(name))
                .get(context(), 0L);
    }
}
