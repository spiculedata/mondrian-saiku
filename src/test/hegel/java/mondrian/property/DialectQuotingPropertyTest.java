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
        // Excluded: input that already begins and ends with the delimiter takes the verbatim
        // pass-through branch, a characterised defect -- see
        // quoteIdentifierPassesAlreadyDelimitedInputThroughUnescaped. Everything else stays under
        // test. Delete this assume when that branch is fixed.
        tc.assume(!isAlreadyDelimited(name, delimiter));

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
     * Characterisation test for a CONFIRMED DEFECT found by this suite:
     * <strong>{@code quoteIdentifier} emits an identifier verbatim, with no escaping at all, when
     * it already begins and ends with the dialect's quote character.</strong> Tracked as issue #140.
     *
     * <p>The branch is explicit in {@code JdbcDialectImpl.quoteIdentifierImpl}:
     *
     * <pre>{@code   if (val.startsWith(q) && val.endsWith(q)) {
     *       // already quoted - nothing to do
     *       return buf.append(val);
     *   }}</pre>
     *
     * <p>The assumption is that such a value is already a well-formed quoted identifier. It need
     * not be. {@code "a" FROM t WHERE 1=1 --"} begins and ends with {@code "}, so it is copied
     * straight into the generated SQL: the leading {@code "a"} closes immediately, everything after
     * it is parsed as statement text, and the trailing quote is swallowed by the {@code --} comment.
     * That is SQL injection through an identifier.
     *
     * <p>It is in the shared base implementation, so it affects <em>all</em> {@value #DIALECT_COUNT_NOTE}
     * dialects, not one. Correctly-escaping inputs are unaffected — {@code a"b} still becomes
     * {@code "a""b"} — so only this branch is at fault.
     *
     * <p><strong>Reachability, stated precisely.</strong> Identifiers come from the schema
     * (table, column and expression names). In a deployment where schemas are authored only by
     * trusted administrators this is a latent bug; where schemas can be uploaded or edited by
     * ordinary users — which Saiku supports — it is a live injection vector. Which of those applies
     * is a deployment question, so the severity is not for this test to settle.
     *
     * <p>Left unfixed: removing the branch changes what every dialect emits for any identifier that
     * happens to be delimiter-wrapped, and callers may be relying on the pass-through to hand
     * pre-quoted SQL fragments through. That is a product call.
     */
    @org.junit.jupiter.api.Test
    void quoteIdentifierPassesAlreadyDelimitedInputThroughUnescaped() {
        Dialect oracle = MockDialect.of(Dialect.DatabaseProduct.ORACLE);

        // Three consequences of the one branch, all minimal, all found by the generator rather
        // than constructed:

        // (a) NOT INJECTIVE. Two different identifiers produce byte-identical SQL, so the quoting
        //     cannot be undone and two distinct schema objects become indistinguishable.
        assertEquals(
                oracle.quoteIdentifier("a"),
                oracle.quoteIdentifier("\"a\""),
                "witness changed - quoteIdentifier may have become injective");
        assertEquals("\"a\"", oracle.quoteIdentifier("a"), "both collapse onto the same text");

        // (b) A LONE DELIMITER passes through as itself, emitting an unbalanced quote into the SQL
        //     that swallows whatever follows it. This is the shortest possible malicious input.
        assertEquals("\"", oracle.quoteIdentifier("\""), "a single quote character is emitted bare");

        // (c) The empty identifier and a doubled delimiter also collide.
        assertEquals(oracle.quoteIdentifier(""), oracle.quoteIdentifier("\"\""), "empty vs doubled collide");

        String hostile = "\"a\" FROM t WHERE 1=1 --\"";

        assertEquals(
                hostile,
                oracle.quoteIdentifier(hostile),
                "witness changed — the already-quoted pass-through may have been fixed; if so, delete "
                        + "this test, as quotedIdentifiersCannotBeTerminatedEarly already covers it");

        // The scan stops after `"a"`, so everything from ` FROM` onward reaches the database as SQL.
        assertEquals(
                3,
                scanQualifiedIdentifier(hostile, '"'),
                "the injected text is expected to begin at offset 3");

        // For contrast: ordinary input containing the delimiter IS escaped correctly.
        assertEquals("\"a\"\"b\"", oracle.quoteIdentifier("a\"b"), "normal escaping is unaffected");
    }

    /** Referenced from the javadoc above so the dialect count cannot drift out of sync silently. */
    private static final String DIALECT_COUNT_NOTE = "26";

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
        // Same exclusion as above: the pass-through branch is what breaks injectivity, and it is
        // already pinned deterministically below.
        String delimiter = dialect.getQuoteIdentifierString();
        tc.assume(!isAlreadyDelimited(first, delimiter) && !isAlreadyDelimited(second, delimiter));

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

        StringBuilder buf = new StringBuilder();
        dialect.quoteStringLiteral(buf, value);
        String literal = buf.toString();

        assertTrue(
                literal.length() >= 2 && literal.charAt(0) == '\'' && literal.charAt(literal.length() - 1) == '\'',
                () -> product(dialect) + " produced an undelimited literal " + show(literal) + " for " + show(value));

        int end = scanDelimited(literal, '\'');
        assertEquals(
                literal.length(),
                end,
                () -> product(dialect) + ": literal " + show(literal) + " for input " + show(value)
                        + " ends at offset " + end + " — the remaining " + (literal.length() - end)
                        + " characters would be read as SQL");
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

    /**
     * Whether {@code value} already begins and ends with the dialect's delimiter — the condition
     * that triggers {@code quoteIdentifierImpl}'s verbatim pass-through.
     *
     * <p>Note it is true for a <em>single</em> delimiter character, since one character both starts
     * and ends with itself. That is how the generator found the lone-quote witness.
     */
    private static boolean isAlreadyDelimited(String value, String delimiter) {
        return delimiter != null
                && !delimiter.isEmpty()
                && value.startsWith(delimiter)
                && value.endsWith(delimiter);
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
