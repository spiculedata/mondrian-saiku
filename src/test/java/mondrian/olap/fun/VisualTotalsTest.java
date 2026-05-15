/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2006-2012 Pentaho and others
// All Rights Reserved.
*/
package mondrian.olap.fun;

import mondrian.test.TestContext;

import junit.framework.TestCase;

import org.olap4j.*;
import org.olap4j.metadata.Member;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * <code>VisualTotalsTest</code> tests the internal functions defined in
 * {@link VisualTotalsFunDef}. Right now, only tests substitute().
 *
 * @author efine
 */
public class VisualTotalsTest extends TestCase {
    public void testSubstituteEmpty() {
        final String actual = VisualTotalsFunDef.substitute("", "anything");
        final String expected = "";
        assertEquals(expected, actual);
    }

    public void testSubstituteOneStarOnly() {
        final String actual = VisualTotalsFunDef.substitute("*", "anything");
        final String expected = "anything";
        assertEquals(expected, actual);
    }

    public void testSubstituteOneStarBegin() {
        final String actual =
            VisualTotalsFunDef.substitute("* is the word.", "Grease");
        final String expected = "Grease is the word.";
        assertEquals(expected, actual);
    }

    public void testSubstituteOneStarEnd() {
        final String actual =
            VisualTotalsFunDef.substitute(
                "Lies, damned lies, and *!", "statistics");
        final String expected = "Lies, damned lies, and statistics!";
        assertEquals(expected, actual);
    }

    public void testSubstituteTwoStars() {
        final String actual = VisualTotalsFunDef.substitute("**", "anything");
        final String expected = "*";
        assertEquals(expected, actual);
    }

    public void testSubstituteCombined() {
        final String actual =
            VisualTotalsFunDef.substitute(
                "*: see small print**** for *", "disclaimer");
        final String expected = "disclaimer: see small print** for disclaimer";
        assertEquals(expected, actual);
    }

    /**
     * Test case for bug <a href="http://jira.pentaho.com/browse/MONDRIAN-925">
     * MONDRIAN-925, "VisualTotals + drillthrough throws Exception"</a>.
     *
     * @throws java.sql.SQLException on error
     */
    public void testDrillthroughVisualTotal() throws SQLException {
        CellSet cellSet =
            TestContext.instance().executeOlap4jQuery(
                "select {[Measures].[Unit Sales]} on columns, "
                + "{VisualTotals("
                + "    {[Product].[Food].[Baked Goods].[Bread],"
                + "     [Product].[Food].[Baked Goods].[Bread].[Bagels],"
                + "     [Product].[Food].[Baked Goods].[Bread].[Muffins]},"
                + "     \"**Subtotal - *\")} on rows "
                + "from [Sales]");
        List<Position> positions = cellSet.getAxes().get(1).getPositions();
        Cell cell;
        ResultSet resultSet;
        Member member;

        cell = cellSet.getCell(Arrays.asList(0, 0));
        member = positions.get(0).getMembers().get(0);
        assertEquals("*Subtotal - Bread", member.getName());
        resultSet = cell.drillThrough();
        assertNull(resultSet);

        cell = cellSet.getCell(Arrays.asList(0, 1));
        member = positions.get(1).getMembers().get(0);
        assertEquals("Bagels", member.getName());
        resultSet = cell.drillThrough();
        assertNotNull(resultSet);
        resultSet.close();
    }

    /**
     * Test case for bug <a href="http://jira.pentaho.com/browse/MONDRIAN-1279">
     * MONDRIAN-1279, "VisualTotals name only applies to member name not
     * caption"</a>.
     *
     * @throws java.sql.SQLException on error
     */
    public void testVisualTotalCaptionBug() throws SQLException {
        CellSet cellSet =
            TestContext.instance().executeOlap4jQuery(
                "select {[Measures].[Unit Sales]} on columns, "
                + "VisualTotals("
                + "    {[Product].[Food].[Baked Goods].[Bread],"
                + "     [Product].[Food].[Baked Goods].[Bread].[Bagels],"
                + "     [Product].[Food].[Baked Goods].[Bread].[Muffins]},"
                + "     \"**Subtotal - *\") on rows "
                + "from [Sales]");
        List<Position> positions = cellSet.getAxes().get(1).getPositions();
        Cell cell;
        Member member;

        cell = cellSet.getCell(Arrays.asList(0, 0));
        member = positions.get(0).getMembers().get(0);
        assertEquals("*Subtotal - Bread", member.getName());
        assertEquals("*Subtotal - Bread", member.getCaption());
    }

    /**
     * Regression test for upstream
     * <a href="https://jira.pentaho.com/browse/MONDRIAN-1294">MONDRIAN-1294</a>:
     * <em>VisualTotals throws error on calculated members</em>. Before the
     * Sum-fallback in {@code AggregateFunDef.AggregateCalc.aggregate}, this
     * MDX threw {@code MondrianEvaluationException: Could not find an
     * aggregator in the current evaluation context} on the {@code *Subtotal -
     * Bread} cell because {@code [Measures].[Profit]} is a schema-level
     * calculated measure ({@code Store Sales - Store Cost}) and so carries no
     * declared aggregator. With the fix the visual-total cell evaluates as
     * {@code Sum(children, Profit)} — i.e. the sum of Bagels' and Muffins'
     * profit, which is what an analyst toggling "subtotals" expects.
     */
    public void testVisualTotalsOverCalculatedMeasure_MONDRIAN_1294() {
        TestContext.instance().assertQueryReturns(
            "select {[Measures].[Profit]} on columns,\n"
            + " VisualTotals(\n"
            + "   {[Product].[Products].[Food].[Baked Goods].[Bread],\n"
            + "    [Product].[Products].[Food].[Baked Goods].[Bread].[Bagels],\n"
            + "    [Product].[Products].[Food].[Baked Goods].[Bread].[Muffins]},\n"
            + "   \"**Subtotal - *\") on rows\n"
            + "from [Sales]",
            "Axis #0:\n"
            + "{}\n"
            + "Axis #1:\n"
            + "{[Measures].[Profit]}\n"
            + "Axis #2:\n"
            + "{[Product].[Products].[Food].[Baked Goods].[*Subtotal - Bread]}\n"
            + "{[Product].[Products].[Food].[Baked Goods].[Bread].[Bagels]}\n"
            + "{[Product].[Products].[Food].[Baked Goods].[Bread].[Muffins]}\n"
            + "Row #0: $5,367.00\n"
            + "Row #1: $1,123.10\n"
            + "Row #2: $4,243.90\n");
    }
}

// End VisualTotalsTest.java
