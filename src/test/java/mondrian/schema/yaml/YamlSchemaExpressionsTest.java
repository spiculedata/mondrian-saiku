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
 * #34 Phase 1 Session 7: SQL-dialect expression blocks for both
 * {@code Level} ({@code KeyExpression}, {@code NameExpression},
 * {@code OrdinalExpression}, {@code CaptionExpression}) and
 * {@code Measure} ({@code MeasureExpression}).
 *
 * <p>The shape is a YAML list of {@code {dialect, text}} maps. The
 * SQL text is captured verbatim so users can mix bare SQL with
 * Mondrian's {@code <Column table="..." name="..."/>} inline-refs
 * by literally writing the XML markup in the SQL text — Mondrian's
 * own dialect-SQL parser handles that substitution.
 *
 * <p>Acid test: a Measure that uses {@code MeasureExpression} to
 * compute a derived value returns identical MDX cells across YAML
 * and XML versions of the same schema.
 */
public class YamlSchemaExpressionsTest {

    @Test
    public void yamlConverterEmitsMeasureExpression() {
        String yaml =
            "schema: Exp\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    measures:\n"
            + "      - name: Promotion Sales\n"
            + "        aggregator: sum\n"
            + "        datatype: Numeric\n"
            + "        measure_expression:\n"
            + "          - dialect: derby\n"
            + "            text: '(case when \"sales_fact_1997\".\"promotion_id\" = 0 then 0 else \"sales_fact_1997\".\"store_sales\" end)'\n"
            + "          - dialect: generic\n"
            + "            text: 'CASE WHEN \"sales_fact_1997\".\"promotion_id\" = 0 THEN 0 ELSE \"sales_fact_1997\".\"store_sales\" END'\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("MeasureExpression container missing:\n" + xml,
            xml.contains("<MeasureExpression>"));
        assertTrue("derby dialect SQL block missing:\n" + xml,
            xml.contains("<SQL dialect=\"derby\">"));
        assertTrue("generic dialect SQL block missing:\n" + xml,
            xml.contains("<SQL dialect=\"generic\">"));
        assertTrue("SQL body text not preserved:\n" + xml,
            xml.contains("promotion_id")
                && xml.contains("CASE WHEN"));
        assertTrue("MeasureExpression close missing:\n" + xml,
            xml.contains("</MeasureExpression>"));
        // The Measure must become a container element (not
        // self-closing) when MeasureExpression is present.
        assertTrue("Measure container open missing:\n" + xml,
            xml.contains("<Measure name=\"Promotion Sales\""));
        assertTrue("Measure container close missing:\n" + xml,
            xml.contains("</Measure>"));
    }

    /** Level can carry KeyExpression / NameExpression /
     *  OrdinalExpression / CaptionExpression, each with its own SQL
     *  dialect list. */
    @Test
    public void yamlConverterEmitsLevelExpressions() {
        String yaml =
            "schema: Exp\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    dimensions:\n"
            + "      - name: Customer\n"
            + "        foreign_key: customer_id\n"
            + "        hierarchy:\n"
            + "          has_all: true\n"
            + "          primary_key: customer_id\n"
            + "          table: customer\n"
            + "          levels:\n"
            + "            - name: Name\n"
            + "              unique_members: true\n"
            + "              key_expression:\n"
            + "                - dialect: generic\n"
            + "                  text: '\"fname\" || '' '' || \"lname\"'\n"
            + "              name_expression:\n"
            + "                - dialect: generic\n"
            + "                  text: '\"fname\" || '' '' || \"lname\"'\n"
            + "              ordinal_expression:\n"
            + "                - dialect: generic\n"
            + "                  text: '\"lname\" || '' '' || \"fname\"'\n"
            + "              caption_expression:\n"
            + "                - dialect: generic\n"
            + "                  text: '\"fname\"'\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("KeyExpression missing:\n" + xml,
            xml.contains("<KeyExpression>")
                && xml.contains("</KeyExpression>"));
        assertTrue("NameExpression missing:\n" + xml,
            xml.contains("<NameExpression>")
                && xml.contains("</NameExpression>"));
        assertTrue("OrdinalExpression missing:\n" + xml,
            xml.contains("<OrdinalExpression>")
                && xml.contains("</OrdinalExpression>"));
        assertTrue("CaptionExpression missing:\n" + xml,
            xml.contains("<CaptionExpression>")
                && xml.contains("</CaptionExpression>"));
        assertTrue("dialect generic missing:\n" + xml,
            xml.contains("<SQL dialect=\"generic\">"));
        // Level must become a container element when expression
        // children are present.
        assertTrue("Level container open missing:\n" + xml,
            xml.contains("<Level name=\"Name\""));
        assertTrue("Level container close missing:\n" + xml,
            xml.contains("</Level>"));
    }

    /** Round-trip via XmlSchemaToYaml + back through YamlSchemaConverter
     *  preserves the MeasureExpression structure. */
    @Test
    public void roundTripPreservesMeasureExpression() {
        String originalXml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"Exp\">\n"
            + "  <Cube name=\"Sales\">\n"
            + "    <Table name=\"sales_fact_1997\"/>\n"
            + "    <Measure name=\"Store Sales\" column=\"store_sales\""
            +            " aggregator=\"sum\"/>\n"
            + "    <Measure name=\"Half Sales\" aggregator=\"sum\""
            +            " datatype=\"Numeric\">\n"
            + "      <MeasureExpression>\n"
            + "        <SQL dialect=\"generic\">"
            +            "(\"sales_fact_1997\".\"store_sales\" * 0.5)"
            +            "</SQL>\n"
            + "      </MeasureExpression>\n"
            + "    </Measure>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        String roundtripXml = YamlSchemaConverter.toXml(yaml);

        assertTrue(
            "MeasureExpression lost in roundtrip:\nyaml=" + yaml
                + "\nxml=" + roundtripXml,
            roundtripXml.contains("<MeasureExpression>"));
        assertTrue(
            "SQL body lost in roundtrip:\nyaml=" + yaml
                + "\nxml=" + roundtripXml,
            roundtripXml.contains("store_sales"));
    }

    /** Acid test: a MeasureExpression-derived measure returns
     *  identical cells across YAML and XML versions of the same
     *  schema. */
    @Test
    public void measureExpressionMatchesXmlEquivalent() {
        String xmlSchema =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"Exp\">\n"
            + "  <Cube name=\"ExpSales\">\n"
            + "    <Table name=\"sales_fact_1997\"/>\n"
            + "    <Dimension name=\"Time\" foreignKey=\"time_id\">\n"
            + "      <Hierarchy hasAll=\"true\" primaryKey=\"time_id\">\n"
            + "        <Table name=\"time_by_day\"/>\n"
            + "        <Level name=\"Year\" column=\"the_year\""
            +              " type=\"Numeric\" uniqueMembers=\"true\"/>\n"
            + "      </Hierarchy>\n"
            + "    </Dimension>\n"
            + "    <Measure name=\"Half Sales\" aggregator=\"sum\""
            +            " datatype=\"Numeric\">\n"
            + "      <MeasureExpression>\n"
            + "        <SQL dialect=\"generic\">"
            +            "(\"sales_fact_1997\".\"store_sales\" * 0.5)"
            +            "</SQL>\n"
            + "      </MeasureExpression>\n"
            + "    </Measure>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        String yamlSchema =
            "schema: Exp\n"
            + "cubes:\n"
            + "  ExpSales:\n"
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
            + "      - name: Half Sales\n"
            + "        aggregator: sum\n"
            + "        datatype: Numeric\n"
            + "        measure_expression:\n"
            + "          - dialect: generic\n"
            + "            text: '(\"sales_fact_1997\".\"store_sales\" * 0.5)'\n";

        String mdx =
            "SELECT [Measures].[Half Sales] ON COLUMNS, "
            + "[Time].[Year].Members ON ROWS "
            + "FROM [ExpSales]";

        String[] xmlCells = runMdx(xmlSchema, mdx);
        String[] yamlCells = runMdx(
            YamlSchemaConverter.toXml(yamlSchema), mdx);

        assertEquals("row count diverged",
            xmlCells.length, yamlCells.length);
        for (int i = 0; i < xmlCells.length; i++) {
            assertEquals(
                "cell " + i + " diverged",
                xmlCells[i], yamlCells[i]);
        }
        boolean anyNonBlank = false;
        for (String c : xmlCells) {
            if (!c.isEmpty()) { anyNonBlank = true; break; }
        }
        assertTrue("expected at least one non-blank cell", anyNonBlank);
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

// End YamlSchemaExpressionsTest.java
