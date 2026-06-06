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
 * Issue #104: a FULL end-to-end proof — Mondrian → Calcite → H2 — that a
 * {@code median} / {@code percentile} measure returns the hand-computed
 * value when executed through the real engine on a backend that supports
 * {@code PERCENTILE_CONT} (H2). This is the scenario the demo runs (the
 * demo's datasource is H2), so it confirms the feature will work when
 * deployed, not just that the SQL is shaped correctly.
 *
 * <pre>
 *   region  amount
 *   North   10, 20, 30          median 20   p90 28
 *   South   5, 15, 25, 35       median 20   p90 32
 * </pre>
 */
public class PercentileH2EndToEndTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"stats_fact\"",
        "DROP TABLE IF EXISTS \"stats_region\"",
        "CREATE TABLE \"stats_region\" (\"region_id\" VARCHAR(8),"
            + " \"region_name\" VARCHAR(16))",
        "CREATE TABLE \"stats_fact\" (\"region_id\" VARCHAR(8),"
            + " \"amount\" INTEGER)",
        "INSERT INTO \"stats_region\" VALUES ('N','North'),('S','South')",
        "INSERT INTO \"stats_fact\" VALUES"
            + " ('N',10),('N',20),('N',30),"
            + " ('S',5),('S',15),('S',25),('S',35)",
    };

    private static final String SCHEMA =
        "<Schema name='Stats' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='stats_fact'/>\n"
        + "    <Table name='stats_region'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Region' table='stats_region' key='Region'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Region'>\n"
        + "        <Key><Column name='region_id'/></Key>\n"
        + "        <Name><Column name='region_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Stats'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='Amounts' table='stats_fact'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Median Amount' column='amount'"
        + " aggregator='median'/>\n"
        + "          <Measure name='P90 Amount' column='amount'"
        + " aggregator='percentile' percentile='90'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Region'"
        + " foreignKeyColumn='region_id'/>\n"
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

    /** "region|measure" → value. */
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

    @Test
    public void medianAndPercentileByRegion() {
        Map<String, Double> g = grid(
            "SELECT {[Measures].[Median Amount], [Measures].[P90 Amount]}"
            + " ON COLUMNS,\n"
            + " [Region].[Region].Members ON ROWS\n"
            + "FROM [Stats]");
        assertEquals(20.0, g.get("North|Median Amount"), 0.001);
        assertEquals(28.0, g.get("North|P90 Amount"), 0.001);
        assertEquals(20.0, g.get("South|Median Amount"), 0.001);
        assertEquals(32.0, g.get("South|P90 Amount"), 0.001);
    }
}
