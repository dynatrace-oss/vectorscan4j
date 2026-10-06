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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts the native libraries bundled under {@code /native/<os>/<arch>/} on the classpath to temporary files,
 * since a library inside a JAR cannot be loaded in place.
 */
public final class NativeLoader {
    /** Extracted file per library, so each library is copied out at most once per JVM. */
    private static final Map<String, Path> EXTRACTED = new HashMap<>();

    private NativeLoader() {}

    /** Extracts the bundled library and loads it into the JVM, making its symbols visible to {@code loaderLookup()}. */
    public static synchronized void load(String libBaseName) {
        System.load(extract(libBaseName).toAbsolutePath().toString());
    }

    /**
     * Extracts the bundled library for the current platform to a temporary file, without loading it.
     *
     * @param libBaseName the name without platform prefix or suffix, e.g. {@code "native_batch_handler"} for
     *     {@code libnative_batch_handler.so}
     * @return the extracted file, deleted on JVM exit
     * @throws RuntimeException if no library of that name is bundled for this platform, or it cannot be written
     */
    public static synchronized Path extract(String libBaseName) {
        Path cached = EXTRACTED.get(libBaseName);
        if (cached != null) {
            return cached;
        }
        Path resourcePath =
                buildResourcePath(libBaseName, System.getProperty("os.name"), System.getProperty("os.arch"));
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
            EXTRACTED.put(libBaseName, temp);
            return temp;
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to extract native library \"%s\"", libBaseName), e);
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
        if (os.contains("nux") || os.contains("nix")) {
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
