/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule / Saiku community
// All Rights Reserved.
*/
package mondrian.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import mondrian.spi.Dialect;
import mondrian.spi.impl.MockDialect;

/**
 * Injection-safety properties for every SQL dialect Mondrian ships.
 *
 * <p>Mondrian generates SQL by string concatenation, and the only thing standing between a schema's
 * table and column names — or a member's caption, which can come from the data — and the generated
 * statement is these quoting methods. Every dialect gets its own subclass with its own delimiter
 * and its own escaping rule, which means the safety argument has to be made <em>per dialect</em>;
 * checking one and assuming the rest follow is how a single overridden method becomes an injection
 * hole.
 *
 * <p>{@link MockDialect#of} builds a dialect without a database connection, so all
 * {@link Dialect.DatabaseProduct} values can be covered here rather than only the one backend the
 * test fixture happens to run.
 *
 * <p>The properties asserted are the ones that hold whatever a dialect's escaping convention is:
 *
 * <ul>
 *   <li><strong>Injectivity</strong> — distinct inputs must produce distinct output. A quoting
 *       function that collapses two identifiers into one text is exploitable by construction,
 *       whatever its delimiters look like.
 *   <li><strong>Delimitation</strong> — the output is wrapped in the dialect's delimiter, so the
 *       parser reads it as one token.
 *   <li><strong>No early termination</strong> — scanning the output under the doubled-delimiter
 *       rule consumes all of it, so no input can close its own quoting and have the remainder
 *       parsed as SQL.
 * </ul>
 */
class DialectQuotingPropertyTest {


    /** Products whose dialect could not be built here, reported so the coverage gap is visible. */
    private static final List<String> SKIPPED = new ArrayList<>();

    /**
     * Every dialect Mondrian can construct without a live connection.
     *
     * <p>Declared AFTER {@code SKIPPED}: static initialisers run in source order, so building the
     * list first would populate a field that does not exist yet and fail the whole class with an
     * ExceptionInInitializerError.
     */
    private static final List<Dialect> DIALECTS = buildDialects();

    private static List<Dialect> buildDialects() {
        List<Dialect> dialects = new ArrayList<>();
        for (Dialect.DatabaseProduct product : Dialect.DatabaseProduct.values()) {
            try {
                Dialect dialect = MockDialect.of(product);
                if (dialect != null) {
                    dialects.add(dialect);
                } else {
                    SKIPPED.add(product + " (null)");
                }
            } catch (Throwable t) {
                // Throwable, not RuntimeException: MockDialect.of raises a bare AssertionError for
                // products it cannot build without a live connection, and letting that escape a
                // static initialiser turns every test in the class into NoClassDefFoundError with
                // the real cause buried two levels down.
                SKIPPED.add(product + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        if (dialects.isEmpty()) {
            throw new IllegalStateException("no dialects could be constructed; this suite would be vacuous");
        }
        return List.copyOf(dialects);
    }

    /**
     * Reports coverage rather than assuming it.
     *
     * <p>A suite that silently covers three dialects instead of thirty still reports green, so the
     * count is asserted and the skipped products are named. This is the same reasoning as
     * {@code HegelPlumbingTest}: state what was actually exercised.
     */
    @org.junit.jupiter.api.Test
    void coversMostDialects() {
        System.out.println("dialects under test (" + DIALECTS.size() + "): "
                + DIALECTS.stream().map(DialectQuotingPropertyTest::product).sorted().toList());
        if (!SKIPPED.isEmpty()) {
            System.out.println("  not constructible without a connection: " + SKIPPED);
        }
        assertTrue(
                DIALECTS.size() >= 10,
                () -> "only " + DIALECTS.size() + " dialects could be constructed; skipped " + SKIPPED);
    }

    private static Generator<Dialect> dialect() {
        return dev.hegel.Generators.sampledFrom(DIALECTS);
    }

    // ------------------------------------------------------------------
    // Identifiers
    // ------------------------------------------------------------------

    /**
     * {@code quoteIdentifier} never lets an identifier terminate its own quoting.
     *
     * <p>This is the injection property stated directly. If the scan stops before the end of the
     * output, everything after that point would be read by the database as SQL — which is exactly
     * what an injected delimiter is for.
     */
    @HegelTest(testCases = 500)
    void quotedIdentifiersCannotBeTerminatedEarly(TestCase tc) {
        Dialect dialect = tc.draw(dialect(), "dialect");
        String name = tc.draw(Generators.adversarialText(), "name");

        String delimiter = dialect.getQuoteIdentifierString();
        // A dialect that declares no delimiter cannot make this guarantee and does not claim to.
        tc.assume(delimiter != null && delimiter.length() == 1);
        String quoted = dialect.quoteIdentifier(name);

        char q = delimiter.charAt(0);
        assertTrue(
                quoted.length() >= 2 && quoted.charAt(0) == q && quoted.charAt(quoted.length() - 1) == q,
                () -> product(dialect) + " produced an undelimited identifier " + show(quoted) + " for " + show(name));

        int end = scanQualifiedIdentifier(quoted, q);
        assertEquals(
                quoted.length(),
                end,
                () -> product(dialect) + ": identifier " + show(quoted) + " for input " + show(name)
                        + " ends at offset " + end + " — the remaining " + (quoted.length() - end)
                        + " characters would be read as SQL");
    }

    /**
     * Regression test for issue #140: {@code quoteIdentifier} escapes every identifier, including
     * one that already begins and ends with the dialect's quote character.
     *
     * <p>{@code JdbcDialectImpl.quoteIdentifierImpl} used to short-circuit on
     * {@code val.startsWith(q) && val.endsWith(q)} with the comment "already quoted - nothing to
     * do", assuming such a value was a well-formed quoted identifier. It need not be. These are the
     * three minimal witnesses the property suite produced, kept as a deterministic guard so the
     * short-circuit cannot come back:
     */
    @org.junit.jupiter.api.Test
    void quoteIdentifierEscapesAlreadyDelimitedInput() {
        Dialect oracle = MockDialect.of(Dialect.DatabaseProduct.ORACLE);

        // (a) INJECTIVE. Two different identifiers must not collapse onto the same SQL text.
        assertNotEquals(
                oracle.quoteIdentifier("a"),
                oracle.quoteIdentifier("\"a\""),
                "distinct identifiers must quote to distinct text");
        assertNotEquals(
                oracle.quoteIdentifier(""),
                oracle.quoteIdentifier("\"\""),
                "the empty identifier and a doubled delimiter must not collide");

        // (b) A LONE DELIMITER is escaped rather than emitted bare, so the token stays balanced.
        assertEquals("\"\"\"\"", oracle.quoteIdentifier("\""), "a single quote character must be escaped");

        // (c) The injection witness is now inert: it comes back as one delimited token, so the
        //     ` FROM t WHERE 1=1 --` is data inside an identifier rather than statement text.
        String hostile = "\"a\" FROM t WHERE 1=1 --\"";
        String quoted = oracle.quoteIdentifier(hostile);
        assertEquals(
                quoted.length(),
                scanQualifiedIdentifier(quoted, '"'),
                () -> "the hostile identifier still terminates early: " + show(quoted));

        // Ordinary escaping is unchanged.
        assertEquals("\"a\"\"b\"", oracle.quoteIdentifier("a\"b"), "normal escaping is unaffected");
        assertEquals("\"emp\"", oracle.quoteIdentifier("emp"), "a plain identifier is unaffected");
    }

    /**
     * {@code quoteIdentifier} is injective: different names never quote to the same text.
     *
     * <p>Holds regardless of the escaping convention, so it applies to dialects whose rule this
     * file does not otherwise model.
     */
    @HegelTest(testCases = 500)
    void quotingIdentifiersIsInjective(TestCase tc) {
        Dialect dialect = tc.draw(dialect(), "dialect");
        String first = tc.draw(Generators.adversarialText(), "first");
        String second = tc.draw(Generators.adversarialText(), "second");
        tc.assume(!first.equals(second));
        assertNotEquals(
                dialect.quoteIdentifier(first),
                dialect.quoteIdentifier(second),
                () -> product(dialect) + " quoted " + show(first) + " and " + show(second) + " identically");
    }

    /** Quoting is deterministic. */
    @HegelTest(testCases = 300)
    void quotingIsDeterministic(TestCase tc) {
        Dialect dialect = tc.draw(dialect(), "dialect");
        String name = tc.draw(Generators.adversarialText(), "name");

        assertEquals(
                dialect.quoteIdentifier(name),
                dialect.quoteIdentifier(name),
                () -> product(dialect) + " is not deterministic for " + show(name));
    }

    // ------------------------------------------------------------------
    // String literals
    // ------------------------------------------------------------------

    /**
     * {@code quoteStringLiteral} never lets a value terminate its own literal.
     *
     * <p>String literals carry data-derived text — captions, member keys read from the fact table —
     * so this is the path an attacker reaches through content rather than through configuration.
     */
    @HegelTest(testCases = 500)
    void quotedStringLiteralsCannotBeTerminatedEarly(TestCase tc) {
        Dialect dialect = tc.draw(dialect(), "dialect");
        String value = tc.draw(Generators.adversarialText(), "value");

        // Backslash is excluded for the two dialects that escape with backslashes but never escape
        // the backslash itself -- a confirmed defect (issue #146), pinned by
        // impalaAndHiveDoNotEscapeBackslashesInStringLiterals. Every other dialect keeps it under
        // test, and these two keep every other character under test.
        tc.assume(!(usesBackslashEscaping(dialect) && value.indexOf('\\') >= 0));

        StringBuilder buf = new StringBuilder();
        dialect.quoteStringLiteral(buf, value);
        String literal = buf.toString();

        // The delimiter is read off the output rather than assumed to be '\''. Impala deliberately
        // switches to '"' when the value contains a single quote, which is a legal SQL string
        // literal and not a defect -- asserting '\'' unconditionally failed on correct behaviour.
        assertTrue(
                literal.length() >= 2
                        && (literal.charAt(0) == '\'' || literal.charAt(0) == '"')
                        && literal.charAt(literal.length() - 1) == literal.charAt(0),
                () -> product(dialect) + " produced an undelimited literal " + show(literal) + " for " + show(value));

        int end = scanDelimited(literal, literal.charAt(0));
        assertEquals(
                literal.length(),
                end,
                () -> product(dialect) + ": literal " + show(literal) + " for input " + show(value)
                        + " ends at offset " + end + " — the remaining " + (literal.length() - end)
                        + " characters would be read as SQL");
    }

    /**
     * Whether {@code dialect} escapes string literals with backslashes rather than by doubling.
     *
     * <p>Impala and Hive both do. The distinction matters because the same output text is a valid
     * literal under one convention and malformed under the other.
     */
    private static boolean usesBackslashEscaping(Dialect dialect) {
        Dialect.DatabaseProduct product = dialect.getDatabaseProduct();
        return product == Dialect.DatabaseProduct.IMPALA || product == Dialect.DatabaseProduct.HIVE;
    }

    /**
     * Characterisation test for a CONFIRMED DEFECT found by this suite: <strong>Impala and Hive
     * escape quotes in string literals but never escape the backslash itself.</strong> Tracked as
     * issue #146.
     *
     * <p>Both dialects use backslash escaping — {@code ImpalaDialect.quoteStringLiteral} writes
     * {@code \\} + the delimiter, and its own source carries a {@code REVIEW:} comment asking
     * whether the rules really differ from the standard. Neither escapes a backslash that appears
     * in the <em>value</em>, so:
     *
     * <pre>{@code   value "\\"   ->  '\\'     // the closing quote is escaped; the literal never terminates
     *   value "a\\"  ->  'a\\'}</pre>
     *
     * <p>Under a convention where {@code \} escapes the next character, {@code '\'} opens a literal
     * and then escapes its own closing quote, so the literal runs on and swallows the rest of the
     * statement.
     *
     * <p><strong>Why this is the more serious surface.</strong> Identifiers come from the schema, so
     * exploiting issue #140 needs schema-authoring access. String literals carry <em>data</em> —
     * member captions and key values read from the fact table — so this one is reachable by anyone
     * who can get a backslash into a dimension value.
     *
     * <p>The base implementation is not at fault: {@code Util.singleQuoteString} produces
     * {@code '\'} too, which is a correct one-character literal under standard SQL where backslash
     * is not an escape. It is specifically the two dialects that opted into backslash escaping
     * without escaping backslashes.
     */
    @org.junit.jupiter.api.Test
    void impalaAndHiveDoNotEscapeBackslashesInStringLiterals() {
        for (Dialect.DatabaseProduct product :
                new Dialect.DatabaseProduct[] {Dialect.DatabaseProduct.IMPALA, Dialect.DatabaseProduct.HIVE}) {
            Dialect dialect = MockDialect.of(product);
            StringBuilder buf = new StringBuilder();
            dialect.quoteStringLiteral(buf, "\\");
            assertEquals(
                    "'\\'",
                    buf.toString(),
                    () -> product + " witness changed — backslash escaping may have been fixed; if so, delete "
                            + "this test and the usesBackslashEscaping exclusion");
        }
    }

    /** {@code quoteStringLiteral} is injective. */
    @HegelTest(testCases = 500)
    void quotingStringLiteralsIsInjective(TestCase tc) {
        Dialect dialect = tc.draw(dialect(), "dialect");
        String first = tc.draw(Generators.adversarialText(), "first");
        String second = tc.draw(Generators.adversarialText(), "second");
        tc.assume(!first.equals(second));

        assertNotEquals(
                literalOf(dialect, first),
                literalOf(dialect, second),
                () -> product(dialect) + " quoted " + show(first) + " and " + show(second) + " to the same literal");
    }

    // ------------------------------------------------------------------

    private static String literalOf(Dialect dialect, String value) {
        StringBuilder buf = new StringBuilder();
        dialect.quoteStringLiteral(buf, value);
        return buf.toString();
    }

    private static String product(Dialect dialect) {
        return String.valueOf(dialect.getDatabaseProduct());
    }

    /**
     * Scans a dot-separated sequence of {@code q}-delimited tokens from offset 0, returning the
     * offset where a SQL parser would resume reading statement text.
     *
     * <p>A <em>sequence</em> rather than a single token because {@code quoteIdentifierImpl}
     * deliberately treats a dot in the input as a qualifier separator and emits
     * {@code "owner"."table"} — see the comment in that method. Insisting on a single token would
     * flag that intended output as a failure, so the scanner models what the implementation
     * actually promises and stays sensitive to the case that matters: output that stops being a
     * quoted identifier and becomes bare SQL.
     */
    private static int scanQualifiedIdentifier(String s, char q) {
        int i = 0;
        while (true) {
            int end = scanDelimited(s.substring(i), q);
            if (end == 0) {
                return i;
            }
            i += end;
            if (i < s.length() && s.charAt(i) == '.') {
                i++; // qualifier separator: another delimited token must follow
            } else {
                return i;
            }
        }
    }

    /**
     * Scans a {@code q}-delimited token from offset 0 under the doubled-delimiter rule, returning
     * the offset just past its closing delimiter — where a SQL parser would resume.
     */
    private static int scanDelimited(String s, char q) {
        if (s.isEmpty() || s.charAt(0) != q) {
            return 0;
        }
        int i = 1;
        while (i < s.length()) {
            if (s.charAt(i) == q) {
                if (i + 1 < s.length() && s.charAt(i + 1) == q) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return i;
    }

    private static String show(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
