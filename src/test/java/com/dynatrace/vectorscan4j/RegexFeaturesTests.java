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

import static com.dynatrace.vectorscan4j.constants.ExecutionMode.BLOCK_MODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RegexFeaturesTests {
    static int nMatches = 0;
    static final MatchHandler countMatch = (_, _, _, _) -> {
        nMatches += 1;
        return true;
    };

    static List<Expression> getExpressions(List<String> patterns) {
        List<Expression> expressions = new ArrayList<>();
        for (var pattern : patterns) {
            expressions.add(new Expression(pattern));
        }
        return expressions;
    }

    static int numMatches(String s, List<String> patterns) {
        var expressions = getExpressions(patterns);
        nMatches = 0;
        try (Database db = new Database(expressions, BLOCK_MODE);
                BlockScanner scanner = new BlockScanner(db)) {
            scanner.scan(s, countMatch);
        }
        return nMatches;
    }

    static boolean matches(char input, String pattern) {
        int ans = numMatches(String.valueOf(input), List.of(pattern));
        assert ans <= 1;
        return ans == 1;
    }

    @Test
    void literals() {
        assertEquals(3, numMatches("testestring", List.of("test", "string")));
        assertEquals(0, numMatches("best tess", List.of("test")));
    }

    @Test
    void specialCharacters() {
        // ^ matches the start of a string (even in the empty string)
        assertEquals(1, numMatches("", List.of("^")));
        assertEquals(0, numMatches("ab", List.of("^b")));
        assertEquals(1, numMatches("ab", List.of("^a")));

        // $ matches the end of a string
        assertEquals(1, numMatches("", List.of("$")));
        assertEquals(1, numMatches("ab", List.of("b$")));
        assertEquals(0, numMatches("ab", List.of("a$")));

        // | matches a specific character or group of characters on either side
        assert (matches('a', "a|b"));
        assert (matches('b', "a|b"));
        assertFalse(matches('c', "a|b"));
    }

    @Test
    void groupsAndRanges() {
        // [<chars>] matches any of the enclosed characters
        assert (matches('e', "[aeiou]"));
        assert (matches('u', "[aeiou]"));
        assertFalse(matches('g', "[aeiou]"));
        assertFalse(matches('f', "[aeiou]"));

        // [^<chars>] matches anything but the enclosed characters
        assertFalse(matches('e', "[^aeiou]"));
        assertFalse(matches('u', "[^aeiou]"));
        assert (matches('g', "[^aeiou]"));
        assert (matches('f', "[^aeiou]"));

        // [<char1>-<char2>] matches any characters in range from char1 to char2
        assert (matches('c', "[c-e]"));
        assert (matches('e', "[c-e]"));
        assertFalse(matches('a', "[c-e]"));
        assertFalse(matches('f', "[c-e]"));

        // [^<char1>-<char2>] matches anything but the characters in range from char1 to char2
        assertFalse(matches('c', "[^c-e]"));
        assertFalse(matches('e', "[^c-e]"));
        assert (matches('a', "[^c-e]"));
        assert (matches('f', "[^c-e]"));
    }

    @Test
    void wildCards() {
        // "." matches any character except for newline (\n)
        assertEquals(3, numMatches("abc", List.of(".")));
        assertEquals(0, numMatches("\n", List.of(".")));

        // note that it still matches carriage return (\r)
        assertEquals(1, numMatches("\r", List.of(".")));
        assertEquals(6, numMatches("a\rb\ncde", List.of(".")));
    }

    @Test
    void digitCharacterClasses() {
        // \s matches a whitespace character (space, tab, newline, carriage return, form feed)
        assert (matches(' ', "\\s"));
        assert (matches('\t', "\\s"));
        assert (matches('\n', "\\s"));
        assert (matches('\r', "\\s"));
        assert (matches('\f', "\\s"));
        assertFalse(matches('a', "\\s"));
        assertFalse(matches('A', "\\s"));
        assertFalse(matches('1', "\\s"));
        assertFalse(matches('_', "\\s"));
        assertFalse(matches('!', "\\s"));

        // \S matches a non-whitespace character
        assertEquals(5, numMatches("aA1_!", List.of("\\S")));
        assertEquals(0, numMatches(" \t\n\r\f", List.of("\\S")));

        // \w matches a word character (letters, digits, and underscore)
        assert (matches('a', "\\w"));
        assert (matches('A', "\\w"));
        assert (matches('5', "\\w"));
        assert (matches('_', "\\w"));
        assertFalse(matches(' ', "\\w"));
        assertFalse(matches('!', "\\w"));
        assertFalse(matches('\t', "\\w"));

        // \W matches a non-word character
        assertEquals(3, numMatches(" !\t", List.of("\\W")));

        // \d matches a digit
        assertEquals(5, numMatches("12345abc", List.of("\\d")));
        // \D matches a non-digit
        assertEquals(3, numMatches("12345abc", List.of("\\D")));
    }

    @Test
    void wordBoundaries() {
        // \b matches a zero-width boundary between a word character and a non-word character
        // the start and end of a string count as non-word characters
        assertFalse(matches('!', "\\b"));
        assertEquals(2, numMatches("a", List.of("\\b")));
        assertEquals(6, numMatches("!a!a!a!", List.of("\\b")));
        assertEquals(2, numMatches("!!!!aaa", List.of("\\b")));
    }

    @Test
    void quantifierZeroToN() {
        // * matches 0 or more of the previous character or group
        assertEquals(1, numMatches("ac", List.of("ab*c")));
        assertEquals(1, numMatches("abc", List.of("ab*c")));
        assertEquals(1, numMatches("abbc", List.of("ab*c")));
        assertEquals(6, numMatches("abbbbbc", List.of("ab*")));
    }

    @Test
    void quantifierOneToN() {
        // + matches 1 or more of the previous character or group
        assertEquals(0, numMatches("ac", List.of("ab+c")));
        assertEquals(1, numMatches("abc", List.of("ab+c")));
        assertEquals(1, numMatches("abbc", List.of("ab+c")));
        assertEquals(5, numMatches("abbbbbc", List.of("ab+")));
    }

    @Test
    void quantifierZeroToOne() {
        // ? matches 0 or 1 of the previous character or group
        assertEquals(1, numMatches("ac", List.of("ab?c")));
        assertEquals(1, numMatches("abc", List.of("ab?c")));
        assertEquals(0, numMatches("abbc", List.of("ab?c")));
        assertEquals(4, numMatches("abaac", List.of("ab?")));
    }

    @Test
    void quantifierExactlyN() {
        // {n} matches exactly n of the previous character or group
        assertEquals(1, numMatches("aabbbcc", List.of("ab{3}")));
        assertEquals(0, numMatches("aabbcc", List.of("ab{3}")));
        assertEquals(1, numMatches("aabbbb", List.of("ab{3}")));
        assertEquals(0, numMatches("aabbbb", List.of("ab{3}[^b]")));
    }

    @Test
    void quantifierAtLeastN() {
        // {n,} matches n or more of the previous character or group
        assertEquals(2, numMatches("aabbb", List.of("ab{2,}")));
        assertEquals(1, numMatches("aabbcc", List.of("ab{2,}")));
        assertEquals(0, numMatches("aac", List.of("ab{2,}")));
    }

    @Test
    void quantifierNToM() {
        // {n,m} matches between n and m (inclusive) of the previous character or group
        assertEquals(1, numMatches("aabbcc", List.of("ab{2,3}")));
        assertEquals(2, numMatches("aabbbcc", List.of("ab{2,3}")));
        assertEquals(2, numMatches("aabbbbcc", List.of("ab{2,3}")));
        assertEquals(3, numMatches("aabbccabbb", List.of("ab{2,3}")));
    }
}
