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
 * #34 Phase 1 Session 3: {@code NamedSet} (cube + schema scope) and
 * the {@code Role} → {@code SchemaGrant} → {@code CubeGrant} →
 * {@code HierarchyGrant} → {@code MemberGrant} security model.
 *
 * <p>Together these close the "production-deployable schema" gap — a
 * Saiku-style schema that ships with row/cube-level security and the
 * reusable named MDX sets ops people put in dashboards.
 *
 * <p>NamedSet mirrors the CalculatedMember pattern: inline
 * {@code formula:} attribute for one-liners, {@code formula_body:} for
 * multi-line MDX emitted as a {@code <Formula>} child element.
 */
public class YamlSchemaSecurityTest {

    /** Cube-scoped {@code <NamedSet>} with inline formula. */
    @Test
    public void yamlConverterEmitsCubeNamedSetWithFormulaAttr() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    named_sets:\n"
            + "      - name: Top Sellers\n"
            + "        formula: '{[Time].[1997]}'\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("NamedSet self-closing form missing:\n" + xml,
            xml.contains(
                "<NamedSet name=\"Top Sellers\""
                    + " formula=\"{[Time].[1997]}\"/>"));
    }

    /** Cube-scoped {@code <NamedSet>} with {@code <Formula>} child
     *  element (the multi-line form). */
    @Test
    public void yamlConverterEmitsCubeNamedSetWithFormulaBody() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    named_sets:\n"
            + "      - name: Best Years\n"
            + "        formula_body: |\n"
            + "          TopCount([Time].[Year].Members, 3,\n"
            + "                   [Measures].[Unit Sales])\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("NamedSet container open missing:\n" + xml,
            xml.contains("<NamedSet name=\"Best Years\">"));
        assertTrue("<Formula> child element missing:\n" + xml,
            xml.contains("<Formula>")
                && xml.contains("</Formula>"));
        assertTrue("formula text not preserved:\n" + xml,
            xml.contains("TopCount")
                && xml.contains("[Measures].[Unit Sales]"));
        assertTrue("NamedSet close missing:\n" + xml,
            xml.contains("</NamedSet>"));
    }

    /** Schema-scoped (top-level) {@code <NamedSet>}. */
    @Test
    public void yamlConverterEmitsSchemaScopedNamedSet() {
        String yaml =
            "schema: Phase1\n"
            + "named_sets:\n"
            + "  - name: Global Set\n"
            + "    formula: '{[Time].[1997]}'\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        // Schema-scoped NamedSet lives between <Schema> and <Cube>,
        // outside any Cube element.
        int schemaOpen = xml.indexOf("<Schema name=\"Phase1\"");
        int nsOpen = xml.indexOf("<NamedSet ", schemaOpen);
        int cubeOpen = xml.indexOf("<Cube ", schemaOpen);
        assertTrue("schema-scoped NamedSet must precede first cube:\n" + xml,
            schemaOpen >= 0 && nsOpen > schemaOpen
                && cubeOpen > nsOpen);
    }

    /** Minimal {@code <Role>} with a flat {@code <SchemaGrant>}. */
    @Test
    public void yamlConverterEmitsRoleWithSchemaGrantOnly() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "roles:\n"
            + "  - name: ReadAll\n"
            + "    schema_grant:\n"
            + "      access: all\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("Role open missing:\n" + xml,
            xml.contains("<Role name=\"ReadAll\">"));
        assertTrue("SchemaGrant self-closing missing:\n" + xml,
            xml.contains("<SchemaGrant access=\"all\"/>"));
        assertTrue("Role close missing:\n" + xml,
            xml.contains("</Role>"));
    }

    /** Full {@code Role} → {@code SchemaGrant} → {@code CubeGrant} →
     *  {@code HierarchyGrant} → {@code MemberGrant} stack — the
     *  canonical FoodMart "California manager" shape. */
    @Test
    public void yamlConverterEmitsRoleFullGrantStack() {
        String yaml =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "roles:\n"
            + "  - name: California manager\n"
            + "    schema_grant:\n"
            + "      access: none\n"
            + "      cubes:\n"
            + "        - cube: Sales\n"
            + "          access: all\n"
            + "          hierarchies:\n"
            + "            - hierarchy: '[Store]'\n"
            + "              access: custom\n"
            + "              top_level: '[Store].[Store Country]'\n"
            + "              members:\n"
            + "                - member: '[Store].[USA].[CA]'\n"
            + "                  access: all\n"
            + "                - member: '[Store].[USA].[CA].[Los Angeles]'\n"
            + "                  access: none\n"
            + "            - hierarchy: '[Gender]'\n"
            + "              access: none\n";

        String xml = YamlSchemaConverter.toXml(yaml);

        assertTrue("Role missing:\n" + xml,
            xml.contains("<Role name=\"California manager\">"));
        assertTrue("SchemaGrant container missing:\n" + xml,
            xml.contains("<SchemaGrant access=\"none\">"));
        assertTrue("CubeGrant missing:\n" + xml,
            xml.contains(
                "<CubeGrant cube=\"Sales\" access=\"all\">"));
        assertTrue("HierarchyGrant [Store] missing:\n" + xml,
            xml.contains(
                "<HierarchyGrant hierarchy=\"[Store]\""
                    + " access=\"custom\""
                    + " topLevel=\"[Store].[Store Country]\">"));
        assertTrue("MemberGrant CA all missing:\n" + xml,
            xml.contains(
                "<MemberGrant member=\"[Store].[USA].[CA]\""
                    + " access=\"all\"/>"));
        assertTrue("MemberGrant LA none missing:\n" + xml,
            xml.contains(
                "<MemberGrant"
                    + " member=\"[Store].[USA].[CA].[Los Angeles]\""
                    + " access=\"none\"/>"));
        assertTrue("HierarchyGrant [Gender] self-closing missing:\n" + xml,
            xml.contains(
                "<HierarchyGrant hierarchy=\"[Gender]\""
                    + " access=\"none\"/>"));
        assertTrue("HierarchyGrant close missing:\n" + xml,
            xml.contains("</HierarchyGrant>"));
        assertTrue("CubeGrant close missing:\n" + xml,
            xml.contains("</CubeGrant>"));
        assertTrue("SchemaGrant close missing:\n" + xml,
            xml.contains("</SchemaGrant>"));
    }

    /** End-to-end equivalence: schema-scoped NamedSet + cube-scoped
     *  NamedSet referenced from MDX returns identical cells across YAML
     *  and XML versions of the same schema. */
    @Test
    public void yamlNamedSetMatchesXmlEquivalent() {
        final String xmlSchema =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"Phase1\">\n"
            + "  <Cube name=\"NSSales\">\n"
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
            + "    <NamedSet name=\"All Years\""
            +            " formula=\"[Time].[Year].Members\"/>\n"
            + "  </Cube>\n"
            + "</Schema>\n";

        final String yamlSchema =
            "schema: Phase1\n"
            + "cubes:\n"
            + "  NSSales:\n"
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
            + "    named_sets:\n"
            + "      - name: All Years\n"
            + "        formula: '[Time].[Year].Members'\n";

        final String mdx =
            "SELECT [Measures].[Unit Sales] ON COLUMNS, "
            + "[All Years] ON ROWS "
            + "FROM [NSSales]";

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
        boolean anyNonBlank = false;
        for (String c : xmlCells) {
            if (!c.isEmpty()) { anyNonBlank = true; break; }
        }
        assertTrue("expected at least one non-blank cell, got: "
                + java.util.Arrays.toString(xmlCells),
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

// End YamlSchemaSecurityTest.java
