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

import mondrian.calcite.CalciteFallbackPolicy;
import mondrian.rolap.agg.SegmentLoader;
import mondrian.test.FoodMartTestCase;
import mondrian.test.TestContext;

/**
 * Cover for relations a Mondrian schema declares but the database does not
 * have -- {@code <InlineTable>} and {@code <View>} / {@code <Query>} -- on the
 * Calcite backend.
 *
 * <p>Every case runs with {@code mondrian.calcite.strict=full}, which forbids
 * falling back to the legacy SQL generator. Without that the tests would pass
 * for the wrong reason: an untranslated shape falls back silently, and the
 * whole point of this cover is that the Calcite path itself handles these
 * relations, because on a database the legacy generator has no dialect for
 * the fallback is not a degraded answer, it is no answer.
 *
 * <p>{@link mondrian.test.InlineTableTest} and
 * {@link mondrian.test.SchemaTest} cover the feature's behaviour against the
 * default backend settings. What is pinned here is the set of ways the
 * Calcite translation specifically went wrong, each of which produced a
 * plausible-looking query that returned wrong data or no data at all.
 */
public class VirtualRelationTest extends FoodMartTestCase {

    private String priorStrict;
    private String priorBackend;

    public VirtualRelationTest(String name) {
        super(name);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        priorStrict =
            System.getProperty(CalciteFallbackPolicy.STRICT_PROPERTY);
        priorBackend = System.getProperty("mondrian.backend");
        System.setProperty(CalciteFallbackPolicy.STRICT_PROPERTY, "full");
        System.setProperty("mondrian.backend", "calcite");
        SegmentLoader.clearCalcitePlannerCache();
    }

    @Override
    protected void tearDown() throws Exception {
        restore(CalciteFallbackPolicy.STRICT_PROPERTY, priorStrict);
        restore("mondrian.backend", priorBackend);
        SegmentLoader.clearCalcitePlannerCache();
        super.tearDown();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /**
     * An inline table whose string values are of unequal length, joined to a
     * real dimension column.
     *
     * <p>Standard SQL types {@code SELECT 'USA' UNION ALL SELECT 'Mexico'} as
     * {@code CHAR(6)} and pads the shorter branch, so
     * {@code store.store_country = 'USA   '} matches nothing and every cell
     * comes back empty. The failure is silent: the axis is still correct,
     * because members are read from the inline table alone.
     */
    public void testInlineTableRaggedStringsJoinToRealColumn() {
        raggedStringContext().assertQueryReturns(
            "select {[Measures].[Unit Sales]} on columns,\n"
            + " {[Nation].[Nation].[Nation].Members} on rows\n"
            + "from [Sales_ragged_inline]",
            "Axis #0:\n"
            + "{}\n"
            + "Axis #1:\n"
            + "{[Measures].[Unit Sales]}\n"
            + "Axis #2:\n"
            + "{[Nation].[Nation].[Canada]}\n"
            + "{[Nation].[Nation].[Mexico]}\n"
            + "{[Nation].[Nation].[USA]}\n"
            + "Row #0: \n"
            + "Row #1: \n"
            + "Row #2: 266,773\n");
    }

    /**
     * An inline table holding an integer outside the {@code INTEGER} range,
     * alongside a small one.
     *
     * <p>The rendered SQL is a {@code UNION ALL} of one-row selects, and a
     * database types each column from the first branch -- so 1234 in row one
     * makes the column {@code INTEGER} and 1234567890123 in row two overflows.
     * The dialect emits a widening cast for exactly this reason; Calcite folds
     * {@code CAST(1234 AS BIGINT)} back to a bare literal unless the cast is
     * built as an abstract one.
     */
    public void testInlineTableWideIntegerSurvivesUnionTyping() {
        wideIntegerContext().assertQueryReturns(
            "select {[Measures].[Unit Sales]} on columns,\n"
            + " {[Big numbers].[foo].[Level1].Members} on rows\n"
            + "from [Sales]",
            "Axis #0:\n"
            + "{}\n"
            + "Axis #1:\n"
            + "{[Measures].[Unit Sales]}\n"
            + "Axis #2:\n"
            + "{[Big numbers].[foo].[1234]}\n"
            + "{[Big numbers].[foo].[1234567890123]}\n"
            + "Row #0: 195,448\n"
            + "Row #1: 739\n");
    }

    /**
     * A view named after the table it selects from -- the shape a schema
     * author reaches for when wrapping a table without renaming everything
     * that references it.
     *
     * <p>Resolving {@code product_class} inside the view against a schema
     * that contains the view finds the view again, and recurses until the
     * stack overflows.
     */
    public void testViewNamedAfterTheTableItSelectsFrom() {
        selfNamedViewContext().assertQueryReturns(
            "select {[Measures].[Unit Sales]} on columns,\n"
            + " {[Product].[Products].[Product Family].Members} on rows\n"
            + "from [Sales]",
            "Axis #0:\n"
            + "{}\n"
            + "Axis #1:\n"
            + "{[Measures].[Unit Sales]}\n"
            + "Axis #2:\n"
            + "{[Product].[Products].[Drink]}\n"
            + "{[Product].[Products].[Food]}\n"
            + "{[Product].[Products].[Non-Consumable]}\n"
            + "Row #0: 24,597\n"
            + "Row #1: 191,940\n"
            + "Row #2: 50,236\n");
    }

    /**
     * A role that grants one member of an inline-table dimension must not
     * leak the others. Row security is enforced by filters the Calcite path
     * injects, and a relation the planner models differently -- expanded
     * in place rather than scanned -- is exactly where such a filter could
     * quietly be dropped.
     */
    public void testInlineTableDimensionHonoursMemberGrants() {
        final String result = TestContext.toString(
            raggedStringContext().withRole("Only USA").executeQuery(
                "select {[Measures].[Unit Sales]} on columns,\n"
                + " {[Nation].[Nation].[Nation].Members} on rows\n"
                + "from [Sales_ragged_inline]"));
        assertTrue(
            "granted member must still be visible: " + result,
            result.contains("[Nation].[Nation].[USA]"));
        assertFalse(
            "ungranted member must not leak through an inline-table "
            + "dimension: " + result,
            result.contains("[Nation].[Nation].[Mexico]"));
        assertFalse(
            "ungranted member must not leak through an inline-table "
            + "dimension: " + result,
            result.contains("[Nation].[Nation].[Canada]"));
    }

    private TestContext raggedStringContext() {
        return getTestContext().legacy().create(
            null,
            "<Cube name=\"Sales_ragged_inline\">\n"
            + "  <Table name=\"sales_fact_1997\"/>\n"
            + "  <Dimension name=\"Nation\" foreignKeyTable=\"store\""
            + " foreignKey=\"store_id\">\n"
            + "    <Hierarchy hasAll=\"true\""
            + " allMemberName=\"All Nations\" primaryKeyTable=\"store\""
            + " primaryKey=\"store_id\">\n"
            + "      <Join leftKey=\"store_country\""
            + " rightKey=\"nation_name\">\n"
            + "        <Table name=\"store\"/>\n"
            + "        <InlineTable alias=\"nation_ragged\">\n"
            + "          <ColumnDefs>\n"
            + "            <ColumnDef name=\"nation_name\" type=\"String\"/>\n"
            + "          </ColumnDefs>\n"
            + "          <Rows>\n"
            + "            <Row><Value column=\"nation_name\">USA</Value>"
            + "</Row>\n"
            + "            <Row><Value column=\"nation_name\">Mexico</Value>"
            + "</Row>\n"
            + "            <Row><Value column=\"nation_name\">Canada</Value>"
            + "</Row>\n"
            + "          </Rows>\n"
            + "        </InlineTable>\n"
            + "      </Join>\n"
            + "      <Level name=\"Nation\" table=\"nation_ragged\""
            + " column=\"nation_name\" uniqueMembers=\"true\"/>\n"
            + "    </Hierarchy>\n"
            + "  </Dimension>\n"
            + "  <Measure name=\"Unit Sales\" column=\"unit_sales\""
            + " aggregator=\"sum\" formatString=\"Standard\"/>\n"
            + "</Cube>",
            null, null, null,
            "<Role name=\"Only USA\">\n"
            + "  <SchemaGrant access=\"none\">\n"
            + "    <CubeGrant cube=\"Sales_ragged_inline\" access=\"all\">\n"
            + "      <HierarchyGrant hierarchy=\"[Nation]\""
            + " access=\"custom\">\n"
            + "        <MemberGrant member=\"[Nation].[All Nations].[USA]\""
            + " access=\"all\"/>\n"
            + "      </HierarchyGrant>\n"
            + "    </CubeGrant>\n"
            + "  </SchemaGrant>\n"
            + "</Role>");
    }

    private TestContext wideIntegerContext() {
        return getTestContext()
            .insertPhysTable(
                "<InlineTable alias='big_numbers'>\n"
                + "  <ColumnDefs>\n"
                + "    <ColumnDef name='id' type='Integer'"
                + " internalType='int'/>\n"
                + "    <ColumnDef name='big_num' type='Integer'"
                + " internalType='long'/>\n"
                + "  </ColumnDefs>\n"
                + "  <Rows>\n"
                + "    <Row>\n"
                + "      <Value column='id'>0</Value>\n"
                + "      <Value column='big_num'>1234</Value>\n"
                + "    </Row>\n"
                + "    <Row>\n"
                + "      <Value column='id'>519</Value>\n"
                + "      <Value column='big_num'>1234567890123</Value>\n"
                + "    </Row>\n"
                + "  </Rows>\n"
                + "</InlineTable>\n")
            .insertDimension(
                "Sales",
                "<Dimension name='Big numbers' foreignKey='promotion_id'"
                + " table='big_numbers' key='Level2'>\n"
                + "  <Attributes>\n"
                + "    <Attribute name='Level1' keyColumn='big_num'/>\n"
                + "    <Attribute name='Level2' keyColumn='id'/>\n"
                + "  </Attributes>\n"
                + "  <Hierarchies>\n"
                + "    <Hierarchy name='foo' hasAll='false' primaryKey='id'>\n"
                + "      <Level attribute='Level1'/>\n"
                + "      <Level attribute='Level2'/>\n"
                + "    </Hierarchy>\n"
                + "  </Hierarchies>\n"
                + "</Dimension>\n")
            .insertDimensionLinks(
                "Sales",
                org.olap4j.impl.ArrayMap.of(
                    "Sales",
                    "<ForeignKeyLink dimension='Big numbers' "
                    + "foreignKeyColumn='promotion_id'/>"))
            .ignoreMissingLink();
    }

    private TestContext selfNamedViewContext() {
        final TestContext base = getTestContext();
        final String raw = base.getRawSchema();
        final String replaced = raw.replace(
            "<Table name='product_class' keyColumn='product_class_id'/>",
            "<Query alias='product_class' keyColumn='product_class_id'>\n"
            + "  <ExpressionView>\n"
            + "    <SQL dialect='generic'>\n"
            + "      SELECT * FROM \"product_class\"\n"
            + "    </SQL>\n"
            + "    <SQL dialect='mysql'>\n"
            + "      SELECT * FROM `product_class`\n"
            + "    </SQL>\n"
            + "  </ExpressionView>\n"
            + "</Query>\n");
        assertFalse(
            "the fixture schema no longer declares product_class as a "
            + "<Table>, so this test is no longer exercising a view",
            raw.equals(replaced));
        return base.withSchema(replaced);
    }
}

// End VirtualRelationTest.java
