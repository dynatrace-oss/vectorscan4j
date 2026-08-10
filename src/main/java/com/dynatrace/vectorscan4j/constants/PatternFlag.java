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
package com.dynatrace.vectorscan4j.constants;

import com.dynatrace.vectorscan4j.BlockScanner;
import com.dynatrace.vectorscan4j.MatchHandler;
import java.lang.foreign.MemorySegment;

/**
 * Pattern flags for literal/regex expressions.
 */
public enum PatternFlag {
    /* The comments below were adapted from the official Hyperscan documentation https://intel.github.io/hyperscan/dev-reference/api_constants.html */

    /**
     * Compile flag: Set case-insensitive matching.
     *
     * <p>This flag sets the expression to be matched case-insensitively by default. The expression
     * may still use PCRE tokens (notably `(?i)` and `(?-i)`) to switch case-insensitive matching on
     * and off.
     */
    CASELESS(1),

    /**
     * Compile flag: Matching a `.` will not exclude newlines.
     *
     * <p>This flag sets any instances of the `.` token to match newline characters as well as all
     * other characters. The PCRE specification states that the `.` token does not match newline
     * characters by default, so without this flag the `.` token will not cross line boundaries.
     */
    DOTALL(2),

    /**
     * Compile flag: Set multi-line anchoring.
     *
     * <p>This flag instructs the expression to make the `^` and `$` tokens match newline characters
     * as well as the start and end of the stream. If this flag is not specified, the `^` token will
     * only ever match at the start of a stream, and the `$` token will only ever match at the end of
     * a stream within the guidelines of the PCRE specification.
     */
    MULTILINE(4),

    /**
     * Compile flag: Set single-match only mode.
     *
     * <p>This flag sets the expression's match ID to match at most once. In streaming mode, this
     * means that the expression will return only a single match over the lifetime of the stream,
     * rather than reporting every match as per standard Vectorscan semantics. In block mode, only the
     * first match for each invocation of {@link BlockScanner#scan(MemorySegment, MatchHandler)
     * BlockScanner.scan(...)} will be returned.
     *
     * <p>Note: The use of this flag in combination with {@link #SOM_LEFTMOST} is not currently
     * supported.
     */
    SINGLEMATCH(8),

    /**
     * Compile flag: Allow expressions that can match against empty buffers.
     *
     * <p>This flag instructs the compiler to allow expressions that can match against empty buffers,
     * such as `.?`, `.*`, `(a|)`. Since Vectorscan can return every possible match for an expression,
     * such expressions generally execute very slowly; the default behaviour is to return an error
     * when an attempt to compile one is made. Using this flag will force the compiler to allow such
     * an expression.
     */
    ALLOWEMPTY(16),

    /**
     * Compile flag: Enable UTF-8 mode for this expression.
     *
     * <p>This flag instructs Vectorscan to treat the pattern as a sequence of UTF-8 characters. The
     * results of scanning invalid UTF-8 sequences with a Vectorscan library that has been compiled
     * with one or more patterns using this flag are undefined.
     */
    UTF8(32),

    /**
     * Compile flag: Enable Unicode property support for this expression.
     *
     * <p>This flag instructs Vectorscan to use Unicode properties, rather than the default ASCII
     * interpretations, for character mnemonics like `\w` and `\s` as well as the POSIX character
     * classes. It is only meaningful in conjunction with {@link #UTF8}.
     */
    UCP(64),

    /**
     * Compile flag: Enable prefiltering mode for this expression.
     *
     * <p>This flag instructs Hyperscan to compile an "approximate" version of this pattern for use in
     * a prefiltering application, even if Hyperscan does not support the pattern in normal operation.
     *
     * <p>The set of matches returned when this flag is used is guaranteed to be a superset of the
     * matches specified by the non-prefiltering expression.
     *
     * <p>If the pattern contains pattern constructs not supported by Hyperscan (such as zero-width
     * assertions, back-references or conditional references) these constructs will be replaced
     * internally with broader constructs that may match more often.
     *
     * <p>Furthermore, in prefiltering mode Hyperscan may simplify a pattern that would otherwise
     * return a "Pattern too large" error at compile time, or for performance reasons (subject to the
     * matching guarantee above).
     *
     * <p>It is generally expected that the application will subsequently confirm prefilter matches
     * with another regular expression matcher that can provide exact matches for the pattern.
     *
     * <p>Note: The use of this flag in combination with {@link #SOM_LEFTMOST} is not currently
     * supported.
     */
    PREFILTER(128),

    /**
     * Compile flag: Enable leftmost start of match reporting.
     *
     * <p>This flag instructs Hyperscan to report the leftmost possible start of match offset when a
     * match is reported for this expression. (By default, no start of match is returned.)
     *
     * <p>For all the 3 modes, enabling this behaviour may reduce performance. And particularly, it
     * may increase stream state requirements in streaming mode.
     */
    SOM_LEFTMOST(256),

    /**
     * Compile flag: Logical combination.
     *
     * <p>This flag instructs Hyperscan to parse this expression as logical combination syntax.
     *
     * <p>Logical constraints consist of operands, operators and parentheses. The operands are
     * expression indices, and operators can be {@code "!"} (NOT), {@code "&amp;"} (AND) or
     * {@code "|"} (OR).
     *
     * <p>For example: {@code (101&amp;102&amp;103)|(104&amp;!105)}
     * {@code ((301|302)&amp;303)&amp;(304|305)}
     */
    COMBINATION(512),

    /**
     * Compile flag: Don't do any match reporting.
     *
     * <p>This flag instructs Hyperscan to ignore match reporting for this expression. It is designed
     * to be used on the sub-expressions in logical combinations.
     */
    QUIET(1024);
    public final int value;

    PatternFlag(int value) {
        this.value = value;
    }
}
