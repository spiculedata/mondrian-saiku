/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.calcite;

import mondrian.olap.Axis;
import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Position;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #119: measure-level distinct grain (a {@code sum_distinct} /
 * {@code average_distinct} that de-duplicates on a declared key WITHOUT a
 * {@code <BridgeLink>}) — the exact case #117 could not handle. End-to-end on
 * H2 (real SQL), both schema forms (XML and YAML round-trip), and under a
 * row-security role.
 *
 * <p>The fan-out is baked into the fact table: {@code dg_sale_line} holds one
 * row per (sale, line), repeating the sale's {@code amount} on every line. A
 * naive {@code SUM(amount)} over these rows double-counts; a measure declaring
 * {@code distinctKeyColumn='sale_id'} de-duplicates on the sale grain first —
 * {@code SUM} over {@code SELECT DISTINCT (group keys, sale_id, amount)} — and
 * returns the true per-sale total. No bridge is present, so this exercises the
 * SECOND place {@code PlannerRequest.symmetricGrainColumn} is set (the measure
 * declaration), independent of join topology.
 *
 * <pre>
 *   dg_sale_line (the fact — already fanned out)
 *   sale_id line region amount tenant
 *     1      a   North   100     1
 *     1      b   North   100     1
 *     1      c   North   100     1     (sale 1 → 3 lines)
 *     2      a   South    50     1     (sale 2 → 1 line)
 *     3      a   North   300     2
 *     3      b   North   300     2     (sale 3 → 2 lines)
 *
 *   distinct sum of amount over sale_id    = 100 + 50 + 300 = 450
 *   naive SUM over the fanned rows         = 300 + 50 + 600 = 950   (WRONG)
 *   distinct avg of amount over sale_id    = 450 / 3        = 150
 *   by region (distinct): North = 100+300 = 400; South = 50
 * </pre>
 */
public class MeasureDistinctGrainH2EndToEndTest {

    private static final String[] DDL = {
        "DROP TABLE \"dg_sale_line\" IF EXISTS",
        "DROP TABLE \"dg_region\" IF EXISTS",
        "CREATE TABLE \"dg_sale_line\" (\"sale_id\" INTEGER,"
            + " \"line\" VARCHAR(8), \"region_id\" VARCHAR(16),"
            + " \"amount\" INTEGER, \"tenant\" INTEGER)",
        "CREATE TABLE \"dg_region\" (\"region_id\" VARCHAR(16),"
            + " \"region_name\" VARCHAR(32))",
        "INSERT INTO \"dg_sale_line\" VALUES (1, 'a', 'North', 100, 1)",
        "INSERT INTO \"dg_sale_line\" VALUES (1, 'b', 'North', 100, 1)",
        "INSERT INTO \"dg_sale_line\" VALUES (1, 'c', 'North', 100, 1)",
        "INSERT INTO \"dg_sale_line\" VALUES (2, 'a', 'South', 50, 1)",
        "INSERT INTO \"dg_sale_line\" VALUES (3, 'a', 'North', 300, 2)",
        "INSERT INTO \"dg_sale_line\" VALUES (3, 'b', 'North', 300, 2)",
        "INSERT INTO \"dg_region\" VALUES ('North', 'North')",
        "INSERT INTO \"dg_region\" VALUES ('South', 'South')",
    };

    private static final String SCHEMA =
        "<Schema name='DistinctGrain' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='dg_sale_line'/>\n"
        + "    <Table name='dg_region'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric' defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='dg_region' key='Region'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Region'>\n"
        + "        <Key><Column name='region_id'/></Key>\n"
        + "        <Name><Column name='region_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions>\n"
        + "      <Dimension source='Region'/>\n"
        + "    </Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='dg_sale_line'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Distinct Amount' column='amount'"
        + " aggregator='sum' distinctKeyColumn='sale_id'/>\n"
        + "          <Measure name='Distinct Avg' column='amount'"
        + " aggregator='avg' distinctKeyColumn='sale_id'/>\n"
        + "          <Measure name='Naive Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Region'"
        + " foreignKeyColumn='region_id'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "  <Role name='Tenant'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static final String H2_URL =
        "jdbc:h2:mem:dg_e2e;DB_CLOSE_DELAY=-1";

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            for (String sql : DDL) {
                st.execute(sql);
            }
        }
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    @AfterAll
    public static void close() {
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    private static String schemaFor(String form) {
        return "yaml".equals(form)
            ? mondrian.schema.yaml.m4.M4YamlToXml.toXml(
                mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(SCHEMA))
            : SCHEMA;
    }

    private static Connection connect(
        String catalog, String role, String tenant, boolean cache)
    {
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(),
            "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            props.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(props, null, null);
    }

    private static Double scalar(Connection conn, String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Map<String, Double> rowMap(Connection conn, String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Map<String, Double> out = new LinkedHashMap<>();
        Axis rows = r.getAxes()[1];
        int i = 0;
        for (Position pos : rows.getPositions()) {
            Object v = r.getCell(new int[]{0, i}).getValue();
            out.put(pos.get(0).getName(),
                v == null ? null : ((Number) v).doubleValue());
            i++;
        }
        r.close();
        return out;
    }

    // ---- 1) [All] de-dups to the true per-sale total (not the fanned one) --

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void distinctSumDeDupesFanout(String form) {
        Connection conn = connect(schemaFor(form), null, null, false);
        try {
            assertEquals(450.0,
                scalar(conn,
                    "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "distinct sum de-dups on sale_id: 100+50+300 = 450");
            // The naive SUM proves the fan-out is real (950, not 450).
            assertEquals(950.0,
                scalar(conn,
                    "SELECT {[Measures].[Naive Amount]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "naive SUM double-counts the fanned rows: 300+50+600 = 950");
        } finally {
            conn.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- 2) avg_distinct de-dups before averaging --------------------------

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void distinctAvgDeDupesFanout(String form) {
        Connection conn = connect(schemaFor(form), null, null, false);
        try {
            assertEquals(150.0,
                scalar(conn,
                    "SELECT {[Measures].[Distinct Avg]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "distinct avg over 3 sales: (100+50+300)/3 = 150");
        } finally {
            conn.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- 3) the distinct grain rolls up correctly by a dimension ----------

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void distinctSumByRegion(String form) {
        Connection conn = connect(schemaFor(form), null, null, false);
        try {
            Map<String, Double> m = rowMap(conn,
                "SELECT {[Measures].[Distinct Amount]} ON COLUMNS,\n"
                + " NON EMPTY [Region].[Region].Members ON ROWS\n"
                + "FROM [Sales]");
            assertEquals(400.0, m.get("North"), 0.001,
                "North distinct: sale1 100 + sale3 300 = 400");
            assertEquals(50.0, m.get("South"), 0.001,
                "South distinct: sale2 50");
        } finally {
            conn.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- 4) RLS: a PredicateGrant filters fact rows PRE-de-dup ------------

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void predicateGrantFiltersPreDeDup(String form) {
        String schema = schemaFor(form);
        Connection t1 = connect(schema, "Tenant", "1", false);
        Connection t2 = connect(schema, "Tenant", "2", false);
        Connection ungranted = connect(schema, null, null, false);
        try {
            // Tenant 1 sees sales 1,2 only → distinct 100 + 50 = 150.
            assertEquals(150.0,
                scalar(t1,
                    "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "tenant 1: predicate filters to sales 1,2 before de-dup = 150");
            // Tenant 2 sees sale 3 only → distinct 300.
            assertEquals(300.0,
                scalar(t2,
                    "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "tenant 2: predicate filters to sale 3 before de-dup = 300");
            // The two tenants partition the ungranted distinct total.
            assertEquals(450.0,
                scalar(ungranted,
                    "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "ungranted = full distinct total 150 + 300 = 450");
        } finally {
            t1.close();
            t2.close();
            ungranted.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- 5) no cross-role segment-cache bleed for a distinct-grain measure -

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void distinctGrainSegmentNotServedAcrossRoles(String form) {
        // Cache ON: warm tenant 1's distinct-grain segment, then ask tenant 2
        // on a fresh connection sharing the same schema/cache. Tenant 2 must
        // see its own 300, never tenant 1's cached 150.
        String schema = schemaFor(form);
        Connection t1 = connect(schema, "Tenant", "1", true);
        try {
            assertEquals(150.0,
                scalar(t1,
                    "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "warm tenant 1 distinct-grain segment = 150");
        } finally {
            t1.close();
        }
        Connection t2 = connect(schema, "Tenant", "2", true);
        try {
            Double v = scalar(t2,
                "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                + " FROM [Sales]");
            assertTrue(v != null && Math.abs(v - 300.0) < 0.001,
                "tenant 2 must see its own 300, never tenant 1's cached 150"
                + " (got " + v + ")");
        } finally {
            t2.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}

// End MeasureDistinctGrainH2EndToEndTest.java
