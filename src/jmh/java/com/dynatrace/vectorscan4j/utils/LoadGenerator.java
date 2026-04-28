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
package com.dynatrace.vectorscan4j.utils;

import java.util.Random;

public class LoadGenerator {
    private LoadGenerator() {}

    private static final Random random = new Random(0L);

    public static char[] randomCharArr(int length) {
        return randomCharArr(length, 26);
    }

    public static char[] randomCharArr(int length, int alphabetSize) {
        char[] buf = new char[length];
        final int base = 'a';
        for (int i = 0; i < length; i++) {
            buf[i] = (char) (base + random.nextInt(alphabetSize));
        }
        return buf;
    }
}
