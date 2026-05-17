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
 * #34 Phase 1 Session 10: {@code Closure} (parent-child hierarchy
 * closure-table reference) inside a Level, plus {@code VirtualCube}
 * (composite cube that re-exposes dims and measures from multiple
 * base cubes) with {@code VirtualCubeDimension},
 * {@code VirtualCubeMeasure}, and nested {@code CalculatedMember}.
 *
 * <p>These are the last two missing M3 element types: Closure powers
 * the FoodMart Employee parent-child hierarchy and VirtualCube
 * powers FoodMart's "Warehouse and Sales" cross-base-cube analytics.
 * Round-trip MDX equivalence test for the virtual cube proves
 * end-to-end correctness.
 */
public class YamlSchemaClosureVcubeTest {

    /** Closure element + new Level attrs (parent_column, name_column,
     *  null_parent_value). */
    @Test
    public void yamlConverterEmitsClosure() {
        String yaml =
            "schema: Closure\n"
            + "cubes:\n"
            + "  HR:\n"
            + "    fact_table: salary\n"
            + "    dimensions:\n"
            + "      - name: Employee\n"
            + "        foreign_key: employee_id\n"
            + "        hierarchy:\n"
            + "          has_all: true\n"
            + "          all_member_name: All Employees\n"
            + "          primary_key: employee_id\n"
            + "          table: employee\n"
            + "          levels:\n"
            + "            - name: Employee Id\n"
            + "              type: Numeric\n"
            + "              unique_members: true\n"
            + "              column: employee_id\n"
            + "              parent_column: supervisor_id\n"
            + "              name_column: full_name\n"
            + "              null_parent_value: '0'\n"
            + "              closure:\n"
            + "                parent_column: supervisor_id\n"
            + "                child_column: employee_id\n"
            + "                table: employee_closure\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("Level parentColumn missing:\n" + xml,
            xml.contains("parentColumn=\"supervisor_id\""));
        assertTrue("Level nameColumn missing:\n" + xml,
            xml.contains("nameColumn=\"full_name\""));
        assertTrue("Level nullParentValue missing:\n" + xml,
            xml.contains("nullParentValue=\"0\""));
        assertTrue("Closure open missing:\n" + xml,
            xml.contains(
                "<Closure parentColumn=\"supervisor_id\""
                    + " childColumn=\"employee_id\">"));
        assertTrue("Closure Table missing:\n" + xml,
            xml.contains("<Table name=\"employee_closure\"/>"));
        assertTrue("Closure close missing:\n" + xml,
            xml.contains("</Closure>"));
    }

    /** Closure round-trips through XML → YAML → XML. */
    @Test
    public void closureRoundTripsCleanly() {
        String xml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"Closure\">\n"
            + "  <Cube name=\"HR\">\n"
            + "    <Table name=\"salary\"/>\n"
            + "    <Dimension name=\"Employee\" foreignKey=\"employee_id\">\n"
            + "      <Hierarchy hasAll=\"true\""
            +              " allMemberName=\"All Employees\""
            +              " primaryKey=\"employee_id\">\n"
            + "        <Table name=\"employee\"/>\n"
            + "        <Level name=\"Employee Id\" type=\"Numeric\""
            +              " uniqueMembers=\"true\" column=\"employee_id\""
            +              " parentColumn=\"supervisor_id\""
            +              " nameColumn=\"full_name\""
            +              " nullParentValue=\"0\">\n"
            + "          <Closure parentColumn=\"supervisor_id\""
            +                  " childColumn=\"employee_id\">\n"
            + "            <Table name=\"employee_closure\"/>\n"
            + "          </Closure>\n"
            + "        </Level>\n"
            + "      </Hierarchy>\n"
            + "    </Dimension>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        String yaml = XmlSchemaToYaml.toYaml(xml);
        String back = YamlSchemaConverter.toXml(yaml);

        assertTrue("Closure lost in round-trip; yaml=\n" + yaml + "\nxml=\n"
                + back,
            back.contains("<Closure parentColumn=\"supervisor_id\"")
                && back.contains("childColumn=\"employee_id\"")
                && back.contains("<Table name=\"employee_closure\"/>"));
    }

    /** VirtualCube with dimensions, measures (cross-cube refs), and
     *  a calculated member. */
    @Test
    public void yamlConverterEmitsVirtualCube() {
        String yaml =
            "schema: V\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "virtual_cubes:\n"
            + "  Warehouse and Sales:\n"
            + "    default_measure: Store Sales\n"
            + "    dimensions:\n"
            + "      - cube_name: Sales\n"
            + "        name: Customers\n"
            + "      - name: Product\n"
            + "    measures:\n"
            + "      - cube_name: Sales\n"
            + "        name: '[Measures].[Store Sales]'\n"
            + "      - cube_name: Warehouse\n"
            + "        name: '[Measures].[Warehouse Sales]'\n"
            + "    calculated_members:\n"
            + "      - name: Profit Per Unit Shipped\n"
            + "        dimension: Measures\n"
            + "        formula_body: |\n"
            + "          [Measures].[Profit] / [Measures].[Units Shipped]\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("VirtualCube open missing:\n" + xml,
            xml.contains(
                "<VirtualCube name=\"Warehouse and Sales\""
                    + " defaultMeasure=\"Store Sales\">"));
        assertTrue("VirtualCubeDimension with cubeName missing:\n" + xml,
            xml.contains(
                "<VirtualCubeDimension cubeName=\"Sales\""
                    + " name=\"Customers\"/>"));
        assertTrue("VirtualCubeDimension without cubeName missing:\n" + xml,
            xml.contains(
                "<VirtualCubeDimension name=\"Product\"/>"));
        assertTrue("VirtualCubeMeasure for Sales missing:\n" + xml,
            xml.contains(
                "<VirtualCubeMeasure cubeName=\"Sales\""
                    + " name=\"[Measures].[Store Sales]\"/>"));
        assertTrue("VirtualCubeMeasure for Warehouse missing:\n" + xml,
            xml.contains(
                "<VirtualCubeMeasure cubeName=\"Warehouse\""
                    + " name=\"[Measures].[Warehouse Sales]\"/>"));
        assertTrue("VirtualCube CalculatedMember missing:\n" + xml,
            xml.contains(
                "<CalculatedMember name=\"Profit Per Unit Shipped\""));
        assertTrue("VirtualCube Formula child missing:\n" + xml,
            xml.contains("<Formula>"));
        assertTrue("VirtualCube close missing:\n" + xml,
            xml.contains("</VirtualCube>"));
    }

    /** VirtualCube round-trips through XML → YAML → XML. */
    @Test
    public void virtualCubeRoundTripsCleanly() {
        String xml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"V\">\n"
            + "  <Cube name=\"Sales\">\n"
            + "    <Table name=\"sales_fact_1997\"/>\n"
            + "  </Cube>\n"
            + "  <VirtualCube name=\"WS\" defaultMeasure=\"X\">\n"
            + "    <VirtualCubeDimension name=\"Time\"/>\n"
            + "    <VirtualCubeDimension cubeName=\"Sales\""
            +            " name=\"Customers\"/>\n"
            + "    <VirtualCubeMeasure cubeName=\"Sales\""
            +            " name=\"[Measures].[X]\"/>\n"
            + "    <CalculatedMember name=\"Y\" dimension=\"Measures\""
            +            " formula=\"[Measures].[X] * 2\"/>\n"
            + "  </VirtualCube>\n"
            + "</Schema>\n";

        String yaml = XmlSchemaToYaml.toYaml(xml);
        String back = YamlSchemaConverter.toXml(yaml);

        assertTrue("VirtualCube name lost; yaml=" + yaml,
            back.contains("<VirtualCube name=\"WS\""));
        assertTrue("default measure lost:\n" + back,
            back.contains("defaultMeasure=\"X\""));
        assertTrue("VirtualCubeDimension without cubeName lost:\n" + back,
            back.contains("<VirtualCubeDimension name=\"Time\"/>"));
        assertTrue("VirtualCubeDimension with cubeName lost:\n" + back,
            back.contains(
                "<VirtualCubeDimension cubeName=\"Sales\""
                    + " name=\"Customers\"/>"));
        assertTrue("VirtualCubeMeasure lost:\n" + back,
            back.contains(
                "<VirtualCubeMeasure cubeName=\"Sales\""
                    + " name=\"[Measures].[X]\"/>"));
        assertTrue("Nested CalculatedMember lost:\n" + back,
            back.contains("<CalculatedMember name=\"Y\""));
    }

    /** Acid test: a VirtualCube referencing two base cubes returns
     *  byte-identical MDX cells across YAML and XML versions of the
     *  same schema. */
    @Test
    public void virtualCubeMatchesXmlEquivalentOnRealMdx() {
        // Two base cubes share a fact table for simplicity — the test
        // only needs cross-cube measure references to exercise the
        // VirtualCube path, not multi-fact joins.
        String xmlSchema =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"VirtualSchema\">\n"
            + "  <Dimension name=\"Time\">\n"
            + "    <Hierarchy hasAll=\"true\" primaryKey=\"time_id\">\n"
            + "      <Table name=\"time_by_day\"/>\n"
            + "      <Level name=\"Year\" column=\"the_year\""
            +            " type=\"Numeric\" uniqueMembers=\"true\"/>\n"
            + "    </Hierarchy>\n"
            + "  </Dimension>\n"
            + "  <Cube name=\"Sales\">\n"
            + "    <Table name=\"sales_fact_1997\"/>\n"
            + "    <DimensionUsage name=\"Time\" source=\"Time\""
            +            " foreignKey=\"time_id\"/>\n"
            + "    <Measure name=\"Unit Sales\" column=\"unit_sales\""
            +            " aggregator=\"sum\"/>\n"
            + "  </Cube>\n"
            + "  <Cube name=\"Warehouse\">\n"
            + "    <Table name=\"inventory_fact_1997\"/>\n"
            + "    <DimensionUsage name=\"Time\" source=\"Time\""
            +            " foreignKey=\"time_id\"/>\n"
            + "    <Measure name=\"Warehouse Sales\""
            +            " column=\"warehouse_sales\""
            +            " aggregator=\"sum\"/>\n"
            + "  </Cube>\n"
            + "  <VirtualCube name=\"WS\""
            +            " defaultMeasure=\"Unit Sales\">\n"
            + "    <VirtualCubeDimension name=\"Time\"/>\n"
            + "    <VirtualCubeMeasure cubeName=\"Sales\""
            +            " name=\"[Measures].[Unit Sales]\"/>\n"
            + "    <VirtualCubeMeasure cubeName=\"Warehouse\""
            +            " name=\"[Measures].[Warehouse Sales]\"/>\n"
            + "  </VirtualCube>\n"
            + "</Schema>\n";

        String yamlSchema =
            "schema: VirtualSchema\n"
            + "shared_dimensions:\n"
            + "  Time:\n"
            + "    hierarchy:\n"
            + "      has_all: true\n"
            + "      primary_key: time_id\n"
            + "      table: time_by_day\n"
            + "      levels:\n"
            + "        - name: Year\n"
            + "          column: the_year\n"
            + "          type: Numeric\n"
            + "          unique_members: true\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    dimension_usages:\n"
            + "      - name: Time\n"
            + "        source: Time\n"
            + "        foreign_key: time_id\n"
            + "    measures:\n"
            + "      - name: Unit Sales\n"
            + "        column: unit_sales\n"
            + "        aggregator: sum\n"
            + "  Warehouse:\n"
            + "    fact_table: inventory_fact_1997\n"
            + "    dimension_usages:\n"
            + "      - name: Time\n"
            + "        source: Time\n"
            + "        foreign_key: time_id\n"
            + "    measures:\n"
            + "      - name: Warehouse Sales\n"
            + "        column: warehouse_sales\n"
            + "        aggregator: sum\n"
            + "virtual_cubes:\n"
            + "  WS:\n"
            + "    default_measure: Unit Sales\n"
            + "    dimensions:\n"
            + "      - name: Time\n"
            + "    measures:\n"
            + "      - cube_name: Sales\n"
            + "        name: '[Measures].[Unit Sales]'\n"
            + "      - cube_name: Warehouse\n"
            + "        name: '[Measures].[Warehouse Sales]'\n";

        String mdx =
            "SELECT {[Measures].[Unit Sales], [Measures].[Warehouse Sales]}"
            + " ON COLUMNS, "
            + "[Time].[Year].Members "
            + "ON ROWS "
            + "FROM [WS]";

        String[] xmlCells = runMdx(xmlSchema, mdx);
        String[] yamlCells = runMdx(
            YamlSchemaConverter.toXml(yamlSchema), mdx);

        assertEquals("row × col count diverged",
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

// End YamlSchemaClosureVcubeTest.java
