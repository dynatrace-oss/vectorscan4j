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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeLoader {
    private NativeLoader() {}

    public static synchronized void load(String libBaseName) {
        try {
            String osName = System.getProperty("os.name");
            String osArch = System.getProperty("os.arch");
            Path resourcePath = buildResourcePath(libBaseName, osName, osArch);

            try (InputStream is = NativeLoader.class.getResourceAsStream(resourcePath.toString())) {
                if (is == null) {
                    throw new RuntimeException("Native library resource not found: " + resourcePath);
                }

                String mappedName = System.mapLibraryName(libBaseName);
                // Keep suffix so Linux/Mac identify file type.
                String suffix = mappedName.contains(".") ? mappedName.substring(mappedName.lastIndexOf('.')) : null;
                Path temp = Files.createTempFile("native-" + libBaseName + "-", suffix);
                temp.toFile().deleteOnExit();

                try (OutputStream osOut = Files.newOutputStream(temp)) {
                    is.transferTo(osOut);
                }
                System.load(temp.toAbsolutePath().toString());
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to load native library \"%s\"", libBaseName), e);
        }
    }

    static Path buildResourcePath(String libBaseName, String osName, String osArch) {
        String mappedName = System.mapLibraryName(libBaseName);
        String os = normalizeOs(osName);
        String arch = normalizeArch(osArch);
        return Path.of(String.format("/native/%s/%s/%s", os, arch, mappedName));
    }

    static String normalizeOs(String osName) {
        String os = osName == null ? "unknown" : osName.toLowerCase();
        if (os.contains("windows")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("linux")) {
            return "linux";
        }
        return os.replace(' ', '_');
    }

    static String normalizeArch(String osArch) {
        String arch = osArch == null ? "unknown" : osArch.toLowerCase();
        return switch (arch) {
            case "x86_64", "amd64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }
}
