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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NativeLoaderTests {

    @Test
    void normalizeOsMapsKnownFamilies() {
        assertEquals("windows", NativeLoader.normalizeOs("Windows 11"));
        assertEquals("linux", NativeLoader.normalizeOs("Linux"));
        assertEquals("macos", NativeLoader.normalizeOs("Mac OS X"));
        assertEquals("macos", NativeLoader.normalizeOs("Darwin"));
    }

    @Test
    void normalizeOsFallsBackToSanitizedName() {
        assertEquals("freebsd_14.0", NativeLoader.normalizeOs("FreeBSD 14.0"));
        assertEquals("unknown", NativeLoader.normalizeOs(null));
    }

    @Test
    void normalizeArchMapsKnownArchitectures() {
        assertEquals("x86_64", NativeLoader.normalizeArch("amd64"));
        assertEquals("x86_64", NativeLoader.normalizeArch("x86_64"));
        assertEquals("x86", NativeLoader.normalizeArch("i686"));
        assertEquals("aarch64", NativeLoader.normalizeArch("arm64"));
        assertEquals("aarch64", NativeLoader.normalizeArch("aarch64"));
    }

    @Test
    void normalizeArchFallsBackToInput() {
        assertEquals("riscv64", NativeLoader.normalizeArch("riscv64"));
        assertEquals("unknown", NativeLoader.normalizeArch(null));
    }

    @Test
    void buildResourcePathUsesNormalizedSegments() {
        Path p = NativeLoader.buildResourcePath("vectorscan", "Linux", "amd64");
        String normalized = p.toString().replace('\\', '/');
        assertTrue(normalized.startsWith("/native/linux/x86_64/"));
        assertTrue(normalized.endsWith(System.mapLibraryName("vectorscan")));
    }

    @Test
    void loadThrowsHelpfulErrorForMissingResource() {
        String oldOs = System.getProperty("os.name");
        String oldArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Unit Test OS");
            System.setProperty("os.arch", "unit-test-arch");

            RuntimeException ex = assertThrows(RuntimeException.class, () -> NativeLoader.load("definitely_missing"));
            assertTrue(ex.getMessage().contains("Native library resource not found:"));
        } finally {
            if (oldOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", oldOs);
            }
            if (oldArch == null) {
                System.clearProperty("os.arch");
            } else {
                System.setProperty("os.arch", oldArch);
            }
        }
    }
}
