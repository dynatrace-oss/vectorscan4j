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
package com.dynatrace.vectorscan;

import static com.dynatrace.vectorscan.constants.ExecutionMode.BLOCK_MODE;
import static com.dynatrace.vectorscan.constants.Flags.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PatternFlagsTests {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<Integer> matchIds(BlockScanner scanner, String input) {
        List<Integer> ids = new ArrayList<>();
        scanner.scan(input, (id, _, _, _) -> {
            ids.add(id);
            return true;
        });
        return ids;
    }

    private static List<Integer> matchIds(BlockScanner scanner, byte[] input) {
        List<Integer> ids = new ArrayList<>();
        scanner.scan(input, (id, _, _, _) -> {
            ids.add(id);
            return true;
        });
        return ids;
    }

    @Test
    void caseless() {
        try (Database dbWith = new Database(List.of(new Expression("hello", EnumSet.of(CASELESS))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression("hello")), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // CASELESS: all three capitalization variants match
            assertEquals(3, matchIds(with, "hello HELLO Hello").size());

            // without CASELESS: only the exact lower-case form matches
            assertEquals(1, matchIds(without, "hello HELLO Hello").size());
        }
    }

    @Test
    void caselessUnicode() {
        // caseless also affects lower/upper-case pairs of non-ASCII characters
        byte[] eAcuteUpper = "É".getBytes(StandardCharsets.UTF_8);

        try (Database dbCaselessOnly = new Database(List.of(new Expression("é", EnumSet.of(CASELESS))), BLOCK_MODE);
                BlockScanner caselessOnly = new BlockScanner(dbCaselessOnly);
                Database dbCaselessUcp =
                        new Database(List.of(new Expression("é", EnumSet.of(CASELESS, UTF8))), BLOCK_MODE);
                BlockScanner caselessUcp = new BlockScanner(dbCaselessUcp)) {

            // CASELESS alone (without UTF8 or UCP) operates in byte/ASCII mode — it only folds [A-Za-z].
            assertTrue(matchIds(caselessOnly, eAcuteUpper).isEmpty());

            // CASELESS | UTF8: UTF8 enables Unicode character properties.
            // Unicode case-folding now applies, so "é" matches "É".
            assertEquals(1, matchIds(caselessUcp, eAcuteUpper).size());
        }
    }

    @Test
    void caselessPCRE() {
        String input = "ABcd abcd ABCD abCD";

        // (?i) enables caseless for "ab" and (?-i) then disables it again for "cd".
        // This should work the same regardless of the global CASELESS compile flag.
        try (Database dbWithCaseFlag =
                        new Database(List.of(new Expression("(?i)ab(?-i)cd", EnumSet.of(CASELESS))), BLOCK_MODE);
                BlockScanner withCaseFlag = new BlockScanner(dbWithCaseFlag);
                Database dbWithoutCaseFlag = new Database(List.of(new Expression("(?i)ab(?-i)cd")), BLOCK_MODE);
                BlockScanner withoutCaseFlag = new BlockScanner(dbWithoutCaseFlag)) {

            // Matches: "ABcd", "abcd".
            // Non-matches: "ABCD", "abCD" because (?-i) enforces case-sensitive "cd".
            assertEquals(2, matchIds(withCaseFlag, input).size());
            assertEquals(2, matchIds(withoutCaseFlag, input).size());
        }
    }

    @Test
    void dotAll() {
        try (Database dbWith = new Database(List.of(new Expression("a.b", EnumSet.of(DOTALL))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression("a.b")), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // DOTALL: "." matches the newline character → "a\nb" matches
            assertEquals(1, matchIds(with, "a\nb").size());

            // without DOTALL: "." does NOT match \n → no match
            assertTrue(matchIds(without, "a\nb").isEmpty());

            // without DOTALL: "." still matches non-newline characters
            assertEquals(1, matchIds(without, "a b").size());
        }
    }

    @Test
    void multiline() {
        try (Database dbWith = new Database(List.of(new Expression("^foo", EnumSet.of(MULTILINE))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression("^foo")), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // "foo" at the very start of the input – both modes match
            assertEquals(1, matchIds(with, "foo bar").size());
            assertEquals(1, matchIds(without, "foo bar").size());

            // "foo" after a newline – only MULTILINE treats the position after \n as a line start
            assertEquals(1, matchIds(with, "bar\nfoo").size());
            assertTrue(matchIds(without, "bar\nfoo").isEmpty());
        }
    }

    @Test
    void singleMatch() {
        try (Database dbWith = new Database(List.of(new Expression("a", EnumSet.of(SINGLEMATCH))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression("a")), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // SINGLEMATCH: only the first match is reported, regardless of further occurrences
            assertEquals(1, matchIds(with, "aaa").size());

            // without SINGLEMATCH: all three occurrences generate a callback
            assertEquals(3, matchIds(without, "aaa").size());
        }
    }

    @Test
    void allowEmpty() {
        // "a*" can match an empty string → compilation fails without HS_FLAG_ALLOWEMPTY
        assertThrows(RuntimeException.class, () -> {
            Database db = new Database(List.of(new Expression("a*")), BLOCK_MODE);
            db.close();
        });

        // with HS_FLAG_ALLOWEMPTY the same pattern compiles and works correctly
        try (Database db = new Database(List.of(new Expression("a*", EnumSet.of(ALLOWEMPTY))), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            assertFalse(matchIds(scanner, "aaa").isEmpty());
        }
    }

    @Test
    void utf8() {
        // "café" in UTF-8: c(1 byte) a(1 byte) f(1 byte) é(2 bytes: 0xC3 0xA9) = 5 bytes total
        byte[] cafe = "café".getBytes(StandardCharsets.UTF_8);
        assertEquals(5, cafe.length);

        try (Database dbWith = new Database(List.of(new Expression(".", EnumSet.of(UTF8))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression(".")), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // UTF8 mode: "." matches each Unicode code point → 4 matches (c, a, f, é)
            assertEquals(4, matchIds(with, cafe).size());

            // byte mode: "." matches each raw byte → 5 matches (é occupies 2 bytes)
            assertEquals(5, matchIds(without, cafe).size());
        }
    }

    @Test
    void ucp() {
        // "é" (U+00E9 LATIN SMALL LETTER E WITH ACUTE) is a Unicode letter, encoded as 2 bytes in UTF-8

        byte[] eAccent = "é".getBytes(StandardCharsets.UTF_8);

        // HS_FLAG_UCP alone is sufficient — it implicitly enables UTF-8 decoding internally
        try (Database dbWith = new Database(List.of(new Expression("\\w", EnumSet.of(UCP))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression("\\w", EnumSet.of(UTF8))), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // UCP: \w uses Unicode properties → matches the Unicode letter "é" (one code point)
            assertEquals(1, matchIds(with, eAccent).size());

            // without UCP: \w is ASCII-only [a-zA-Z0-9_] → "é" does not match
            assertTrue(matchIds(without, eAccent).isEmpty());
        }
    }

    @Test
    void prefilter() {
        // Backreferences are unsupported in vectorscan; compilation fails without PREFILTER
        assertThrows(RuntimeException.class, () -> {
            Database db = new Database(List.of(new Expression("(a)\\1")), BLOCK_MODE);
            db.close();
        });

        // however with PREFILTER, vectorscan compiles a superset approximation:
        // no false negatives – every true match of the original pattern is still reported
        try (Database db = new Database(List.of(new Expression("(a)\\1", EnumSet.of(PREFILTER))), BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            // "aa" is a genuine match of "(a)\1" and must be found by the approximation
            assertEquals(List.of(0), matchIds(scanner, "aa"));
        }
    }

    @Test
    void somLeftmost() {
        // "say hello!" → "hello" starts at byte offset 4 and ends at 9
        try (Database dbWith = new Database(List.of(new Expression("hello", EnumSet.of(SOM_LEFTMOST))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression("hello")), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            List<long[]> withPairs = new ArrayList<>();
            with.scan("say hello!", (_, from, to, _) -> {
                withPairs.add(new long[] {from, to});
                return true;
            });
            assertEquals(1, withPairs.size());
            assertEquals(4, withPairs.getFirst()[0]); // "from" is the actual start of the match
            assertEquals(9, withPairs.getFirst()[1]); // "to" is the exclusive end

            List<long[]> withoutPairs = new ArrayList<>();
            without.scan("say hello!", (_, from, to, _) -> {
                withoutPairs.add(new long[] {from, to});
                return true;
            });
            assertEquals(1, withoutPairs.size());
            assertEquals(0, withoutPairs.getFirst()[0]); // without SOM_LEFTMOST, "from" is always 0
            assertEquals(9, withoutPairs.getFirst()[1]); // "to" is still the correct end offset
        }
    }

    @Test
    void combination() {
        // With COMBINATION: the third expression is a logical AND of the first two by ID.
        List<Expression> withFlag = List.of(
                new Expression("foo", EnumSet.of(QUIET)), // id=0 – suppressed by QUIET
                new Expression("bar", EnumSet.of(QUIET)), // id=1 – suppressed by QUIET
                new Expression("0&1", EnumSet.of(COMBINATION)) // id=2 – fires when BOTH 0 and 1 match
                );
        // Without COMBINATION: "0&1" is treated as the literal string pattern "0&1".
        List<Expression> withoutFlag = List.of(
                new Expression("foo"), // id=0
                new Expression("bar"), // id=1
                new Expression("0&1") // id=2 – matches the literal text "0&1"
                );

        try (Database dbWith = new Database(withFlag, BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(withoutFlag, BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // COMBINATION: "foo bar" satisfies "0&1" → only the combination expression fires
            List<Integer> ids = matchIds(with, "foo bar");
            assertEquals(List.of(2), ids);

            // COMBINATION: only "foo" present → condition not satisfied, nothing fires
            assertTrue(matchIds(with, "only foo here").isEmpty());

            // without COMBINATION: "foo" (id=0) and "bar" (id=1) fire; literal "0&1" does not
            ids = matchIds(without, "foo bar");
            assertEquals(2, ids.size());
            assertTrue(ids.containsAll(List.of(0, 1)));
            assertFalse(ids.contains(2));

            // without COMBINATION: id=2 fires only when the literal string "0&1" appears
            assertTrue(matchIds(without, "0&1").contains(2));
        }
    }

    @Test
    void quiet() {
        try (Database dbWith = new Database(List.of(new Expression("foo", EnumSet.of(QUIET))), BLOCK_MODE);
                BlockScanner with = new BlockScanner(dbWith);
                Database dbWithout = new Database(List.of(new Expression("foo")), BLOCK_MODE);
                BlockScanner without = new BlockScanner(dbWithout)) {

            // QUIET: the pattern matches internally but no match callback is ever generated
            assertTrue(matchIds(with, "foo").isEmpty());

            // without QUIET: a normal match callback is generated
            assertEquals(List.of(0), matchIds(without, "foo"));
        }
    }
}
