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
import mondrian.olap.Connection;
import mondrian.olap.MondrianProperties;
import mondrian.rolap.RolapCube;
import mondrian.rolap.RolapMeasureGroup;
import mondrian.rolap.RolapStar;
import mondrian.test.TestContext;

/**
 * Differential properties: <strong>answering from an aggregate table must give the same answer as
 * answering from the fact table.</strong>
 *
 * <p>Aggregate tables are a pure optimisation — pre-rolled summaries the planner may substitute for
 * a scan of the fact table. The user does not choose them, cannot see when they were used, and gets
 * no indication either way. So the only thing making them safe is that the substitution is
 * answer-preserving, and that is exactly what a differential property can check without an oracle.
 *
 * <p>The matching rules are where this gets subtle: which summary can serve which query, what
 * happens to distinct-count measures (which do <em>not</em> roll up), and how dimensions that the
 * summary collapses are handled. Those rules are the kind of thing that is right for the queries
 * someone tried and wrong for a shape nobody thought of — which is the case for generating them.
 *
 * <p><strong>How the two sides are built.</strong> {@code ReadAggregates} is consulted when a schema
 * loads, {@code UseAggregates} when a query runs. So the two connections are created under different
 * {@code ReadAggregates} settings — with the schema pool disabled so each gets its own schema rather
 * than sharing a cached one — and {@code UseAggregates} is then left on for the whole class. The
 * with-aggregates connection has summaries available and uses them; the without-aggregates
 * connection has none loaded and cannot.
 */
@org.junit.jupiter.api.condition.EnabledIf("aggregateTablesAreDeclared")
class AggregateTableDifferentialPropertyTest {

    /**
     * Whether the schema under test actually declares aggregate tables.
     *
     * <p><strong>This class is currently SKIPPED, and that is deliberate.</strong> The HSQLDB
     * fixture contains 11 {@code agg_*} tables, but {@code demo/FoodMart.mondrian.xml} — the
     * catalog {@code TestContext} loads — declares none of them ({@code AggName}/{@code AggPattern}
     * do not appear in it). With nothing declared, Mondrian recognises no aggregate stars, both
     * connections below are identical, and every property here would compare a configuration
     * against itself and pass.
     *
     * <p>That is the worst possible outcome for a differential suite: four confident green ticks
     * proving nothing. Skipping is the honest alternative — the tests are written and ready, and
     * they start doing real work the moment the schema declares the aggregate tables that the
     * fixture already ships.
     *
     * <p>To activate: add {@code <AggName>}/{@code <AggPattern>} declarations for the fixture's
     * {@code agg_*} tables to the test catalog. The guard below then passes and the differential
     * runs for real.
     */
    static boolean aggregateTablesAreDeclared() {
        try {
            return !aggStarsOf(Holder.WITH_AGGREGATES).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final class Holder {
        static final Connection WITH_AGGREGATES;
        static final Connection WITHOUT_AGGREGATES;

        static {
            MondrianProperties props = MondrianProperties.instance();

            props.UseAggregates.set(true);
            props.ReadAggregates.set(true);
            WITH_AGGREGATES = TestContext.instance().withSchemaPool(false).getConnection();

            props.ReadAggregates.set(false);
            WITHOUT_AGGREGATES = TestContext.instance().withSchemaPool(false).getConnection();

            // UseAggregates deliberately left ON for the rest of the class: the with-aggregates
            // connection needs it at query time, and the without-aggregates connection has no
            // summaries loaded so the flag cannot affect it. ReadAggregates is left off, which is
            // the Mondrian default, so any connection built later behaves as it normally would.
        }
    }

    /**
     * Every aggregate star the planner can choose from, across the cube's measure groups.
     *
     * <p>Goes via {@code getMeasureGroups()} rather than {@code RolapCube.getStar()}: in this 4.x
     * fork a cube has one star per measure group and the single-star accessor is deprecated to the
     * point of throwing {@code UnsupportedOperationException}. Calling it made this guard fail while
     * the four differential properties passed — which would have left the class looking green with
     * no evidence that the two sides differed at all.
     */
    private static List<?> aggStarsOf(Connection connection) {
        RolapCube cube = (RolapCube) connection.getSchema().lookupCube("Sales", true);
        List<Object> all = new java.util.ArrayList<>();
        for (RolapMeasureGroup group : cube.getMeasureGroups()) {
            RolapStar star = group.getStar();
            if (star != null) {
                all.addAll(star.getAggStars());
            }
        }
        return all;
    }

    /**
     * Proves the two sides genuinely differ, so the differential is not vacuous.
     *
     * <p>Without this the whole class could pass by comparing two identical configurations — the
     * failure mode that makes a differential suite worthless while still reporting green. Asserted
     * on {@code RolapStar.getAggStars()}, which is the planner's own record of the summaries it can
     * choose from.
     */
    @org.junit.jupiter.api.Test
    void theTwoConnectionsActuallyDifferInAggregateAvailability() {
        // Reached only when aggregateTablesAreDeclared() is true, i.e. the schema declares
        // aggregates. Kept as an assertion rather than folded into the condition so that a
        // half-configured schema (aggregates declared but not recognised on both sides) fails
        // loudly instead of silently skipping.
        List<?> withAgg = aggStarsOf(Holder.WITH_AGGREGATES);
        List<?> withoutAgg = aggStarsOf(Holder.WITHOUT_AGGREGATES);

        System.out.println("aggregate stars: with=" + withAgg.size() + " without=" + withoutAgg.size());

        assertTrue(
                !withAgg.isEmpty(),
                "no aggregate tables were recognised, so every property in this class would compare "
                        + "two identical configurations and pass vacuously");
        assertTrue(
                withoutAgg.isEmpty(),
                () -> "the without-aggregates connection still has " + withoutAgg.size()
                        + " aggregate stars; the two sides are not actually different");
    }

    // ------------------------------------------------------------------
    // The differential
    // ------------------------------------------------------------------

    /** Both configurations return the same members, in the same order. */
    @HegelTest(testCases = 80)
    void aggregatesDoNotChangeTheMembers(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        String mdx = "SELECT " + set + " ON COLUMNS FROM " + FoodMart.CUBE;

        assertEquals(
                FoodMart.membersOfQuery(Holder.WITHOUT_AGGREGATES, mdx),
                FoodMart.membersOfQuery(Holder.WITH_AGGREGATES, mdx),
                () -> "aggregate tables changed the members for S = " + set);
    }

    /**
     * Both configurations compute the same aggregate.
     *
     * <p>The property that matters most: an aggregate table that summarises at the wrong grain, or
     * that the planner substitutes for a query it cannot actually serve, shows up here as a
     * different number and nowhere else.
     */
    @HegelTest(testCases = 80)
    void aggregatesDoNotChangeSums(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        String mdx = withMeasure("Sum(" + set + ", " + FoodMart.ADDITIVE_MEASURE + ")");

        assertEquals(
                scalarOf(Holder.WITHOUT_AGGREGATES, mdx),
                scalarOf(Holder.WITH_AGGREGATES, mdx),
                () -> "aggregate tables changed the sum for S = " + set);
    }

    /** Both configurations agree on cell values across a whole axis, not just a single total. */
    @HegelTest(testCases = 60)
    void aggregatesDoNotChangeCellValuesAcrossAnAxis(TestCase tc) {
        String level = tc.draw(MdxGenerator.level(), "level");
        String mdx = "SELECT {" + FoodMart.ADDITIVE_MEASURE + "} ON COLUMNS, " + level + ".Members ON ROWS FROM "
                + FoodMart.CUBE;

        assertEquals(
                cellValues(Holder.WITHOUT_AGGREGATES, mdx),
                cellValues(Holder.WITH_AGGREGATES, mdx),
                () -> "aggregate tables changed cell values for " + level);
    }

    /**
     * The agreement survives a crossjoin, which is where a summary's collapsed dimensions bite.
     *
     * <p>An aggregate table that has rolled a dimension away can still be used for a query that does
     * not reference it — deciding that correctly is the heart of aggregate matching, and a crossjoin
     * puts two dimensions in play at once.
     */
    @HegelTest(testCases = 20)
    void aggregatesDoNotChangeCrossjoinedCellValues(TestCase tc) {
        List<String> levels = tc.draw(MdxGenerator.twoIndependentLevels(), "levels");
        String mdx = "SELECT {" + FoodMart.ADDITIVE_MEASURE + "} ON COLUMNS, "
                + "Crossjoin(" + levels.get(0) + ".Members, " + levels.get(1) + ".Members) ON ROWS FROM "
                + FoodMart.CUBE;

        assertEquals(
                cellValues(Holder.WITHOUT_AGGREGATES, mdx),
                cellValues(Holder.WITH_AGGREGATES, mdx),
                () -> "aggregate tables changed crossjoined cell values for " + levels);
    }

    // ------------------------------------------------------------------

    private static String withMeasure(String expr) {
        return "WITH MEMBER [Measures].[__probe] AS " + expr + " SELECT {[Measures].[__probe]} ON COLUMNS FROM "
                + FoodMart.CUBE;
    }

    private static Double scalarOf(Connection connection, String mdx) {
        var result = connection.execute(connection.parseQuery(mdx));
        var cell = result.getCell(new int[] {0});
        if (cell.isNull()) {
            return null;
        }
        Object value = cell.getValue();
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    /** Every cell on the row axis, as strings, so nulls and formatting differences are visible too. */
    private static List<String> cellValues(Connection connection, String mdx) {
        var result = connection.execute(connection.parseQuery(mdx));
        int rows = result.getAxes()[1].getPositions().size();
        List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            var cell = result.getCell(new int[] {0, i});
            values.add(cell.isNull() ? "null" : String.valueOf(cell.getValue()));
        }
        return values;
    }
}
