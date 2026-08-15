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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Legacy-vs-Calcite parity matrix for the slicer shapes that reach
 * {@code CalcitePlannerAdapters.addSlicerFilters} — the code that pins
 * evaluator context members as equality filters on a member read.
 *
 * <p>saiku#1665 was one cell of this matrix (compound slicer + NON EMPTY
 * level members). The failure mode is generic: the evaluator context is a
 * <em>lossy</em> projection of the query's real slicer — it keeps only the
 * last member of a compound set — so any read that pins it can silently
 * narrow to one position and return an empty axis with no error. This test
 * crosses the slicer shapes against the axis shapes that route through the
 * two {@code addSlicerFilters} call sites:
 *
 * <ul>
 *   <li>{@code SqlContextConstraint} — plain NON EMPTY level members,
 *       children, descendants, named sets;</li>
 *   <li>{@code RolapNativeSet.SetConstraint} — CrossJoin /
 *       NonEmptyCrossJoin, TopCount and Filter native evaluation.</li>
 * </ul>
 *
 * <p>Legacy is the reference: every case asserts the two backends return
 * identical cell-sets. Cases that must return data also assert the result
 * is non-empty, so a shared regression cannot pass by both backends
 * collapsing to nothing.
 */
public class CompoundSlicerShapeMatrixTest {

    /** Slicer whose LAST member — the one the evaluator context keeps —
     *  has no fact rows. This is the saiku#1665 trigger. */
    private static final String EMPTY_TAIL =
        "WHERE {[Time].[Time].[1997], [Time].[Time].[1998]}";

    @BeforeAll public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    @AfterEach public void clearBackend() {
        System.clearProperty("mondrian.backend");
        SegmentLoader.clearCalcitePlannerCache();
    }

    /**
     * Axis shapes, each paired with the {@link #EMPTY_TAIL} compound
     * slicer. Every one of these reads is constrained by the evaluator
     * context, so every one could pin [Time].[1998] and collapse.
     */
    private static List<Arguments> axisShapes() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(shape(
            "axis-level-members",
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY {[Product].[Products].[Product Family].Members}"
            + " ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        cases.add(shape(
            "axis-member-children",
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY {[Product].[Products].[Drink].Children} ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        cases.add(shape(
            "axis-descendants",
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY Descendants([Product].[Products].[Drink],\n"
            + "   [Product].[Products].[Product Department]) ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        cases.add(shape(
            "axis-crossjoin",
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY CrossJoin(\n"
            + "   {[Product].[Products].[Product Family].Members},\n"
            + "   {[Store].[Stores].[Store Country].Members}) ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        cases.add(shape(
            "axis-non-empty-crossjoin",
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NonEmptyCrossJoin(\n"
            + "   {[Product].[Products].[Product Family].Members},\n"
            + "   {[Store].[Stores].[Store Country].Members}) ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        cases.add(shape(
            "axis-topcount",
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY TopCount(\n"
            + "   {[Product].[Products].[Product Family].Members}, 2,\n"
            + "   [Measures].[Store Sales]) ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        cases.add(shape(
            "axis-filter",
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY Filter(\n"
            + "   {[Product].[Products].[Product Family].Members},\n"
            + "   [Measures].[Store Sales] > 100) ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        // The shape Saiku Studio emits: a named set referenced by a
        // NON EMPTY axis.
        cases.add(shape(
            "axis-named-set",
            "WITH SET [~ROWS] AS\n"
            + " '{[Product].[Products].[Product Family].Members}'\n"
            + "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY [~ROWS] ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        cases.add(shape(
            "axis-both-non-empty-crossjoin-columns",
            "SELECT NON EMPTY CrossJoin(\n"
            + "   {[Measures].[Store Sales], [Measures].[Unit Sales]},\n"
            + "   {[Store].[Stores].[Store Country].Members}) ON COLUMNS,\n"
            + " NON EMPTY {[Product].[Products].[Product Family].Members}"
            + " ON ROWS\n"
            + "FROM [Sales] " + EMPTY_TAIL));
        return cases;
    }

    /**
     * Slicer shapes against a fixed NON EMPTY level-members axis. Covers
     * the ordering of the empty member, arity, cross-hierarchy tuples,
     * repeated members, a non-Time dimension, and the calculated-member
     * spelling of the same rollup — plus the two single-position controls
     * that were never broken.
     */
    private static List<Arguments> slicerShapes() {
        String axis =
            "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
            + " NON EMPTY {[Product].[Products].[Product Family].Members}"
            + " ON ROWS\n"
            + "FROM [Sales] ";
        List<Arguments> cases = new ArrayList<>();
        cases.add(shape(
            "slicer-empty-last", axis + EMPTY_TAIL));
        cases.add(shape(
            "slicer-empty-first",
            axis + "WHERE {[Time].[Time].[1998], [Time].[Time].[1997]}"));
        cases.add(shape(
            "slicer-three-members",
            axis + "WHERE {[Time].[Time].[1997].[Q1],"
            + " [Time].[Time].[1997].[Q2], [Time].[Time].[1998]}"));
        cases.add(shape(
            "slicer-mixed-levels",
            axis
            + "WHERE {[Time].[Time].[1997].[Q1], [Time].[Time].[1998]}"));
        cases.add(shape(
            "slicer-cross-hierarchy-tuples",
            axis
            + "WHERE {([Time].[Time].[1997], [Store].[Stores].[USA].[CA]),\n"
            + "       ([Time].[Time].[1998], [Store].[Stores].[USA].[WA])}"));
        cases.add(shape(
            "slicer-repeated-member-in-tuples",
            axis
            + "WHERE {([Time].[Time].[1997], [Store].[Stores].[USA].[CA]),\n"
            + "       ([Time].[Time].[1997], [Store].[Stores].[USA].[WA])}"));
        cases.add(shape(
            "slicer-non-time-dimension",
            axis
            + "WHERE {[Store].[Stores].[USA].[CA],"
            + " [Store].[Stores].[USA].[WA]}"));
        cases.add(shape(
            "slicer-two-compound-hierarchies",
            axis
            + "WHERE CrossJoin(\n"
            + "  {[Time].[Time].[1997], [Time].[Time].[1998]},\n"
            + "  {[Store].[Stores].[USA].[CA],"
            + " [Store].[Stores].[USA].[WA]})"));
        // Same rollup spelled as a calculated member: the context member
        // is calculated, so it is skipped rather than pinned. Included so
        // the two spellings stay in agreement.
        cases.add(shape(
            "slicer-aggregate-calc-member",
            "WITH MEMBER [Time].[Time].[97 plus 98] AS\n"
            + " 'Aggregate({[Time].[Time].[1997], [Time].[Time].[1998]})'\n"
            + axis + "WHERE {[Time].[Time].[97 plus 98]}"));
        // Controls: single-position slicers must keep their pin.
        cases.add(shape(
            "control-single-member-set",
            axis + "WHERE {[Time].[Time].[1997]}"));
        cases.add(shape(
            "control-bare-member",
            axis + "WHERE ([Time].[Time].[1997])"));
        cases.add(shape(
            "control-no-slicer", axis));
        // Every slicer position is empty: an empty axis is the CORRECT
        // answer here, so parity is asserted without a non-empty check.
        cases.add(emptyShape(
            "slicer-all-positions-empty",
            axis + "WHERE {[Time].[Time].[1998].[Q1],"
            + " [Time].[Time].[1998].[Q2]}"));
        return cases;
    }

    static Stream<Arguments> matrix() {
        List<Arguments> all = new ArrayList<>(axisShapes());
        all.addAll(slicerShapes());
        return all.stream();
    }

    private static Arguments shape(String name, String mdx) {
        return Arguments.of(name, mdx, true);
    }

    private static Arguments emptyShape(String name, String mdx) {
        return Arguments.of(name, mdx, false);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    public void legacyAndCalciteAgree(
        String name, String mdx, boolean expectRows)
    {
        String legacy = runOn("legacy", name, mdx);
        String calcite = runOn("calcite", name, mdx);
        assertEquals(
            "backend drift for " + name + ":\n" + mdx, legacy, calcite);
        if (expectRows) {
            assertTrue(
                name + " must return data on both backends — an empty axis "
                + "here is the saiku#1665 failure mode:\n" + calcite,
                calcite.contains("Row #0:"));
        } else {
            assertTrue(
                name + " must return an empty axis:\n" + calcite,
                !calcite.contains("Row #0:"));
        }
    }

    private static String runOn(String backend, String name, String mdx) {
        System.setProperty("mondrian.backend", backend);
        SegmentLoader.clearCalcitePlannerCache();
        return FoodMartCapture.executeCold(
            new NamedMdx(name, mdx), null).cellSet;
    }
}

// End CompoundSlicerShapeMatrixTest.java
