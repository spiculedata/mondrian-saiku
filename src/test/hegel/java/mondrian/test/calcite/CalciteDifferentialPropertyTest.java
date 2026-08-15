/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule / Saiku community
// All Rights Reserved.
*/
package mondrian.test.calcite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import mondrian.property.MdxGenerator;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.calcite.corpus.SmokeCorpus.NamedMdx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Differential properties for the Calcite backend: <strong>routing a query through Calcite must not
 * change its answer.</strong>
 *
 * <p>The existing equivalence harness compares a <em>recorded corpus</em> of queries against golden
 * files. That is the right tool for locking in known-good behaviour, but it can only ever cover the
 * queries someone thought to record. This class reuses the same machinery with generated queries
 * instead, so the comparison reaches shapes nobody wrote down.
 *
 * <p>No golden files are involved. {@code EquivalenceHarness.run} gates on a per-query golden and
 * would reject a generated query outright ("golden not found"), so this goes straight to the
 * primitive underneath — {@link FoodMartCapture#executeCold} — and compares the two cell sets
 * directly. That is the harness's own Gate 2 ({@code CELL_SET_DRIFT}), which is the gate that
 * actually asserts equivalence; the other gates are about SQL and plan drift, which are
 * implementation detail rather than correctness.
 *
 * <p><strong>Opt-in, because it is slow.</strong> {@code executeCold} flushes the schema cache and
 * builds a fresh connection for <em>each</em> of the two runs, by design — a warm cache would let
 * the second run answer from the first one's segments and the comparison would prove nothing. That
 * costs seconds per test case, so this class is disabled unless {@code -Dhegel.calcite=true} is
 * given and does not run in the default {@code -Phegel} suite:
 *
 * <pre>{@code   mvn test -Phegel -Dhegel.calcite=true}</pre>
 */
@EnabledIfSystemProperty(named = "hegel.calcite", matches = "true")
class CalciteDifferentialPropertyTest {

    @BeforeAll
    static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    /**
     * A generated query returns the same cell set with and without the Calcite interceptor.
     *
     * <p>The case count is deliberately small — each case is two cold executions, and the value here
     * is reaching query <em>shapes</em> the recorded corpus does not contain rather than running a
     * large number of them. Raise it when investigating a specific drift.
     */
    @HegelTest(testCases = 8)
    void calciteAndLegacyProduceTheSameCellSet(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        String mdx = "SELECT " + set + " ON COLUMNS, {[Measures].[Unit Sales]} ON ROWS FROM [Sales]";

        NamedMdx query = new NamedMdx("generated", mdx);

        FoodMartCapture.CapturedRun legacy = FoodMartCapture.executeCold(query, null);
        FoodMartCapture.CapturedRun calcite =
                FoodMartCapture.executeCold(query, CalcitePassThrough.class.getName());

        assertEquals(
                legacy.cellSet,
                calcite.cellSet,
                () -> "Calcite changed the result.\n  mdx: " + mdx);
    }

    /** The same, for a two-axis query with an aggregate, which exercises the cell path harder. */
    @HegelTest(testCases = 8)
    void calciteAndLegacyAgreeOnAggregatedQueries(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        String mdx = "WITH MEMBER [Measures].[__total] AS 'Sum(" + set + ", [Measures].[Unit Sales])' "
                + "SELECT {[Measures].[Unit Sales], [Measures].[__total]} ON COLUMNS, "
                + set + " ON ROWS FROM [Sales]";

        NamedMdx query = new NamedMdx("generated-agg", mdx);

        FoodMartCapture.CapturedRun legacy = FoodMartCapture.executeCold(query, null);
        FoodMartCapture.CapturedRun calcite =
                FoodMartCapture.executeCold(query, CalcitePassThrough.class.getName());

        assertEquals(
                legacy.cellSet,
                calcite.cellSet,
                () -> "Calcite changed the aggregated result.\n  mdx: " + mdx);
    }
}
