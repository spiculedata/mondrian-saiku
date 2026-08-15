/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.test.calcite;

import mondrian.rolap.agg.SegmentLoader;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.calcite.corpus.SmokeCorpus.NamedMdx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * saiku#1665: {@code NON EMPTY} on an axis combined with a compound
 * (multi-member) slicer returned an <em>empty</em> axis under the Calcite
 * backend while legacy returned the correct rows — a silent wrong answer.
 *
 * <p>Cause: the evaluator context holds only the <em>last</em> member of a
 * compound slicer, and the Calcite tuple read pinned it as an equality
 * filter. With {@code WHERE {[Time].[1997], [Time].[1998]}} that pinned
 * [Time].[1998], which has no fact rows (the Sales cube reads
 * {@code sales_fact_1997} only), so the member read returned nothing and
 * both NON EMPTY axes collapsed. Legacy drops the restriction for any
 * hierarchy with two or more slicer members
 * ({@code SqlConstraintUtils.removeMultiPositionSlicerMembers}); the fix
 * shares that rule with the Calcite path.
 *
 * <p>Each case asserts backend equivalence — legacy is the reference — plus
 * the expected shape, so the test cannot pass by both backends being empty.
 */
public class CompoundSlicerNonEmptyTest {

    private static final String ROWS =
        "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
        + "       NON EMPTY {[Product].[Products].[Product Family].Members}"
        + " ON ROWS\n"
        + "FROM [Sales]\n";

    @BeforeAll public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    @AfterEach public void clearBackend() {
        System.clearProperty("mondrian.backend");
        SegmentLoader.clearCalcitePlannerCache();
    }

    /** The reported repro: one slicer member has data, the other has none. */
    @Test public void compoundSlicerWithEmptyMember() {
        String calcite = assertBackendsAgree(
            ROWS + "WHERE {[Time].[Time].[1997], [Time].[Time].[1998]}");
        assertTrue(
            "1997 data must survive the empty 1998 slicer member: " + calcite,
            calcite.contains("[Product].[Products].[Drink]")
                && calcite.contains("48,836.21"));
    }

    /** Slicer members at different levels of the same hierarchy. */
    @Test public void compoundSlicerAcrossLevels() {
        String calcite = assertBackendsAgree(
            ROWS
            + "WHERE {[Time].[Time].[1997].[Q1], [Time].[Time].[1998]}");
        assertTrue(
            "Q1 1997 data must survive: " + calcite,
            calcite.contains("11,585.80"));
    }

    /** Compound slicer of tuples spanning two hierarchies. */
    @Test public void compoundSlicerAcrossHierarchies() {
        String calcite = assertBackendsAgree(
            ROWS
            + "WHERE {([Time].[Time].[1997], [Store].[Stores].[USA].[CA]),\n"
            + "       ([Time].[Time].[1998], [Store].[Stores].[USA].[WA])}");
        assertTrue(
            "CA 1997 + WA 1998 rollup must survive: " + calcite,
            calcite.contains("14,203.24"));
    }

    /**
     * A hierarchy repeated across the tuples with the SAME member is a
     * single slicer position and stays pinned — the read must still be
     * restricted to [Time].[1997].
     */
    @Test public void repeatedMemberStaysPinned() {
        String calcite = assertBackendsAgree(
            ROWS
            + "WHERE {([Time].[Time].[1997], [Store].[Stores].[USA].[CA]),\n"
            + "       ([Time].[Time].[1997], [Store].[Stores].[USA].[WA])}");
        assertTrue(
            "CA + WA 1997 rollup must survive: " + calcite,
            calcite.contains("[Product].[Products].[Food]"));
    }

    /** Every slicer member empty — the axis is legitimately empty. */
    @Test public void compoundSlicerAllEmptyYieldsEmptyAxis() {
        String calcite = assertBackendsAgree(
            ROWS
            + "WHERE {[Time].[Time].[1998].[Q1], [Time].[Time].[1998].[Q2]}");
        assertTrue(
            "no 1998 data exists, so no product family is non-empty: "
            + calcite,
            !calcite.contains("[Product].[Products].[Drink]"));
    }

    /** Control: a single-member slicer set was never affected. */
    @Test public void singleMemberSlicerSetUnaffected() {
        String calcite =
            assertBackendsAgree(ROWS + "WHERE {[Time].[Time].[1997]}");
        assertTrue(
            "1997 data must be present: " + calcite,
            calcite.contains("48,836.21"));
    }

    /**
     * Locks the mechanism, not just the outcome. In one query [Time] has a
     * single slicer position (repeated across both tuples) and [Store] has
     * two: the fact-rooted member read must keep the [Time] pin and drop
     * the [Store] one. Asserting on the emitted SQL is what stops a future
     * refactor from restoring the pin and reintroducing saiku#1665 in a
     * shape the cell-set assertions happen not to distinguish.
     */
    @Test public void pinIsDroppedOnlyForMultiPositionHierarchies() {
        String memberRead = calciteMemberReadSql(
            ROWS
            + "WHERE {([Time].[Time].[1997], [Store].[Stores].[USA].[CA]),\n"
            + "       ([Time].[Time].[1997], [Store].[Stores].[USA].[WA])}");
        assertTrue(
            "single-position [Time] must stay pinned: " + memberRead,
            memberRead.contains("1997"));
        assertFalse(
            "multi-position [Store] must not be pinned: " + memberRead,
            memberRead.contains("store_state"));
    }

    /** The reported repro, at the SQL level: no year pin on the read. */
    @Test public void compoundSlicerEmitsNoPinnedYearFilter() {
        String memberRead = calciteMemberReadSql(
            ROWS + "WHERE {[Time].[Time].[1997], [Time].[Time].[1998]}");
        assertFalse(
            "compound [Time] slicer must not pin a year on the member "
            + "read: " + memberRead,
            memberRead.contains("the_year"));
    }

    /**
     * Returns the fact-rooted member-read SQL Calcite emits for {@code mdx}
     * — the statement that enumerates the product families off the fact
     * table. Fails the test if no such statement was captured, so the
     * assertions above can never pass vacuously.
     */
    private static String calciteMemberReadSql(String mdx) {
        System.setProperty("mondrian.backend", "calcite");
        SegmentLoader.clearCalcitePlannerCache();
        FoodMartCapture.CapturedRun run = FoodMartCapture.executeCold(
            new NamedMdx("compound-slicer-sql", mdx), null);
        StringBuilder found = new StringBuilder();
        for (CapturedExecution e : run.executions) {
            // The member read: projects product_family off the fact, and
            // (unlike the segment load) selects no measure.
            if (e.sql.contains("product_family")
                && e.sql.contains("sales_fact_1997")
                && !e.sql.contains("SUM("))
            {
                found.append(e.sql).append('\n');
            }
        }
        assertTrue(
            "expected a fact-rooted member read in:\n" + run.executions,
            found.length() > 0);
        return found.toString();
    }

    /**
     * Runs {@code mdx} on both backends, asserts the cell-sets match and
     * returns the Calcite cell-set for shape assertions.
     */
    private static String assertBackendsAgree(String mdx) {
        String legacy = runOn("legacy", mdx);
        String calcite = runOn("calcite", mdx);
        assertEquals(
            "calcite must match legacy for: " + mdx, legacy, calcite);
        return calcite;
    }

    private static String runOn(String backend, String mdx) {
        System.setProperty("mondrian.backend", backend);
        SegmentLoader.clearCalcitePlannerCache();
        return FoodMartCapture.executeCold(
            new NamedMdx("compound-slicer-non-empty", mdx), null).cellSet;
    }
}

// End CompoundSlicerNonEmptyTest.java
