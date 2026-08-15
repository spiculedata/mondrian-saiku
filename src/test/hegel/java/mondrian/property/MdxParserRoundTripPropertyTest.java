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

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import mondrian.olap.Query;
import mondrian.olap.Util;

/**
 * Round-trip properties for the MDX parser and unparser.
 *
 * <p>{@code Util.unparse} is not a debugging convenience. Mondrian round-trips MDX through it in
 * anger: statements are re-serialised for the query log and the profiler, XMLA echoes queries back,
 * and {@code CmdRunner} prints them. If unparsing a query produces text that parses to a
 * <em>different</em> query, then what Mondrian reports it ran is not what it ran — which turns
 * every downstream diagnosis into a wild goose chase.
 *
 * <p>The property asserted is a fixpoint rather than string equality with the input:
 *
 * <pre>{@code   unparse(parse(unparse(parse(q)))) == unparse(parse(q))}</pre>
 *
 * <p>The first unparse legitimately normalises ({@code crossjoin} becomes {@code Crossjoin},
 * {@code on 0} becomes {@code ON COLUMNS}), so demanding the original text back would assert
 * against the design. Demanding <em>stability</em> from the second pass onwards is the real
 * contract, and it is exactly what fails when an operator is unparsed without the parentheses its
 * own precedence needs.
 */
class MdxParserRoundTripPropertyTest {

    /**
     * Unparsing is idempotent for generated set expressions on an axis.
     *
     * <p>Set expressions are where nesting and precedence actually arise, so this is the shape most
     * likely to expose a missing parenthesis or a lost argument.
     */
    @HegelTest(testCases = 150)
    void unparseIsIdempotentForSetExpressions(TestCase tc) {
        String setExpr = tc.draw(MdxGenerator.setExpr(), "setExpr");
        assertUnparseIsIdempotent("SELECT " + setExpr + " ON COLUMNS FROM " + FoodMart.CUBE);
    }

    /** The same, for a query with two axes and a slicer — the full statement shape. */
    @HegelTest(testCases = 100)
    void unparseIsIdempotentForFullQueries(TestCase tc) {
        String rows = tc.draw(MdxGenerator.setExpr(), "rows");
        String mdx = "SELECT {" + FoodMart.ADDITIVE_MEASURE + "} ON COLUMNS, " + rows + " ON ROWS FROM "
                + FoodMart.CUBE;
        assertUnparseIsIdempotent(mdx);
    }

    /**
     * A calculated member survives the round trip.
     *
     * <p>{@code WITH MEMBER} bodies are unparsed inside single quotes, so an expression containing
     * a quote — or one whose own unparse introduces one — is where the escaping can come apart.
     */
    @HegelTest(testCases = 100)
    void unparseIsIdempotentForCalculatedMembers(TestCase tc) {
        String setExpr = tc.draw(MdxGenerator.setExpr(), "setExpr");
        String mdx = "WITH MEMBER [Measures].[Calc] AS 'Count(" + setExpr + ")' "
                + "SELECT {[Measures].[Calc]} ON COLUMNS FROM " + FoodMart.CUBE;
        assertUnparseIsIdempotent(mdx);
    }

    /**
     * Parses, unparses, and asserts the result is stable under a second pass.
     *
     * <p>Both the once- and twice-unparsed forms are reported on failure, because the diff between
     * them <em>is</em> the diagnosis — it points at the single node whose unparse is unstable.
     */
    private static void assertUnparseIsIdempotent(String mdx) {
        String once = unparse(mdx);
        String twice = unparse(once);
        assertEquals(
                once,
                twice,
                () -> "unparse is not idempotent.\n  original: " + mdx + "\n  once:     " + once + "\n  twice:    "
                        + twice);
    }

    private static String unparse(String mdx) {
        try {
            Query query = FoodMart.connection().parseQuery(mdx);
            return Util.unparse(query);
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "failed to parse generated MDX.\n  mdx: " + mdx + "\n  " + e.getClass().getName() + ": "
                            + e.getMessage(),
                    e);
        }
    }
}
