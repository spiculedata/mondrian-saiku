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
import mondrian.olap.MondrianProperties;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.TestContext;
import mondrian.test.PropertySaver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 1 Session 11: YAML as a first-class catalog source for
 * {@code RolapSchemaLoader}. Deployments can now point Mondrian at
 * a {@code schema.yaml} file (via {@code Catalog} connection
 * property) or pass YAML directly (via {@code CatalogContent})
 * without any external pre-conversion step.
 *
 * <p>Detection is two-pronged:
 * <ul>
 *   <li>{@code CatalogContent} string starts with {@code schema:} (no
 *       leading {@code <}) → treat as YAML, forward-convert via
 *       {@link YamlSchemaConverter#toXml(String)}.</li>
 *   <li>{@code Catalog} URL ends with {@code .yaml} or {@code .yml}
 *       → read file and forward-convert. If the URL is a {@code
 *       file://} path, use {@link YamlSchemaConverter#toXmlFromPath
 *       (Path)} so {@code $ref} includes resolve relative to the
 *       file.</li>
 * </ul>
 *
 * <p>All existing XML catalogs work unchanged.
 */
public class YamlCatalogIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String YAML_SCHEMA =
        "schema: YamlCatalog\n"
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
    public void mondrianLoadsYamlPassedAsCatalogContent() {
        // TestContext.withSchema passes its string via the
        // CatalogContent connection property. With the integration
        // wired in, passing YAML there should Just Work.
        TestContext ctx = TestContext.instance().withSchema(YAML_SCHEMA);
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(MDX);
            Result result = conn.execute(q);
            try {
                int rowCount = result.getAxes()[1].getPositions().size();
                assertTrue("expected at least 1 row", rowCount > 0);
                Object v = result.getCell(new int[] {0, 0})
                    .getFormattedValue();
                String cell = v == null ? "" : v.toString();
                // [Time].Members returns the All member at row 0 with
                // the grand total.
                assertEquals("grand total cell mismatch", "266,773", cell);
            } finally {
                result.close();
            }
        } finally {
            conn.close();
        }
    }

    @Test
    public void mondrianLoadsYamlFromCatalogFileUrl() throws Exception {
        // Write YAML to disk and point Mondrian's Catalog property at
        // it. The URL ends in .yaml so the integration should detect
        // it and forward-convert before parsing.
        Path yamlPath = tmp.newFile("schema.yaml").toPath();
        Files.writeString(yamlPath, YAML_SCHEMA, StandardCharsets.UTF_8);

        TestContext ctx = TestContext.instance().withProperties(
            cloneAndSetCatalog(yamlPath.toAbsolutePath().toString()));

        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(MDX);
            Result result = conn.execute(q);
            try {
                int rowCount = result.getAxes()[1].getPositions().size();
                assertTrue(rowCount > 0);
                Object v = result.getCell(new int[] {0, 0})
                    .getFormattedValue();
                assertEquals("266,773",
                    v == null ? "" : v.toString());
            } finally {
                result.close();
            }
        } finally {
            conn.close();
        }
    }

    @Test
    public void mondrianLoadsYamlWithRefIncludesFromFile() throws Exception {
        // Multi-file YAML schema: $ref splice from sibling time.yaml.
        // Requires the file-URL detection to use toXmlFromPath() so
        // the $ref resolves relative to the file containing it.
        Path dir = tmp.getRoot().toPath();
        Path schemaPath = dir.resolve("schema.yaml");
        Path timePath = dir.resolve("time.yaml");
        Files.writeString(
            schemaPath,
            "schema: YamlCatalog\n"
            + "shared_dimensions:\n"
            + "  Time:\n"
            + "    $ref: time.yaml\n"
            + "cubes:\n"
            + "  UnitSalesByYear:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    dimension_usages:\n"
            + "      - name: Time\n"
            + "        source: Time\n"
            + "        foreign_key: time_id\n"
            + "    measures:\n"
            + "      - name: Unit Sales\n"
            + "        column: unit_sales\n"
            + "        aggregator: sum\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            timePath,
            "hierarchy:\n"
            + "  has_all: true\n"
            + "  primary_key: time_id\n"
            + "  table: time_by_day\n"
            + "  levels:\n"
            + "    - name: Year\n"
            + "      column: the_year\n"
            + "      type: Numeric\n"
            + "      unique_members: true\n",
            StandardCharsets.UTF_8);

        TestContext ctx = TestContext.instance().withProperties(
            cloneAndSetCatalog(schemaPath.toAbsolutePath().toString()));

        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(MDX);
            Result result = conn.execute(q);
            try {
                int rowCount = result.getAxes()[1].getPositions().size();
                assertTrue(rowCount > 0);
            } finally {
                result.close();
            }
        } finally {
            conn.close();
        }
    }

    @Test
    public void existingXmlCatalogsStillWorkUnchanged() {
        // Sanity regression: XML schemas mustn't take a new code
        // path. Use the same shape but as XML directly.
        String xml =
            "<?xml version=\"1.0\"?>\n"
            + "<Schema name=\"YamlCatalog\">\n"
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

        TestContext ctx = TestContext.instance().withSchema(xml);
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(MDX);
            Result result = conn.execute(q);
            try {
                int rowCount = result.getAxes()[1].getPositions().size();
                assertTrue(rowCount > 0);
            } finally {
                result.close();
            }
        } finally {
            conn.close();
        }
    }

    /** Build a TestContext property list that points the Catalog
     *  property at the given path and strips any CatalogContent. */
    private static mondrian.olap.Util.PropertyList cloneAndSetCatalog(
        String catalogPath)
    {
        mondrian.olap.Util.PropertyList props =
            TestContext.instance().getConnectionProperties().clone();
        props.put(
            RolapConnectionProperties.Catalog.name(),
            "file://" + catalogPath);
        props.remove(
            RolapConnectionProperties.CatalogContent.name());
        return props;
    }
}

// End YamlCatalogIntegrationTest.java
