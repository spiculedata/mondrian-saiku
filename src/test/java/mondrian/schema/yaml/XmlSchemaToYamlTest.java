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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 1 Session 4: the reverse converter — given a Mondrian XML
 * schema (or any string containing one), emit the equivalent YAML the
 * forward {@link YamlSchemaConverter} would consume.
 *
 * <p>The migration tool people will actually use. Pre-existing XML
 * schemas (FoodMart, customer deployments, regression fixtures) can
 * now be lifted into YAML without hand-conversion.
 *
 * <p>The acid test is roundtrip semantic equality: XML → YAML →
 * XML' → run MDX against both XML and XML', assert byte-identical
 * cells. Textual XML equality isn't the goal (attribute order,
 * whitespace, comment stripping all change). What matters is that
 * the round-tripped schema returns the same cell-set.
 */
public class XmlSchemaToYamlTest {

    /** Smoke: schema name is preserved. */
    @Test
    public void reverseConverterEmitsSchemaName() {
        String xml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"X\"/>\n";

        String yaml = XmlSchemaToYaml.toYaml(xml);

        assertNotNull(yaml);
        assertTrue("schema: X missing from YAML:\n" + yaml,
            yaml.contains("schema:") && yaml.contains("X"));
    }

    /** Cube + minimal table → cubes:/fact_table:. */
    @Test
    public void reverseConverterEmitsMinimalCube() {
        String xml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"X\">\n"
            + "  <Cube name=\"Sales\">\n"
            + "    <Table name=\"sales_fact_1997\"/>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        String yaml = XmlSchemaToYaml.toYaml(xml);

        assertTrue("cubes: key missing:\n" + yaml,
            yaml.contains("cubes:"));
        assertTrue("cube name missing:\n" + yaml,
            yaml.contains("Sales:"));
        assertTrue("fact_table key missing:\n" + yaml,
            yaml.contains("fact_table:"));
        assertTrue("table value missing:\n" + yaml,
            yaml.contains("sales_fact_1997"));
    }

    /** camelCase XML attrs → snake_case YAML keys. */
    @Test
    public void reverseConverterSnakeCasesAttributeNames() {
        String xml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"X\">\n"
            + "  <Cube name=\"Sales\">\n"
            + "    <Table name=\"sales_fact_1997\"/>\n"
            + "    <Dimension name=\"Time\" foreignKey=\"time_id\">\n"
            + "      <Hierarchy hasAll=\"true\" primaryKey=\"time_id\">\n"
            + "        <Table name=\"time_by_day\"/>\n"
            + "        <Level name=\"Year\" column=\"the_year\""
            +              " uniqueMembers=\"true\"/>\n"
            + "      </Hierarchy>\n"
            + "    </Dimension>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        String yaml = XmlSchemaToYaml.toYaml(xml);

        assertTrue("foreignKey not snake-cased to foreign_key:\n" + yaml,
            yaml.contains("foreign_key:"));
        assertTrue("hasAll not snake-cased to has_all:\n" + yaml,
            yaml.contains("has_all:"));
        assertTrue("primaryKey not snake-cased to primary_key:\n" + yaml,
            yaml.contains("primary_key:"));
        assertTrue("uniqueMembers not snake-cased to unique_members:\n" + yaml,
            yaml.contains("unique_members:"));
    }

    /** Roundtrip equivalence — the deliverable. */
    @Test
    public void roundtripMinimalCubeReturnsIdenticalCells() {
        String originalXml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"Roundtrip\">\n"
            + "  <Cube name=\"UnitSalesByYear\">\n"
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
            + "  </Cube>\n"
            + "</Schema>\n";

        final String mdx =
            "SELECT [Measures].[Unit Sales] ON COLUMNS, "
            + "[Time].Members ON ROWS "
            + "FROM [UnitSalesByYear]";

        String[] originalCells = runMdx(originalXml, mdx);

        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        String roundtripXml = YamlSchemaConverter.toXml(yaml);
        String[] roundtripCells = runMdx(roundtripXml, mdx);

        assertEquals("row count diverged after roundtrip",
            originalCells.length, roundtripCells.length);
        for (int i = 0; i < originalCells.length; i++) {
            assertEquals(
                "cell " + i + " diverged after roundtrip"
                    + "\n  original: " + originalCells[i]
                    + "\n  round-tripped: " + roundtripCells[i]
                    + "\n  YAML produced: " + yaml
                    + "\n  XML produced: " + roundtripXml,
                originalCells[i], roundtripCells[i]);
        }
    }

    /** Roundtrip with the full FoodMart-shape Sales cube — shared
     *  dims, DimensionUsage, multi-table Join, Calculated Member. */
    @Test
    public void roundtripFullSalesCubeReturnsIdenticalCells() {
        final String originalXml =
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
            + "    <Measure name=\"Store Sales\" column=\"store_sales\""
            +            " aggregator=\"sum\"/>\n"
            + "    <CalculatedMember name=\"Avg Sale\" dimension=\"Measures\""
            +            " formula=\"[Measures].[Store Sales]"
            +                     " / [Measures].[Unit Sales]\"/>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        final String mdx =
            "SELECT {[Measures].[Unit Sales], [Measures].[Avg Sale]}"
            + " ON COLUMNS, "
            + "[Product].[Product Family].Members ON ROWS "
            + "FROM [Sales]";

        String[] originalCells = runMdx(originalXml, mdx);
        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        String roundtripXml = YamlSchemaConverter.toXml(yaml);
        String[] roundtripCells = runMdx(roundtripXml, mdx);

        assertEquals("row count diverged after roundtrip",
            originalCells.length, roundtripCells.length);
        for (int i = 0; i < originalCells.length; i++) {
            assertEquals(
                "cell " + i + " diverged after roundtrip"
                    + "\n  YAML produced:\n" + yaml
                    + "\n  XML produced:\n" + roundtripXml,
                originalCells[i], roundtripCells[i]);
        }
        boolean anyNonBlank = false;
        for (String c : originalCells) {
            if (!c.isEmpty()) { anyNonBlank = true; break; }
        }
        assertTrue("expected at least one non-blank cell, got: "
                + java.util.Arrays.toString(originalCells),
            anyNonBlank);
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
                int colCount = result.getAxes().length >= 1
                    ? result.getAxes()[0].getPositions().size() : 0;
                String[] cells = new String[rowCount * colCount];
                int k = 0;
                for (int r = 0; r < rowCount; r++) {
                    for (int c = 0; c < colCount; c++) {
                        Object value = result.getCell(new int[] {c, r})
                            .getFormattedValue();
                        cells[k++] = value == null ? "" : value.toString();
                    }
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

// End XmlSchemaToYamlTest.java
