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
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #104: a FULL end-to-end proof — Mondrian → Calcite → H2 — of the
 * shipped demo cube. The Bank demo's "Account Statistics" cube uses the
 * non-additive {@code median} / {@code percentile} aggregators over the
 * {@code mm_fact} accounts, and the demo runs on H2 (which supports
 * {@code PERCENTILE_CONT}). This runs that exact cube through the real
 * engine on H2, confirming the feature works when deployed — not just that
 * the SQL is shaped correctly.
 *
 * <pre>
 *   branch  account balances             median   p90
 *   London  1000, 500, 2000, 4000        1500     3400
 *   Leeds   300, 1500, 700, 3000         1100     2550
 * </pre>
 */
public class PercentileH2EndToEndTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"mm_fact\"",
        "DROP TABLE IF EXISTS \"mm_branch\"",
        "DROP TABLE IF EXISTS \"mm_date\"",
        "CREATE TABLE \"mm_branch\" (\"branch_id\" VARCHAR(8),"
            + " \"branch_name\" VARCHAR(16))",
        "CREATE TABLE \"mm_date\" (\"date_key\" INTEGER, \"yr\" INTEGER)",
        "CREATE TABLE \"mm_fact\" (\"account_id\" INTEGER,"
            + " \"date_key\" INTEGER, \"branch_id\" VARCHAR(8),"
            + " \"balance\" INTEGER, \"fees\" INTEGER)",
        "INSERT INTO \"mm_branch\" VALUES ('LON','London'),('LDS','Leeds')",
        "INSERT INTO \"mm_date\" VALUES (2024,2024),(2025,2025)",
        "INSERT INTO \"mm_fact\" VALUES"
            + " (1,2024,'LON',1000,10),(2,2024,'LON',500,5),"
            + " (3,2025,'LDS',300,3),(4,2024,'LON',2000,20),"
            + " (5,2024,'LDS',1500,15),(6,2025,'LDS',700,7),"
            + " (7,2025,'LON',4000,40),(8,2025,'LDS',3000,30)",
    };

    /** The Account Statistics cube from the shipped Bank demo schema. */
    private static final String SCHEMA =
        "<Schema name='Bank' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='mm_fact'/>\n"
        + "    <Table name='mm_branch'/>\n"
        + "    <Table name='mm_date'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Branch' table='mm_branch' key='Branch'>\n"
        + "    <Attributes><Attribute name='Branch'>\n"
        + "      <Key><Column name='branch_id'/></Key>\n"
        + "      <Name><Column name='branch_name'/></Name>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Date' table='mm_date' key='Date Id'"
        + " type='TIME'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Date Id' hasHierarchy='false'>"
        + "<Key><Column name='date_key'/></Key></Attribute>\n"
        + "      <Attribute name='Year' levelType='TimeYears'"
        + " hasHierarchy='false'><Key><Column name='yr'/></Key></Attribute>\n"
        + "    </Attributes>\n"
        + "    <Hierarchies>"
        + "<Hierarchy name='Calendar' allMemberName='All Years'>"
        + "<Level attribute='Year'/></Hierarchy></Hierarchies>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Account Statistics'>\n"
        + "    <Dimensions>\n"
        + "      <Dimension source='Branch'/>\n"
        + "      <Dimension source='Date'/>\n"
        + "    </Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='Balances' table='mm_fact'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Total Balance' column='balance'"
        + " aggregator='sum'/>\n"
        + "          <Measure name='Median Balance' column='balance'"
        + " aggregator='median'/>\n"
        + "          <Measure name='P90 Balance' column='balance'"
        + " aggregator='percentile' percentile='90'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Branch'"
        + " foreignKeyColumn='branch_id'/>\n"
        + "          <ForeignKeyLink dimension='Date'"
        + " foreignKeyColumn='date_key'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    private static final String H2_URL =
        "jdbc:h2:mem:pctile_e2e;DB_CLOSE_DELAY=-1";

    private static Connection conn;

    @BeforeAll
    public static void boot() throws Exception {
        // Initialise Mondrian's server/classloader state the same way every
        // other query-executing test does, even though this test uses H2 —
        // without it, the shared SegmentCacheManager background thread can
        // fail to lazily load mondrian.server.Execution$1 during post-query
        // cleanup when this test runs after an HSQLDB-based one.
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
        Util.PropertyList props = new Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(),
            "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), SCHEMA);
        conn = DriverManager.getConnection(props, null, null);
    }

    @AfterAll
    public static void close() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }

    /** "row|col" → value. */
    private Map<String, Double> grid(String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Map<String, Double> out = new LinkedHashMap<>();
        Axis cols = r.getAxes()[0];
        Axis rows = r.getAxes()[1];
        int ri = 0;
        for (Position rp : rows.getPositions()) {
            int ci = 0;
            for (Position cp : cols.getPositions()) {
                Object v = r.getCell(new int[]{ci, ri}).getValue();
                out.put(
                    rp.get(0).getName() + "|" + cp.get(0).getName(),
                    v == null ? null : ((Number) v).doubleValue());
                ci++;
            }
            ri++;
        }
        r.close();
        return out;
    }

    /** The shipped Account Statistics cube: median + p90 balance by branch,
     *  executed through the full engine on H2. */
    @Test
    public void medianAndPercentileByBranch() {
        Map<String, Double> g = grid(
            "SELECT {[Measures].[Median Balance], [Measures].[P90 Balance]}"
            + " ON COLUMNS,\n"
            + " [Branch].[Branch].Members ON ROWS\n"
            + "FROM [Account Statistics]");
        assertEquals(1500.0, g.get("London|Median Balance"), 0.001);
        assertEquals(3400.0, g.get("London|P90 Balance"), 0.001);
        assertEquals(1100.0, g.get("Leeds|Median Balance"), 0.001);
        assertEquals(2550.0, g.get("Leeds|P90 Balance"), 0.001);
    }
}
