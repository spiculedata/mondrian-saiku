/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml;

import mondrian.olap.Connection;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.test.TestContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 1 Session 2: shared dimensions + DimensionUsage references
 * + multi-hierarchy + Join + Level Property children + Hierarchy-level
 * {@code primaryKeyTable}.
 *
 * <p>Together these are what take the converter from "minimal one-cube
 * spike" to "FoodMart-shape multi-cube schema with the standard star
 * topology". The acid test at the bottom YAML-ifies the canonical Sales
 * cube (Store + Time + Product shared dims) and asserts byte-identical
 * MDX results against the equivalent hand-written XML.
 */
public class YamlSchemaSharedDimsTest {

    /** Top-level shared {@code <Dimension>} emitted directly under
     *  {@code <Schema>} (no foreignKey, no cube-scoped indent). */
    @Test
    public void yamlConverterEmitsSharedDimension() {
        String yaml =
            "schema: FoodMartLite\n"
            + "shared_dimensions:\n"
            + "  Store:\n"
            + "    hierarchy:\n"
            + "      has_all: true\n"
            + "      primary_key: store_id\n"
            + "      table: store\n"
            + "      levels:\n"
            + "        - name: Store Country\n"
            + "          column: store_country\n"
            + "          unique_members: true\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        int schemaOpen = xml.indexOf("<Schema name=\"FoodMartLite\"");
        int dimOpen = xml.indexOf("<Dimension name=\"Store\"", schemaOpen);
        int dimClose = xml.indexOf("</Dimension>", dimOpen);
        assertTrue("shared dim missing or misplaced:\n" + xml,
            schemaOpen >= 0 && dimOpen > schemaOpen && dimClose > dimOpen);
        assertTrue("shared dim must NOT carry foreignKey:\n" + xml,
            xml.substring(dimOpen, dimClose).indexOf("foreignKey") < 0);
    }

    /** Shared {@code <Dimension>} carries {@code type="TimeDimension"}. */
    @Test
    public void yamlConverterEmitsSharedDimensionWithType() {
        String yaml =
            "schema: FoodMartLite\n"
            + "shared_dimensions:\n"
            + "  Time:\n"
            + "    type: TimeDimension\n"
            + "    hierarchy:\n"
            + "      has_all: false\n"
            + "      primary_key: time_id\n"
            + "      table: time_by_day\n"
            + "      levels:\n"
            + "        - name: Year\n"
            + "          column: the_year\n"
            + "          type: Numeric\n"
            + "          unique_members: true\n"
            + "          level_type: TimeYears\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("type=TimeDimension missing:\n" + xml,
            xml.contains("<Dimension name=\"Time\" type=\"TimeDimension\">"));
        assertTrue("levelType missing:\n" + xml,
            xml.contains("levelType=\"TimeYears\""));
    }

    /** {@code DimensionUsage} inside a cube. */
    @Test
    public void yamlConverterEmitsDimensionUsage() {
        String yaml =
            "schema: FoodMartLite\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    dimension_usages:\n"
            + "      - source: Store\n"
            + "        foreign_key: store_id\n"
            + "      - name: TimeAlias\n"
            + "        source: Time\n"
            + "        foreign_key: time_id\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("DimensionUsage #1 missing:\n" + xml,
            xml.contains(
                "<DimensionUsage source=\"Store\""
                    + " foreignKey=\"store_id\"/>"));
        assertTrue("DimensionUsage #2 (with alias) missing:\n" + xml,
            xml.contains(
                "<DimensionUsage name=\"TimeAlias\" source=\"Time\""
                    + " foreignKey=\"time_id\"/>"));
    }

    /** Multi-hierarchy dimension (FoodMart {@code Time} has the
     *  default + {@code Weekly}). */
    @Test
    public void yamlConverterEmitsMultiHierarchyDimension() {
        String yaml =
            "schema: FoodMartLite\n"
            + "shared_dimensions:\n"
            + "  Time:\n"
            + "    type: TimeDimension\n"
            + "    hierarchies:\n"
            + "      - has_all: false\n"
            + "        primary_key: time_id\n"
            + "        table: time_by_day\n"
            + "        levels:\n"
            + "          - name: Year\n"
            + "            column: the_year\n"
            + "            unique_members: true\n"
            + "      - name: Weekly\n"
            + "        has_all: true\n"
            + "        primary_key: time_id\n"
            + "        table: time_by_day\n"
            + "        levels:\n"
            + "          - name: Week\n"
            + "            column: week_of_year\n"
            + "            unique_members: false\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        // First hierarchy: unnamed (the default).
        assertTrue("default hierarchy missing:\n" + xml,
            xml.contains("<Hierarchy hasAll=\"false\" primaryKey=\"time_id\">"));
        // Second hierarchy: named Weekly.
        assertTrue("named Weekly hierarchy missing:\n" + xml,
            xml.contains(
                "<Hierarchy name=\"Weekly\" hasAll=\"true\""
                    + " primaryKey=\"time_id\">"));
    }

    /** {@code Join} with two child tables — replaces the simple
     *  {@code <Table>} when a hierarchy spans multiple tables. */
    @Test
    public void yamlConverterEmitsJoin() {
        String yaml =
            "schema: FoodMartLite\n"
            + "shared_dimensions:\n"
            + "  Product:\n"
            + "    hierarchy:\n"
            + "      has_all: true\n"
            + "      primary_key: product_id\n"
            + "      primary_key_table: product\n"
            + "      join:\n"
            + "        left_key: product_class_id\n"
            + "        right_key: product_class_id\n"
            + "        tables:\n"
            + "          - product\n"
            + "          - product_class\n"
            + "      levels:\n"
            + "        - name: Family\n"
            + "          table: product_class\n"
            + "          column: product_family\n"
            + "          unique_members: true\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("primaryKeyTable missing:\n" + xml,
            xml.contains("primaryKeyTable=\"product\""));
        assertTrue("Join element missing:\n" + xml,
            xml.contains(
                "<Join leftKey=\"product_class_id\""
                    + " rightKey=\"product_class_id\">"));
        assertTrue("left table missing:\n" + xml,
            xml.contains("<Table name=\"product\"/>"));
        assertTrue("right table missing:\n" + xml,
            xml.contains("<Table name=\"product_class\"/>"));
        assertTrue("Join close missing:\n" + xml,
            xml.contains("</Join>"));
        // Level table-override attribute.
        assertTrue("Level table override missing:\n" + xml,
            xml.contains("table=\"product_class\""));
    }

    /** Level {@code <Property>} child elements. */
    @Test
    public void yamlConverterEmitsLevelProperties() {
        String yaml =
            "schema: FoodMartLite\n"
            + "shared_dimensions:\n"
            + "  Store:\n"
            + "    hierarchy:\n"
            + "      has_all: true\n"
            + "      primary_key: store_id\n"
            + "      table: store\n"
            + "      levels:\n"
            + "        - name: Store Name\n"
            + "          column: store_name\n"
            + "          unique_members: true\n"
            + "          properties:\n"
            + "            - name: Store Type\n"
            + "              column: store_type\n"
            + "            - name: Has coffee bar\n"
            + "              column: coffee_bar\n"
            + "              type: Boolean\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        // The level becomes a container (open tag, properties inside,
        // close tag) when it has child elements — no longer self-closing.
        assertTrue("Level must open as container when properties exist:\n"
                + xml,
            xml.contains("<Level name=\"Store Name\"")
                && xml.contains("</Level>"));
        assertTrue("Property #1 missing:\n" + xml,
            xml.contains(
                "<Property name=\"Store Type\" column=\"store_type\"/>"));
        assertTrue("Property #2 with type missing:\n" + xml,
            xml.contains(
                "<Property name=\"Has coffee bar\""
                    + " column=\"coffee_bar\" type=\"Boolean\"/>"));
    }

    /**
     * Acid test: YAML-ify the FoodMart Sales cube (Store + Time + Product
     * shared dims via DimensionUsage, Time multi-hierarchy, Product
     * Join) and prove byte-identical MDX results against the equivalent
     * hand-written XML.
     */
    @Test
    public void yamlFoodMartSalesCubeMatchesXmlEquivalent() {
        final String xmlSchema = FOODMART_LITE_XML;
        final String yamlSchema = FOODMART_LITE_YAML;

        final String mdx =
            "SELECT [Measures].[Unit Sales] ON COLUMNS, "
            + "[Store].[Store Country].Members ON ROWS "
            + "FROM [Sales]";

        String[] xmlCells = runMdx(xmlSchema, mdx);
        String xmlFromYaml = YamlSchemaConverter.toXml(yamlSchema);
        String[] yamlCells = runMdx(xmlFromYaml, mdx);

        assertEquals("row count diverged",
            xmlCells.length, yamlCells.length);
        for (int i = 0; i < xmlCells.length; i++) {
            assertEquals(
                "cell at row " + i + " diverged",
                xmlCells[i], yamlCells[i]);
        }
        // FoodMart has data only for USA (Canada + Mexico are
        // schema-defined but rowless), so we need to scan all cells
        // rather than pin cell[0].
        boolean anyNonBlank = false;
        for (String c : xmlCells) {
            if (!c.isEmpty()) {
                anyNonBlank = true;
                break;
            }
        }
        assertTrue("expected at least one non-blank cell, got: "
                + java.util.Arrays.toString(xmlCells),
            anyNonBlank);

        // Second query exercises the joined Product hierarchy.
        final String mdx2 =
            "SELECT [Measures].[Unit Sales] ON COLUMNS, "
            + "[Product].[Product Family].Members ON ROWS "
            + "FROM [Sales]";
        String[] xmlCells2 = runMdx(xmlSchema, mdx2);
        String[] yamlCells2 = runMdx(xmlFromYaml, mdx2);
        assertEquals("Product row count diverged",
            xmlCells2.length, yamlCells2.length);
        for (int i = 0; i < xmlCells2.length; i++) {
            assertEquals(
                "Product cell " + i + " diverged",
                xmlCells2[i], yamlCells2[i]);
        }
    }

    private static String[] runMdx(String schemaXml, String mdx) {
        TestContext ctx = TestContext.instance().withSchema(schemaXml);
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(mdx);
            Result result = conn.execute(q);
            try {
                int rowCount = result.getAxes().length >= 2
                    ? result.getAxes()[1].getPositions().size() : 0;
                String[] cells = new String[rowCount];
                for (int r = 0; r < rowCount; r++) {
                    Object value = result.getCell(new int[] {0, r})
                        .getFormattedValue();
                    cells[r] = value == null ? "" : value.toString();
                }
                return cells;
            } finally {
                result.close();
            }
        } finally {
            conn.close();
        }
    }

    /** Minimal FoodMart-shape schema in canonical Mondrian XML. */
    private static final String FOODMART_LITE_XML =
        "<?xml version=\"1.0\"?>\n"
        + "<Schema name=\"FoodMartLite\">\n"
        + "  <Dimension name=\"Store\">\n"
        + "    <Hierarchy hasAll=\"true\" primaryKey=\"store_id\">\n"
        + "      <Table name=\"store\"/>\n"
        + "      <Level name=\"Store Country\" column=\"store_country\""
        +            " uniqueMembers=\"true\"/>\n"
        + "    </Hierarchy>\n"
        + "  </Dimension>\n"
        + "  <Dimension name=\"Time\" type=\"TimeDimension\">\n"
        + "    <Hierarchy hasAll=\"false\" primaryKey=\"time_id\">\n"
        + "      <Table name=\"time_by_day\"/>\n"
        + "      <Level name=\"Year\" column=\"the_year\" type=\"Numeric\""
        +            " uniqueMembers=\"true\" levelType=\"TimeYears\"/>\n"
        + "    </Hierarchy>\n"
        + "  </Dimension>\n"
        + "  <Dimension name=\"Product\">\n"
        + "    <Hierarchy hasAll=\"true\" primaryKey=\"product_id\""
        +            " primaryKeyTable=\"product\">\n"
        + "      <Join leftKey=\"product_class_id\""
        +            " rightKey=\"product_class_id\">\n"
        + "        <Table name=\"product\"/>\n"
        + "        <Table name=\"product_class\"/>\n"
        + "      </Join>\n"
        + "      <Level name=\"Product Family\" table=\"product_class\""
        +            " column=\"product_family\" uniqueMembers=\"true\"/>\n"
        + "    </Hierarchy>\n"
        + "  </Dimension>\n"
        + "  <Cube name=\"Sales\">\n"
        + "    <Table name=\"sales_fact_1997\"/>\n"
        + "    <DimensionUsage name=\"Store\" source=\"Store\""
        +            " foreignKey=\"store_id\"/>\n"
        + "    <DimensionUsage name=\"Time\" source=\"Time\""
        +            " foreignKey=\"time_id\"/>\n"
        + "    <DimensionUsage name=\"Product\" source=\"Product\""
        +            " foreignKey=\"product_id\"/>\n"
        + "    <Measure name=\"Unit Sales\" column=\"unit_sales\""
        +            " aggregator=\"sum\"/>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    /** Same schema written in YAML. */
    private static final String FOODMART_LITE_YAML =
        "schema: FoodMartLite\n"
        + "shared_dimensions:\n"
        + "  Store:\n"
        + "    hierarchy:\n"
        + "      has_all: true\n"
        + "      primary_key: store_id\n"
        + "      table: store\n"
        + "      levels:\n"
        + "        - name: Store Country\n"
        + "          column: store_country\n"
        + "          unique_members: true\n"
        + "  Time:\n"
        + "    type: TimeDimension\n"
        + "    hierarchy:\n"
        + "      has_all: false\n"
        + "      primary_key: time_id\n"
        + "      table: time_by_day\n"
        + "      levels:\n"
        + "        - name: Year\n"
        + "          column: the_year\n"
        + "          type: Numeric\n"
        + "          unique_members: true\n"
        + "          level_type: TimeYears\n"
        + "  Product:\n"
        + "    hierarchy:\n"
        + "      has_all: true\n"
        + "      primary_key: product_id\n"
        + "      primary_key_table: product\n"
        + "      join:\n"
        + "        left_key: product_class_id\n"
        + "        right_key: product_class_id\n"
        + "        tables:\n"
        + "          - product\n"
        + "          - product_class\n"
        + "      levels:\n"
        + "        - name: Product Family\n"
        + "          table: product_class\n"
        + "          column: product_family\n"
        + "          unique_members: true\n"
        + "cubes:\n"
        + "  Sales:\n"
        + "    fact_table: sales_fact_1997\n"
        + "    dimension_usages:\n"
        + "      - name: Store\n"
        + "        source: Store\n"
        + "        foreign_key: store_id\n"
        + "      - name: Time\n"
        + "        source: Time\n"
        + "        foreign_key: time_id\n"
        + "      - name: Product\n"
        + "        source: Product\n"
        + "        foreign_key: product_id\n"
        + "    measures:\n"
        + "      - name: Unit Sales\n"
        + "        column: unit_sales\n"
        + "        aggregator: sum\n";
}

// End YamlSchemaSharedDimsTest.java
