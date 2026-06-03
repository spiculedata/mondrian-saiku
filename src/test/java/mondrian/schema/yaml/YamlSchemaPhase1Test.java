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
 * #34 Phase 1 — extends Phase 0's minimal converter with the elements
 * every real-world Mondrian schema uses: {@code Annotations} (i18n
 * captions / descriptions), {@code CalculatedMember} (the cornerstone
 * of measure-derivation), and agg-table refs nested inside
 * {@code <Table>} ({@code AggExclude}, {@code AggName} with its
 * children: {@code AggFactCount}, {@code AggIgnoreColumn},
 * {@code AggForeignKey}, {@code AggMeasure}, {@code AggLevel}).
 *
 * <p>Tests are split: emission-only assertions for elements that need
 * fixture-data not present in the default FoodMart test fixture (agg
 * tables), plus an end-to-end equivalence test for CalculatedMember
 * (which is pure MDX and needs no extra DB state).
 */
public class YamlSchemaPhase1Test {

    /** Sanity: schema-level Annotations round-trip into XML correctly. */
    @Test
    public void yamlConverterEmitsSchemaAnnotations() {
        String yaml =
            "schema: Phase1\n"
            + "annotations:\n"
            + "  caption.de_DE: Verkaufen\n"
            + "  description.fr_FR: Cube des ventes\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("missing <Annotations>:\n" + xml,
            xml.contains("<Annotations>"));
        assertTrue("missing caption.de_DE annotation:\n" + xml,
            xml.contains(
                "<Annotation name=\"caption.de_DE\">Verkaufen</Annotation>"));
        assertTrue("missing description.fr_FR annotation:\n" + xml,
            xml.contains(
                "<Annotation name=\"description.fr_FR\">"
                    + "Cube des ventes</Annotation>"));
        assertTrue("missing </Annotations>:\n" + xml,
            xml.contains("</Annotations>"));
    }

    /** Cube-level Annotations land inside the cube. */
    @Test
    public void yamlConverterEmitsCubeAnnotations() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    annotations:\n"
            + "      caption.fr_FR: Ventes\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        // The cube-scoped Annotations block must sit between <Cube> and
        // its <Table>, the same shape MondrianDef expects.
        int cubeStart = xml.indexOf("<Cube name=\"Sales\"");
        int annOpen = xml.indexOf("<Annotations>", cubeStart);
        int annClose = xml.indexOf("</Annotations>", annOpen);
        int tableOpen = xml.indexOf("<Table name=\"sales_fact_1997\"");
        assertTrue("expected cube-scoped <Annotations>:\n" + xml,
            cubeStart >= 0 && annOpen > cubeStart
                && annClose > annOpen && tableOpen > annClose);
        assertTrue("missing French caption:\n" + xml,
            xml.contains(
                "<Annotation name=\"caption.fr_FR\">Ventes</Annotation>"));
    }

    /** CalculatedMember with inline formula attribute + properties. */
    @Test
    public void yamlConverterEmitsCalculatedMemberWithProperties() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    calculated_members:\n"
            + "      - name: Profit\n"
            + "        dimension: Measures\n"
            + "        formula: '[Measures].[Store Sales] - [Measures].[Store Cost]'\n"
            + "        properties:\n"
            + "          FORMAT_STRING: '$#,##0.00'\n"
            + "          SOLVE_ORDER: '10'\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("missing CalculatedMember open:\n" + xml,
            xml.contains("<CalculatedMember name=\"Profit\""));
        assertTrue("missing dimension attr:\n" + xml,
            xml.contains("dimension=\"Measures\""));
        assertTrue("missing inline formula attribute:\n" + xml,
            xml.contains(
                "formula=\"[Measures].[Store Sales] "
                    + "- [Measures].[Store Cost]\""));
        assertTrue("missing FORMAT_STRING property:\n" + xml,
            xml.contains(
                "<CalculatedMemberProperty name=\"FORMAT_STRING\""
                    + " value=\"$#,##0.00\"/>"));
        assertTrue("missing SOLVE_ORDER property:\n" + xml,
            xml.contains(
                "<CalculatedMemberProperty name=\"SOLVE_ORDER\""
                    + " value=\"10\"/>"));
        assertTrue("missing CalculatedMember close:\n" + xml,
            xml.contains("</CalculatedMember>"));
    }

    /**
     * CalculatedMember can also use a child {@code <Formula>} element
     * (mirrors how FoodMart3.xml writes the more readable multi-line
     * forms). The converter picks one form — child element — when the
     * YAML uses the {@code formula_body} key.
     */
    @Test
    public void yamlConverterEmitsCalculatedMemberFormulaAsChildElement() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    calculated_members:\n"
            + "      - name: Profit\n"
            + "        dimension: Measures\n"
            + "        formula_body: |\n"
            + "          [Measures].[Store Sales] - [Measures].[Store Cost]\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("missing <Formula> child element:\n" + xml,
            xml.contains("<Formula>"));
        assertTrue("missing closing </Formula>:\n" + xml,
            xml.contains("</Formula>"));
    }

    /** AggExclude as a direct child of {@code <Table>}. */
    @Test
    public void yamlConverterEmitsAggExclude() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table:\n"
            + "      name: sales_fact_1997\n"
            + "      agg_exclude:\n"
            + "        - agg_c_special_sales_fact_1997\n"
            + "        - agg_lc_100_sales_fact_1997\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("AggExclude #1 missing:\n" + xml,
            xml.contains(
                "<AggExclude name=\"agg_c_special_sales_fact_1997\"/>"));
        assertTrue("AggExclude #2 missing:\n" + xml,
            xml.contains(
                "<AggExclude name=\"agg_lc_100_sales_fact_1997\"/>"));
    }

    /** Full {@code <AggName>} block with every child element MondrianDef
     *  accepts inside it. */
    @Test
    public void yamlConverterEmitsAggNameWithAllChildElements() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table:\n"
            + "      name: sales_fact_1997\n"
            + "      agg_names:\n"
            + "        - name: agg_c_special_sales_fact_1997\n"
            + "          fact_count_column: FACT_COUNT\n"
            + "          ignore_columns:\n"
            + "            - foo\n"
            + "            - bar\n"
            + "          foreign_keys:\n"
            + "            - fact_column: product_id\n"
            + "              agg_column: PRODUCT_ID\n"
            + "            - fact_column: customer_id\n"
            + "              agg_column: CUSTOMER_ID\n"
            + "          measures:\n"
            + "            - name: '[Measures].[Unit Sales]'\n"
            + "              column: UNIT_SALES_SUM\n"
            + "          levels:\n"
            + "            - name: '[Time].[Year]'\n"
            + "              column: TIME_YEAR\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("AggName missing:\n" + xml,
            xml.contains(
                "<AggName name=\"agg_c_special_sales_fact_1997\">"));
        assertTrue("AggFactCount missing:\n" + xml,
            xml.contains("<AggFactCount column=\"FACT_COUNT\"/>"));
        assertTrue("AggIgnoreColumn foo missing:\n" + xml,
            xml.contains("<AggIgnoreColumn column=\"foo\"/>"));
        assertTrue("AggIgnoreColumn bar missing:\n" + xml,
            xml.contains("<AggIgnoreColumn column=\"bar\"/>"));
        assertTrue("AggForeignKey product_id missing:\n" + xml,
            xml.contains(
                "<AggForeignKey factColumn=\"product_id\""
                    + " aggColumn=\"PRODUCT_ID\"/>"));
        assertTrue("AggForeignKey customer_id missing:\n" + xml,
            xml.contains(
                "<AggForeignKey factColumn=\"customer_id\""
                    + " aggColumn=\"CUSTOMER_ID\"/>"));
        assertTrue("AggMeasure missing:\n" + xml,
            xml.contains(
                "<AggMeasure name=\"[Measures].[Unit Sales]\""
                    + " column=\"UNIT_SALES_SUM\"/>"));
        assertTrue("AggLevel missing:\n" + xml,
            xml.contains(
                "<AggLevel name=\"[Time].[Year]\""
                    + " column=\"TIME_YEAR\"/>"));
        assertTrue("AggName close tag missing:\n" + xml,
            xml.contains("</AggName>"));
    }

    /**
     * End-to-end deliverable for Phase 1: a YAML schema that defines a
     * {@code CalculatedMember} on a real cube and runs an MDX query
     * touching that calculated member returns identical cells to the
     * same schema written in XML.
     */
    @Test
    public void yamlSchemaWithCalcMemberMatchesXmlEquivalent() {
        final String xmlSchema =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"Phase1\">\n"
            + "  <Cube name=\"SalesPlus\">\n"
            + "    <Table name=\"sales_fact_1997\"/>\n"
            + "    <Dimension name=\"Time\" foreignKey=\"time_id\">\n"
            + "      <Hierarchy hasAll=\"true\" primaryKey=\"time_id\">\n"
            + "        <Table name=\"time_by_day\"/>\n"
            + "        <Level name=\"Year\" column=\"the_year\""
            +              " type=\"Numeric\" uniqueMembers=\"true\"/>\n"
            + "      </Hierarchy>\n"
            + "    </Dimension>\n"
            + "    <Measure name=\"Unit Sales\" column=\"unit_sales\""
            +            " aggregator=\"sum\"/>\n"
            + "    <Measure name=\"Store Sales\" column=\"store_sales\""
            +            " aggregator=\"sum\"/>\n"
            + "    <CalculatedMember name=\"Avg Sale\" dimension=\"Measures\""
            +            " formula=\"[Measures].[Store Sales]"
            +                     " / [Measures].[Unit Sales]\">\n"
            + "      <CalculatedMemberProperty name=\"FORMAT_STRING\""
            +                     " value=\"0.00\"/>\n"
            + "    </CalculatedMember>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        final String yamlSchema =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  SalesPlus:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    dimensions:\n"
            + "      - name: Time\n"
            + "        foreign_key: time_id\n"
            + "        hierarchy:\n"
            + "          has_all: true\n"
            + "          primary_key: time_id\n"
            + "          table: time_by_day\n"
            + "          levels:\n"
            + "            - name: Year\n"
            + "              column: the_year\n"
            + "              type: Numeric\n"
            + "              unique_members: true\n"
            + "    measures:\n"
            + "      - name: Unit Sales\n"
            + "        column: unit_sales\n"
            + "        aggregator: sum\n"
            + "      - name: Store Sales\n"
            + "        column: store_sales\n"
            + "        aggregator: sum\n"
            + "    calculated_members:\n"
            + "      - name: Avg Sale\n"
            + "        dimension: Measures\n"
            + "        formula: '[Measures].[Store Sales] / [Measures].[Unit Sales]'\n"
            + "        properties:\n"
            + "          FORMAT_STRING: '0.00'\n";

        final String mdx =
            "SELECT [Measures].[Avg Sale] ON COLUMNS, "
            + "[Time].Members ON ROWS "
            + "FROM [SalesPlus]";

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
        assertTrue("expected at least one non-blank Avg Sale cell",
            xmlCells.length > 0 && !xmlCells[0].isEmpty());
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
}

// End YamlSchemaPhase1Test.java
