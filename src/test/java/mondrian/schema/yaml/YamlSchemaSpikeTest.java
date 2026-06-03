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
 * #34 Phase 0 spike: confirms YAML schema can drive Mondrian
 * end-to-end. Same minimal one-cube fixture written two ways —
 * Mondrian XML and YAML — both loaded via
 * {@link TestContext#withSchema(String)}, the same MDX query run
 * against each, assert byte-identical cell values.
 *
 * <p>Tests the architectural call: <em>can we cleanly inject a
 * non-XML format into MondrianDef / RolapSchemaLoader?</em> If yes,
 * the wider scope (full Sales cube, $ref includes, round-trip,
 * lint tool) becomes mechanical. If no, we learn cheaply before
 * committing weeks of follow-up work.
 */
public class YamlSchemaSpikeTest {

    /** Minimal one-cube schema in Mondrian's existing XML format. */
    private static final String XML_SCHEMA =
        "<?xml version=\"1.0\"?>\n"
        + "<Schema name=\"YamlSpike\">\n"
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

    /** Same schema written in YAML. */
    private static final String YAML_SCHEMA =
        "schema: YamlSpike\n"
        + "cubes:\n"
        + "  UnitSalesByYear:\n"
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
        + "        aggregator: sum\n";

    private static final String MDX =
        "SELECT [Measures].[Unit Sales] ON COLUMNS, "
        + "[Time].Members ON ROWS "
        + "FROM [UnitSalesByYear]";

    @Test
    public void yamlConverterEmitsLoadableMondrianXml() {
        String xmlFromYaml = YamlSchemaConverter.toXml(YAML_SCHEMA);
        // Sanity checks on the converter output before involving Mondrian.
        assertNotNull(xmlFromYaml);
        assertTrue(
            "expected emitted XML to declare the schema name — got:\n"
                + xmlFromYaml,
            xmlFromYaml.contains("<Schema name=\"YamlSpike\""));
        assertTrue(
            "expected the cube element — got:\n" + xmlFromYaml,
            xmlFromYaml.contains("<Cube name=\"UnitSalesByYear\""));
        assertTrue(
            "expected hierarchy attribute hasAll=true (camelCase) — got:\n"
                + xmlFromYaml,
            xmlFromYaml.contains("hasAll=\"true\""));
        assertTrue(
            "expected level uniqueMembers attribute — got:\n" + xmlFromYaml,
            xmlFromYaml.contains("uniqueMembers=\"true\""));
    }

    /**
     * The deliverable: a YAML-loaded schema returns identical query
     * results to the same schema loaded from XML. Proves YAML can
     * drive Mondrian end-to-end.
     */
    @Test
    public void yamlLoadedSchemaMatchesXmlLoadedSchemaQueryResults() {
        String[] xmlCells = runAndStringify(XML_SCHEMA);
        String xmlFromYaml = YamlSchemaConverter.toXml(YAML_SCHEMA);
        String[] yamlCells = runAndStringify(xmlFromYaml);

        assertEquals(
            "YAML and XML cell counts diverged",
            xmlCells.length, yamlCells.length);
        for (int i = 0; i < xmlCells.length; i++) {
            assertEquals(
                "YAML and XML cell value diverged at row " + i,
                xmlCells[i], yamlCells[i]);
        }
        // Pin a known value so the test catches "both loaded but no
        // data" silent failures.
        assertTrue(
            "expected at least one non-blank cell — got: "
                + java.util.Arrays.toString(xmlCells),
            xmlCells.length >= 1 && !xmlCells[0].isEmpty());
    }

    /**
     * Loads {@code schemaXml} into Mondrian, runs {@link #MDX}, returns
     * each cell's formatted-value string in row order.
     */
    private static String[] runAndStringify(String schemaXml) {
        TestContext ctx = TestContext.instance().withSchema(schemaXml);
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(MDX);
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

// End YamlSchemaSpikeTest.java
