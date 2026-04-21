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
package com.dynatrace.vectorscan.constants;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum ErrorCode {
    /** The engine completed normally. */
    HS_SUCCESS(0),

    /** A parameter passed to this function was invalid. */
    HS_INVALID(-1),

    /** A memory allocation failed. */
    HS_NOMEM(-2),

    /**
     * The engine was terminated by callback. This return value indicates that the target buffer was
     * partially scanned, but that the callback function requested that scanning cease after a match
     * was located.
     */
    HS_SCAN_TERMINATED(-3),

    /** The pattern compiler failed, and the compile error should be inspected. */
    HS_COMPILER_ERROR(-4),

    /** The given database was built for a different version of the Chimera matcher. */
    HS_DB_VERSION_ERROR(-5),

    /** The given database was built for a different platform (i.e., CPU type). */
    HS_DB_PLATFORM_ERROR(-6),

    /**
     * The given database was built for a different mode of operation. This error is returned when
     * streaming calls are used with a non-streaming database and vice versa.
     */
    HS_DB_MODE_ERROR(-7),

    /** A parameter passed to this function was not correctly aligned. */
    HS_BAD_ALIGN(-8),

    /**
     * The memory allocator did not return memory suitably aligned for the largest representable data
     * type on this platform.
     */
    HS_BAD_ALLOC(-9),

    /**
     * The scratch region was already in use. This error is returned when Chimera is able to detect
     * that the scratch region given is already in use by another Chimera API call. A separate scratch
     * region, allocated with {@code ch_alloc_scratch()} or {@code ch_clone_scratch()}, is required
     * for every concurrent caller of the Chimera API. For example, this error might be returned when
     * {@code ch_scan()} has been called inside a callback delivered by a currently-executing {@code
     * ch_scan()} call using the same scratch region.
     *
     * <p>Note: Not all concurrent uses of scratch regions may be detected. This error is intended as
     * a best-effort debugging tool, not a guarantee.
     */
    HS_SCRATCH_IN_USE(-10),

    /** Unsupported CPU architecture. Requires Supplemental Streaming SIMD Extensions 3. */
    HS_ARCH_ERROR(-11),

    /** Provided buffer was too small. */
    HS_INSUFFICIENT_SPACE(-12),

    /**
     * Unexpected internal error from Hyperscan. This error indicates that there was unexpected
     * matching behaviors from Hyperscan. This could be related to invalid usage of scratch space or
     * invalid memory operations by users.
     */
    HS_UNKNOWN_ERROR(-13);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    // reverse lookup table for fast lookups
    private static final Map<Integer, ErrorCode> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(ErrorCode::getCode, Function.identity()));

    public static ErrorCode fromCode(int code) {
        return BY_CODE.get(code);
    }
}
