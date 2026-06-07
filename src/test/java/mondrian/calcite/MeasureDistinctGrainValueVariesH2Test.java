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

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #119 robustness: a measure-level distinct grain
 * ({@code sum_distinct}/{@code average_distinct}) when the fact repeats
 * DIFFERENT measure values for the same grain key — i.e. the
 * value-functionally-determined-by-grain contract is violated by the data.
 *
 * <p>The old emission projected {@code (keys, grain, value)}, applied
 * {@code SELECT DISTINCT}, then aggregated. That is correct ONLY while the
 * value is constant per grain: when it varies, DISTINCT retains every distinct
 * {@code (grain, value)} pair and the outer {@code SUM}/{@code AVG} counts the
 * grain MORE THAN ONCE — silently over-counting on a legitimate query.
 *
 * <p>The fix collapses each {@code (keys, grain)} to a single deterministic
 * representative value (MIN) before the outer aggregate, so a grain key is
 * counted exactly once regardless of value drift.
 *
 * <pre>
 *   dv_sale_line (fact — fanned out, amount VARIES within sale_id)
 *   sale_id line amount
 *     1      a   100
 *     1      b   200     (sale 1: DIFFERENT amounts on its lines!)
 *     1      c   100
 *     2      a    50
 *     3      a   300
 *     3      b   300
 *
 *   distinct sum over sale_id (one representative/sale, MIN):
 *       100 (sale1) + 50 (sale2) + 300 (sale3) = 450
 *   BUGGY DISTINCT-on-value: sale1 keeps {100,200} → 300; total = 650
 *   distinct avg over sale_id: (100 + 50 + 300) / 3 = 150
 * </pre>
 */
public class MeasureDistinctGrainValueVariesH2Test {

    private static final String[] DDL = {
        "DROP TABLE \"dv_sale_line\" IF EXISTS",
        "CREATE TABLE \"dv_sale_line\" (\"sale_id\" INTEGER,"
            + " \"line\" VARCHAR(8), \"amount\" INTEGER)",
        "INSERT INTO \"dv_sale_line\" VALUES (1, 'a', 100)",
        "INSERT INTO \"dv_sale_line\" VALUES (1, 'b', 200)",
        "INSERT INTO \"dv_sale_line\" VALUES (1, 'c', 100)",
        "INSERT INTO \"dv_sale_line\" VALUES (2, 'a', 50)",
        "INSERT INTO \"dv_sale_line\" VALUES (3, 'a', 300)",
        "INSERT INTO \"dv_sale_line\" VALUES (3, 'b', 300)",
    };

    private static final String SCHEMA =
        "<Schema name='DistinctGrainVary' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='dv_sale_line'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions/>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='dv_sale_line'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Distinct Amount' column='amount'"
        + " aggregator='sum' distinctKeyColumn='sale_id'/>\n"
        + "          <Measure name='Distinct Avg' column='amount'"
        + " aggregator='avg' distinctKeyColumn='sale_id'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks/>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    private static final String H2_URL =
        "jdbc:h2:mem:dv_e2e;DB_CLOSE_DELAY=-1";

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

    private static Connection connect(String catalog) {
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
        return DriverManager.getConnection(props, null, null);
    }

    private static Double scalar(Connection conn, String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void distinctSumCountsEachGrainOnceWhenValueVaries(String form) {
        Connection conn = connect(schemaFor(form));
        try {
            // Each sale counts ONCE (representative MIN value), never the sum of
            // its varying line amounts: 100 + 50 + 300 = 450, NOT 650.
            assertEquals(450.0,
                scalar(conn,
                    "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "distinct sum must count each sale once even when the fact"
                + " repeats different amounts for that sale (got the"
                + " over-counted total?)");
        } finally {
            conn.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void distinctAvgCountsEachGrainOnceWhenValueVaries(String form) {
        Connection conn = connect(schemaFor(form));
        try {
            // (100 + 50 + 300) / 3 sales = 150 — averaged over grain keys, not
            // over the fanned, value-varying lines.
            assertEquals(150.0,
                scalar(conn,
                    "SELECT {[Measures].[Distinct Avg]} ON COLUMNS"
                    + " FROM [Sales]"),
                0.001,
                "distinct avg must average over distinct sales, not lines");
        } finally {
            conn.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}

// End MeasureDistinctGrainValueVariesH2Test.java
