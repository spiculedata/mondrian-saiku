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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import mondrian.olap.Id;
import mondrian.olap.Util;
import org.junit.jupiter.api.Test;

/**
 * Properties of Mondrian's quoting and escaping primitives.
 *
 * <p>These are the functions that embed arbitrary user-controlled text — dimension names, member
 * captions, connect-string values — into a larger syntax (MDX, SQL). The property that matters for
 * every one of them is the same, and it is a security property as much as a correctness one:
 *
 * <blockquote>
 * Quoting must be <em>injective</em>. Whatever goes in must come back out unchanged, and no input
 * may terminate its own quoting early and let the rest of the string be read as syntax.
 * </blockquote>
 *
 * <p>Example-based tests cover the escape characters the author remembered. A property test covers
 * the ones they did not — which is where injection bugs live, since an attacker picks the input
 * precisely from the set nobody thought to write a test for.
 */
class UtilQuotingPropertyTest {

    // ------------------------------------------------------------------
    // MDX identifier quoting:  name -> [name] -> name
    // ------------------------------------------------------------------

    /**
     * {@code quoteMdxIdentifier} followed by {@code parseIdentifier} is the identity.
     *
     * <p>This pair is the boundary every schema name crosses on its way into an MDX string. If a
     * name survives quoting but parses back as something else, a member called {@code "Foo]}
     * .[Bar"} silently becomes a two-segment reference to a different member.
     */
    @HegelTest(testCases = 400)
    void quoteMdxIdentifierRoundTrips(TestCase tc) {
        String name = tc.draw(schemaName(), "name");

        String quoted = Util.quoteMdxIdentifier(name);
        List<Id.Segment> parsed = parse(quoted);

        assertEquals(1, parsed.size(), () -> "quoted " + show(quoted) + " parsed as " + parsed.size()
                + " segments; a single name must stay a single segment");
        assertEquals(name, nameOf(parsed.get(0)), () -> "round trip changed the name via " + show(quoted));
    }

    /**
     * The same property for a whole multi-segment identifier: {@code [A].[B].[C]}.
     *
     * <p>Segment count is asserted separately from content because the two failure modes are
     * different bugs — a name that swallows a {@code .} merges segments, whereas a name that
     * escapes a {@code ]} badly splits them.
     */
    @HegelTest(testCases = 300)
    void quoteMdxIdentifierListRoundTrips(TestCase tc) {
        List<String> names =
                tc.draw(dev.hegel.Generators.lists(schemaName()).minSize(1).maxSize(4), "names");

        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                buf.append('.');
            }
            Util.quoteMdxIdentifier(names.get(i), buf);
        }
        String quoted = buf.toString();

        List<Id.Segment> parsed = parse(quoted);
        assertEquals(names.size(), parsed.size(), () -> "segment count changed via " + show(quoted));
        for (int i = 0; i < names.size(); i++) {
            assertEquals(names.get(i), nameOf(parsed.get(i)), "segment " + i + " changed via " + show(quoted));
        }
    }

    // ------------------------------------------------------------------
    // MDX string literals:  s -> "s" -> s
    // ------------------------------------------------------------------

    /**
     * {@code quoteForMdx} produces a well-formed MDX double-quoted literal whose content decodes
     * back to the input.
     *
     * <p>Decoded here by an independent scanner rather than by a Mondrian function, so the test
     * cannot be satisfied by a quoter and an unquoter that are wrong in matching ways.
     */
    @HegelTest(testCases = 400)
    void quoteForMdxProducesADecodableLiteral(TestCase tc) {
        String s = tc.draw(Generators.adversarialText(), "s");

        String literal = Util.quoteForMdx(s);
        assertEquals(s, decodeDoubledLiteral(literal, '"'), () -> "literal " + show(literal) + " did not decode back");
    }

    // ------------------------------------------------------------------
    // SQL string literals:  s -> 's' -> s   (injection safety)
    // ------------------------------------------------------------------

    /**
     * {@code singleQuoteString} produces a SQL literal that cannot be terminated early.
     *
     * <p>This is the injection property stated directly. Scanning the output under the standard SQL
     * rule ("a doubled quote is a literal quote, a lone quote ends the literal") must consume the
     * <em>entire</em> output. If it stops short, the remaining characters would be parsed by the
     * database as SQL, which is exactly what an injected {@code '} achieves.
     */
    @HegelTest(testCases = 500)
    void singleQuoteStringCannotBeTerminatedEarly(TestCase tc) {
        String s = tc.draw(Generators.adversarialText(), "s");

        String literal = Util.singleQuoteString(s);

        int end = scanSqlLiteral(literal);
        assertEquals(
                literal.length(),
                end,
                () -> "literal " + show(literal) + " for input " + show(s) + " ends at offset " + end
                        + " — the remaining " + (literal.length() - end)
                        + " characters would be read by the database as SQL");
        assertEquals(s, decodeDoubledLiteral(literal, '\''), () -> "literal " + show(literal) + " did not decode back");
    }

    // ------------------------------------------------------------------
    // Connect strings:  PropertyList -> text -> PropertyList
    // ------------------------------------------------------------------

    /**
     * A {@link Util.PropertyList} survives being written out and parsed back.
     *
     * <p>Connect strings carry JDBC URLs, catalog paths and credentials, and Mondrian round-trips
     * them through text in several places (server-side connection pooling, {@code CmdRunner},
     * XMLA). A value that does not survive the round trip is a value that silently changes meaning
     * between the caller and the driver.
     *
     * <p>Values here exclude the delimiter characters, because {@code PropertyList.toString} is
     * <em>known</em> not to round-trip those — see
     * {@link #connectStringToStringIsLossyForDelimiterValues} for the exact witnesses and why the
     * gap is not simply fixed here. This test therefore pins the far larger space that
     * <em>should</em> work, so a regression in ordinary values is still caught.
     *
     * <p>Keys are drawn distinct-ignoring-case because {@code PropertyList.get} matches keys with
     * {@code equalsIgnoreCase}; generating {@code "a"} and {@code "A"} would test the collision
     * rather than the round trip. {@code Provider} is excluded because {@code put} documents that
     * later values of {@code Provider} deliberately do not supersede earlier ones — asserting that
     * it round-trips would be asserting against the documented design.
     */
    @HegelTest(testCases = 400)
    void connectStringRoundTripsForOrdinaryValues(TestCase tc) {
        List<String> keys = tc.draw(
                Generators.distinctIgnoringCase(5)
                        .filter(ks -> ks.stream().noneMatch(k -> k.equalsIgnoreCase("Provider"))),
                "keys");
        List<String> values = tc.draw(
                dev.hegel.Generators.lists(ordinaryConnectStringValue())
                        .minSize(keys.size())
                        .maxSize(keys.size()),
                "values");

        Util.PropertyList original = new Util.PropertyList();
        for (int i = 0; i < keys.size(); i++) {
            original.put(keys.get(i), values.get(i));
        }

        String text = original.toString();
        Util.PropertyList reparsed = Util.parseConnectString(text);

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            assertEquals(
                    values.get(i),
                    reparsed.get(key),
                    "value for key " + show(key) + " did not survive the round trip through " + show(text));
        }
    }

    /**
     * Characterisation test for a KNOWN DEFECT (issue #142): {@code PropertyList.toString()} emits
     * values that do not parse back to themselves.
     *
     * <p>It quotes a value only when the value contains {@code ';'}, and even then it suppresses
     * the delimiter if the value already starts or ends with {@code '\''} — logic its own source
     * flags with a {@code REVIEW:} comment. The three witnesses below were found by generalising
     * {@link #connectStringRoundTripsForOrdinaryValues} to unrestricted values.
     *
     * <p>This test asserts the <em>current, wrong</em> behaviour on purpose. Fixing
     * {@code toString()} is not a self-contained change: {@code UtilTestCase
     * .testParseConnectStringComplex} asserts the lossy output verbatim as the expected format
     * (a value ending in a space and a value beginning with {@code '='} are both written
     * unquoted there), so a fix has to change that test's expectations too — a product decision
     * about whether {@code toString()} is a faithful serialisation or a display format, not a
     * decision for a test suite to take unilaterally.
     *
     * <p>When someone does fix it, this test fails and points straight at the work.
     */
    @Test
    void connectStringToStringIsLossyForDelimiterValues() {
        // A value beginning with a quote and containing a semicolon: the leading delimiter is
        // suppressed, so "''" reads back as an empty quoted value and ";x" is silently dropped.
        assertEquals("", roundTrip("k", "';x"), "witness 1 changed — re-check the defect");

        // A value that is a lone quote: no semicolon, so it is not quoted at all, and the bare
        // quote then opens a literal that never closes.
        assertTrue(
                roundTripThrows("k", "'"),
                "witness 2 changed — a lone-quote value no longer fails to parse");

        // A value with a trailing space: emitted unquoted, and the parser trims unquoted values.
        assertEquals("a", roundTrip("k", "a "), "witness 3 changed — re-check the defect");
    }

    /**
     * Parsing a connect string never throws.
     *
     * <p>Connect strings arrive from configuration files and from XMLA requests, so the parser is
     * reachable with fully arbitrary input. Its contract is to return a {@link Util.PropertyList} —
     * a {@link StringIndexOutOfBoundsException} escaping from it is a crash on hostile input, not a
     * rejection.
     *
     * <p>This property found a surviving instance of <a
     * href="http://jira.pentaho.com/browse/MONDRIAN-397">MONDRIAN-397</a> ("connect string parser
     * gives StringIndexOutOfBoundsException instead of a meaningful error"): every input ending in
     * {@code "=="} crashed. {@code UtilTestCase.testBugMondrian397} covers the trailing-{@code ';'}
     * and trailing-space cases the original reporter happened to hit; the {@code "=="} case sat
     * untouched behind them until this generator found it. Fixed in
     * {@code Util.ConnectStringParser.parseName}.
     */
    @HegelTest(testCases = 500)
    void parseConnectStringNeverThrowsOnArbitraryInput(TestCase tc) {
        String s = tc.draw(Generators.adversarialText(), "s");

        Util.PropertyList list;
        try {
            list = Util.parseConnectString(s);
        } catch (RuntimeException e) {
            // A quoted value that is never closed is a genuine syntax error and reporting it is
            // correct behaviour; anything else is the parser walking off its own input.
            if (e.getMessage() != null && e.getMessage().contains("unterminated quoted value")) {
                return;
            }
            throw new AssertionError("parseConnectString(" + show(s) + ") threw " + e.getClass().getName() + ": "
                    + e.getMessage(), e);
        }
        assertTrue(list != null, "parser returned null rather than an empty list");
    }

    private static String roundTrip(String key, String value) {
        Util.PropertyList list = new Util.PropertyList();
        list.put(key, value);
        return Util.parseConnectString(list.toString()).get(key);
    }

    private static boolean roundTripThrows(String key, String value) {
        try {
            roundTrip(key, value);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Values free of the characters {@code PropertyList.toString} mishandles: the separator, both
     * quote characters, and leading/trailing whitespace (unquoted values are trimmed on the way
     * back in).
     */
    private static dev.hegel.Generator<String> ordinaryConnectStringValue() {
        return Generators.adversarialText()
                .filter(v -> v.indexOf(';') < 0
                        && v.indexOf('\'') < 0
                        && v.indexOf('"') < 0
                        && v.equals(v.trim())
                        && !v.startsWith("="));
    }

    // ------------------------------------------------------------------
    // Independent oracles. Deliberately not built from Mondrian code.
    // ------------------------------------------------------------------

    /**
     * Decodes a literal delimited by {@code q} in which an embedded delimiter is doubled — the rule
     * shared by SQL {@code '...''...'} and MDX {@code "..."" ..."}.
     *
     * @throws AssertionError if the literal is not well formed, since a malformed literal is itself
     *     a failure of the quoter under test
     */
    private static String decodeDoubledLiteral(String literal, char q) {
        assertTrue(
                literal.length() >= 2 && literal.charAt(0) == q && literal.charAt(literal.length() - 1) == q,
                () -> "not a " + q + "-delimited literal: " + show(literal));
        StringBuilder out = new StringBuilder();
        int i = 1;
        int end = literal.length() - 1;
        while (i < end) {
            char c = literal.charAt(i);
            if (c == q) {
                assertTrue(i + 1 < end && literal.charAt(i + 1) == q, () -> "unescaped " + q + " in " + show(literal));
                out.append(q);
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Scans a SQL single-quoted literal from offset 0 and returns the offset just past its closing
     * quote — i.e. where a SQL parser would resume reading statement text.
     *
     * @return the end offset, or 0 if the input does not start a literal at all
     */
    private static int scanSqlLiteral(String s) {
        if (s.isEmpty() || s.charAt(0) != '\'') {
            return 0;
        }
        int i = 1;
        while (i < s.length()) {
            if (s.charAt(i) == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i += 2; // doubled quote: an escaped ' inside the literal
                } else {
                    return i + 1; // lone quote: the literal ends here
                }
            } else {
                i++;
            }
        }
        return i; // unterminated literal
    }

    /**
     * Names as a schema can actually contain them: anything except U+0000.
     *
     * <p>NUL is excluded because a quoted identifier containing one makes olap4j's
     * {@code IdentifierParser} throw {@code IllegalArgumentException: Expected ']'}. That parser is
     * a vendored third-party dependency ({@code lib/repo}), not Mondrian code, so the fix does not
     * belong in this repository; and a NUL inside a dimension or member name is not reachable from
     * any real schema — XML 1.0 forbids the character outright.
     *
     * <p>A full sweep of the BMP confirmed NUL is the <em>only</em> excluded codepoint: all other
     * 65,535 round-trip exactly. That is a genuinely strong result for this pair, and stating the
     * exclusion precisely is what makes the rest of the claim worth anything.
     */
    private static dev.hegel.Generator<String> schemaName() {
        return Generators.nonEmptyAdversarialText().filter(s -> s.indexOf('\0') < 0);
    }

    /**
     * Parses, re-throwing with the input escaped.
     *
     * <p>Not decoration. The first counterexample this suite produced printed as {@code name = " "}
     * and looked like an ordinary space; it was U+0000, and the raw rendering sent the
     * investigation down the wrong path entirely. A counterexample you cannot read is a
     * counterexample you will misdiagnose.
     */
    private static List<Id.Segment> parse(String quoted) {
        try {
            return Util.parseIdentifier(quoted);
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "parseIdentifier(" + show(quoted) + ") threw " + e.getClass().getName() + ": " + e.getMessage(), e);
        }
    }

    private static String nameOf(Id.Segment segment) {
        return ((Id.NameSegment) segment).getName();
    }

    /** Renders a string with escapes so a counterexample containing control characters is legible. */
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
