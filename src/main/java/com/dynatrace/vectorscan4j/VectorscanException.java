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

import com.dynatrace.vectorscan4j.constants.ErrorCode;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VectorscanException extends RuntimeException {
    // reverse lookup from C++ error code type (a.k.a. int) to vectorscan4j ErrorCode
    private static final Map<Integer, ErrorCode> BY_CODE =
            Arrays.stream(ErrorCode.values()).collect(Collectors.toMap(ErrorCode::getCode, Function.identity()));

    public VectorscanException(int code) {
        super(String.format(
                "Failure in native Vectorscan call. ErrorCode: %s. Consult official Hyperscan/Vectorscan documentation for more information.",
                BY_CODE.get(code)));
    }
}
