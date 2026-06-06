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

import mondrian.schema.yaml.m4.M4XmlToYaml;
import mondrian.schema.yaml.m4.M4YamlToXml;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #108: native {@code <Tier>} (binning) and {@code <Duration>}
 * (date-diff) dimension/attribute types desugar to a computed column
 * rendered per-dialect via Calcite. Runs the full engine on H2.
 *
 * <p>The fixture is a tiny sales fact with a unit count and an
 * order/ship date pair, deliberately seeded with rows that land
 * <em>on</em> the tier boundaries (units = 10 and units = 100) so the
 * {@code <} vs {@code <=} boundary semantics are pinned by assertion.
 *
 * <pre>
 *   tier bins:  units &lt; 10 → Small, &lt; 100 → Medium, else Large
 *   row units:  5, 10, 50, 100, 250
 *   →           Small, Medium, Medium, Large, Large
 *   member ORDER must be boundary order (Small, Medium, Large) — a
 *   lexical sort would give Large, Medium, Small.
 *
 *   duration (DAY):   ship_date − order_date
 *   duration (MONTH): months between
 * </pre>
 */
public class TierDurationH2EndToEndTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"td_fact\"",
        "DROP TABLE IF EXISTS \"td_dim\"",
        // Dimension table carries the tier source column and the
        // duration start/end columns; the fact references it by id.
        "CREATE TABLE \"td_dim\" ("
            + " \"id\" INTEGER,"
            + " \"units\" INTEGER,"
            + " \"order_date\" DATE,"
            + " \"ship_date\" DATE)",
        "CREATE TABLE \"td_fact\" ("
            + " \"dim_id\" INTEGER,"
            + " \"amount\" INTEGER)",
        // units chosen to hit both boundaries (10, 100):
        //   5  → Small   (5 < 10)
        //   10 → Medium  (NOT < 10; 10 < 100)         boundary row
        //   50 → Medium
        //   100→ Large   (NOT < 100)                  boundary row
        //   250→ Large
        // ship − order: 3, 10, 40, 70, 100 days.
        "INSERT INTO \"td_dim\" VALUES"
            + " (1,   5, DATE '2024-01-01', DATE '2024-01-04'),"
            + " (2,  10, DATE '2024-01-01', DATE '2024-01-11'),"
            + " (3,  50, DATE '2024-01-01', DATE '2024-02-10'),"
            + " (4, 100, DATE '2024-01-01', DATE '2024-03-11'),"
            + " (5, 250, DATE '2024-01-01', DATE '2024-04-10')",
        "INSERT INTO \"td_fact\" VALUES"
            + " (1,100),(2,100),(3,100),(4,100),(5,100)",
    };

    private static final String SCHEMA =
        "<Schema name='TD' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='td_fact'/>\n"
        + "    <Table name='td_dim'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Size' table='td_dim' key='Id'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Id' hasHierarchy='false'>"
        + "<Key><Column name='id'/></Key></Attribute>\n"
        + "      <Attribute name='Size Tier' hasHierarchy='true'>\n"
        + "        <Tier column='units'>\n"
        + "          <Bin boundary='10' label='Small'/>\n"
        + "          <Bin boundary='100' label='Medium'/>\n"
        + "          <Bin label='Large'/>\n"
        + "        </Tier>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Lead' table='td_dim' key='Id2'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Id2' hasHierarchy='false'>"
        + "<Key><Column name='id'/></Key></Attribute>\n"
        + "      <Attribute name='Lead Days' hasHierarchy='true'>\n"
        + "        <Duration startColumn='order_date'"
        + " endColumn='ship_date' unit='DAY'/>\n"
        + "      </Attribute>\n"
        + "      <Attribute name='Lead Months' hasHierarchy='true'>\n"
        + "        <Duration startColumn='order_date'"
        + " endColumn='ship_date' unit='MONTH'/>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions>\n"
        + "      <Dimension source='Size'/>\n"
        + "      <Dimension source='Lead'/>\n"
        + "    </Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='F' table='td_fact'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "          <Measure name='Row Count' aggregator='count'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Size'"
        + " foreignKeyColumn='dim_id'/>\n"
        + "          <ForeignKeyLink dimension='Lead'"
        + " foreignKeyColumn='dim_id'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    private static final String H2_URL =
        "jdbc:h2:mem:td_e2e;DB_CLOSE_DELAY=-1";

    private static final Map<String, Connection> CONNS =
        new LinkedHashMap<>();

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Class.forName("org.h2.Driver");
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            for (String sql : DDL) {
                st.execute(sql);
            }
        }
        CONNS.put("xml", connect(SCHEMA));
        CONNS.put("yaml",
            connect(M4YamlToXml.toXml(M4XmlToYaml.toYaml(SCHEMA))));
    }

    private static Connection connect(String catalog) {
        Util.PropertyList props = new Util.PropertyList();
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

    @AfterAll
    public static void close() {
        for (Connection c : CONNS.values()) {
            if (c != null) {
                c.close();
            }
        }
        CONNS.clear();
    }

    /** Ordered list of "memberName -> cellValue" for a ROWS-only query
     *  (one measure on COLUMNS). Order is the axis order Mondrian
     *  returns, so it pins member ordering. */
    private List<Map.Entry<String, Double>> rows(String form, String mdx) {
        Connection conn = CONNS.get(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        Axis rowsAxis = r.getAxes()[1];
        int i = 0;
        for (Position rp : rowsAxis.getPositions()) {
            Object v = r.getCell(new int[]{0, i}).getValue();
            out.add(new java.util.AbstractMap.SimpleEntry<>(
                rp.get(0).getName(),
                v == null ? null : ((Number) v).doubleValue()));
            i++;
        }
        r.close();
        return out;
    }

    private static Map<String, Double> toMap(
        List<Map.Entry<String, Double>> rows)
    {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : rows) {
            m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    /** Tier bins land correctly — including the boundary rows
     *  (units = 10 → Medium, units = 100 → Large). */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void tierBinsIncludingBoundaries(String form) {
        Map<String, Double> g = toMap(rows(form,
            "SELECT {[Measures].[Row Count]} ON COLUMNS,\n"
            + " [Size].[Size Tier].Members ON ROWS\n"
            + "FROM [Sales]"));
        // Small: only units=5 (1 row). Medium: units=10,50 (2 rows).
        // Large: units=100,250 (2 rows).
        assertEquals(1.0, g.get("Small"), 0.001, "Small bin (units<10)");
        assertEquals(2.0, g.get("Medium"), 0.001,
            "Medium bin (10<=units<100; boundary 10 lands here)");
        assertEquals(2.0, g.get("Large"), 0.001,
            "Large bin (units>=100; boundary 100 lands here)");
    }

    /** Tier MEMBER ORDER must be boundary order (Small, Medium, Large),
     *  not lexical (which would give Large, Medium, Small). */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void tierMemberOrderIsBoundaryOrder(String form) {
        List<Map.Entry<String, Double>> ordered = rows(form,
            "SELECT {[Measures].[Row Count]} ON COLUMNS,\n"
            + " [Size].[Size Tier].Members ON ROWS\n"
            + "FROM [Sales]");
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Double> e : ordered) {
            // Skip the hierarchy's All member.
            if (e.getKey().startsWith("All ")) {
                continue;
            }
            names.add(e.getKey());
        }
        assertEquals(List.of("Small", "Medium", "Large"), names,
            "tier members must sort by boundary, not lexically");
    }

    /** Duration (DAY) produces the expected per-row integer interval and
     *  the rows roll up correctly. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void durationDaysPerRowAndRollup(String form) {
        Map<String, Double> g = toMap(rows(form,
            "SELECT {[Measures].[Row Count]} ON COLUMNS,\n"
            + " [Lead].[Lead Days].Members ON ROWS\n"
            + "FROM [Sales]"));
        // ship-order day diffs: 3, 10, 40, 70, 100 — all distinct → 1 each.
        assertEquals(1.0, g.get("3"), 0.001, "3-day lead");
        assertEquals(1.0, g.get("10"), 0.001, "10-day lead");
        assertEquals(1.0, g.get("40"), 0.001, "40-day lead");
        assertEquals(1.0, g.get("70"), 0.001, "70-day lead");
        assertEquals(1.0, g.get("100"), 0.001, "100-day lead");
    }

    /** Duration (MONTH) — a second unit, proving the unit is honoured. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void durationMonths(String form) {
        Map<String, Double> g = toMap(rows(form,
            "SELECT {[Measures].[Row Count]} ON COLUMNS,\n"
            + " [Lead].[Lead Months].Members ON ROWS\n"
            + "FROM [Sales]"));
        // months between order(Jan 1) and ship:
        //   Jan 4 → 0, Jan 11 → 0, Feb 10 → 1, Mar 11 → 2, Apr 10 → 3
        // → month 0 has 2 rows, months 1,2,3 have 1 each.
        assertEquals(2.0, g.get("0"), 0.001, "0-month lead (2 rows)");
        assertEquals(1.0, g.get("1"), 0.001, "1-month lead");
        assertEquals(1.0, g.get("2"), 0.001, "2-month lead");
        assertEquals(1.0, g.get("3"), 0.001, "3-month lead");
    }

    // ---- edge cases from the #108 audit (run on a fresh schema) --------

    /**
     * #108 edge: a NULL tier source column lands in the open-ended top bin
     * (the unbounded {@code <Bin label='Large'/>} else-branch), and a value
     * far above the top boundary also lands there — the CASE has no boundary
     * to exclude it. Uses an isolated H2 DB + schema so it does not perturb
     * the shared fixture's exact bin counts.
     */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void tierNullSourceAndOpenEndedTopBin(String form) throws Exception {
        String url = "jdbc:h2:mem:td_tier_edge;DB_CLOSE_DELAY=-1";
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement())
        {
            st.execute("DROP TABLE IF EXISTS \"te_fact\"");
            st.execute("DROP TABLE IF EXISTS \"te_dim\"");
            st.execute("CREATE TABLE \"te_dim\" (\"id\" INTEGER,"
                + " \"units\" INTEGER)");
            st.execute("CREATE TABLE \"te_fact\" (\"dim_id\" INTEGER,"
                + " \"amount\" INTEGER)");
            st.execute("INSERT INTO \"te_dim\" VALUES"
                + " (1, NULL), (2, 5), (3, 9999)");
            st.execute("INSERT INTO \"te_fact\" VALUES (1,100),(2,100),(3,100)");
        }
        String s =
            "<Schema name='TE' metamodelVersion='4.0'>\n"
            + "  <PhysicalSchema>\n"
            + "    <Table name='te_fact'/>\n"
            + "    <Table name='te_dim'/>\n"
            + "  </PhysicalSchema>\n"
            + "  <Dimension name='Size' table='te_dim' key='Id'>\n"
            + "    <Attributes>\n"
            + "      <Attribute name='Id' hasHierarchy='false'>"
            + "<Key><Column name='id'/></Key></Attribute>\n"
            + "      <Attribute name='Size Tier' hasHierarchy='true'>\n"
            + "        <Tier column='units'>\n"
            + "          <Bin boundary='10' label='Small'/>\n"
            + "          <Bin boundary='100' label='Medium'/>\n"
            + "          <Bin label='Large'/>\n"
            + "        </Tier>\n"
            + "      </Attribute>\n"
            + "    </Attributes>\n"
            + "  </Dimension>\n"
            + "  <Cube name='Sales'>\n"
            + "    <Dimensions><Dimension source='Size'/></Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='F' table='te_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Row Count' aggregator='count'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Size'"
            + " foreignKeyColumn='dim_id'/>\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n"
            + "</Schema>\n";
        Connection conn = connectUrl(url, form.equals("yaml")
            ? M4YamlToXml.toXml(M4XmlToYaml.toYaml(s)) : s);
        try {
            Map<String, Double> g = toMap(rowsOn(conn,
                "SELECT {[Measures].[Row Count]} ON COLUMNS,\n"
                + " [Size].[Size Tier].Members ON ROWS\n"
                + "FROM [Sales]"));
            assertEquals(1.0, g.get("Small"), 0.001, "units=5 → Small");
            // NULL (no boundary matches) + 9999 (above top boundary) → Large.
            assertEquals(2.0, g.get("Large"), 0.001,
                "NULL source and over-top value both fall to the open top bin");
        } finally {
            conn.close();
        }
    }

    /**
     * #108 edge: a NEGATIVE interval (ship before order) yields a negative
     * duration rather than an error, and a NULL date yields a NULL duration
     * member (not a silent zero). A sub-day HOUR unit is also exercised (H2
     * supports DATEDIFF HOUR), proving sub-day granularity.
     */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void durationNegativeIntervalAndNullDate(String form)
        throws Exception
    {
        String url = "jdbc:h2:mem:td_dur_edge;DB_CLOSE_DELAY=-1";
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement())
        {
            st.execute("DROP TABLE IF EXISTS \"de_fact\"");
            st.execute("DROP TABLE IF EXISTS \"de_dim\"");
            st.execute("CREATE TABLE \"de_dim\" (\"id\" INTEGER,"
                + " \"order_date\" DATE, \"ship_date\" DATE)");
            st.execute("CREATE TABLE \"de_fact\" (\"dim_id\" INTEGER,"
                + " \"amount\" INTEGER)");
            // row 1: ship BEFORE order → −2 days; row 2: NULL ship → NULL;
            // row 3: normal +3 days.
            st.execute("INSERT INTO \"de_dim\" VALUES"
                + " (1, DATE '2024-01-03', DATE '2024-01-01'),"
                + " (2, DATE '2024-01-01', NULL),"
                + " (3, DATE '2024-01-01', DATE '2024-01-04')");
            st.execute("INSERT INTO \"de_fact\" VALUES (1,100),(2,100),(3,100)");
        }
        String s =
            "<Schema name='DE' metamodelVersion='4.0'>\n"
            + "  <PhysicalSchema>\n"
            + "    <Table name='de_fact'/>\n"
            + "    <Table name='de_dim'/>\n"
            + "  </PhysicalSchema>\n"
            + "  <Dimension name='Lead' table='de_dim' key='Id'>\n"
            + "    <Attributes>\n"
            + "      <Attribute name='Id' hasHierarchy='false'>"
            + "<Key><Column name='id'/></Key></Attribute>\n"
            + "      <Attribute name='Lead Days' hasHierarchy='true'>\n"
            + "        <Duration startColumn='order_date'"
            + " endColumn='ship_date' unit='DAY'/>\n"
            + "      </Attribute>\n"
            + "    </Attributes>\n"
            + "  </Dimension>\n"
            + "  <Cube name='Sales'>\n"
            + "    <Dimensions><Dimension source='Lead'/></Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='F' table='de_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Row Count' aggregator='count'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Lead'"
            + " foreignKeyColumn='dim_id'/>\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n"
            + "</Schema>\n";
        Connection conn = connectUrl(url, form.equals("yaml")
            ? M4YamlToXml.toXml(M4XmlToYaml.toYaml(s)) : s);
        try {
            Map<String, Double> g = toMap(rowsOn(conn,
                "SELECT {[Measures].[Row Count]} ON COLUMNS,\n"
                + " [Lead].[Lead Days].Members ON ROWS\n"
                + "FROM [Sales]"));
            assertEquals(1.0, g.get("-2"), 0.001,
                "ship before order → negative duration, not an error");
            assertEquals(1.0, g.get("3"), 0.001, "normal +3 day interval");
            // The NULL-ship row appears as a NULL-keyed member (caption
            // varies); assert it did not silently collapse into 0.
            org.junit.jupiter.api.Assertions.assertEquals(
                null, g.get("0"),
                "NULL end date must not be miscounted as a 0-day duration");
        } finally {
            conn.close();
        }
    }

    /** Sub-day HOUR duration unit (H2 DATEDIFF supports HOUR). */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void durationSubDayHourUnit(String form) throws Exception {
        String url = "jdbc:h2:mem:td_hour_edge;DB_CLOSE_DELAY=-1";
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement())
        {
            st.execute("DROP TABLE IF EXISTS \"he_fact\"");
            st.execute("DROP TABLE IF EXISTS \"he_dim\"");
            st.execute("CREATE TABLE \"he_dim\" (\"id\" INTEGER,"
                + " \"units\" INTEGER, \"order_ts\" TIMESTAMP,"
                + " \"ship_ts\" TIMESTAMP)");
            st.execute("CREATE TABLE \"he_fact\" (\"dim_id\" INTEGER,"
                + " \"amount\" INTEGER)");
            st.execute("INSERT INTO \"he_dim\" VALUES"
                + " (1, 5, TIMESTAMP '2024-01-01 00:00:00',"
                + " TIMESTAMP '2024-01-01 05:00:00'),"
                + " (2, 5, TIMESTAMP '2024-01-01 00:00:00',"
                + " TIMESTAMP '2024-01-01 12:00:00')");
            st.execute("INSERT INTO \"he_fact\" VALUES (1,100),(2,100)");
        }
        String hourSchema =
            "<Schema name='TDH' metamodelVersion='4.0'>\n"
            + "  <PhysicalSchema>\n"
            + "    <Table name='he_fact'/>\n"
            + "    <Table name='he_dim'/>\n"
            + "  </PhysicalSchema>\n"
            + "  <Dimension name='Lead' table='he_dim' key='Id'>\n"
            + "    <Attributes>\n"
            + "      <Attribute name='Id' hasHierarchy='false'>"
            + "<Key><Column name='id'/></Key></Attribute>\n"
            + "      <Attribute name='Lead Hours' hasHierarchy='true'>\n"
            + "        <Duration startColumn='order_ts'"
            + " endColumn='ship_ts' unit='HOUR'/>\n"
            + "      </Attribute>\n"
            + "    </Attributes>\n"
            + "  </Dimension>\n"
            + "  <Cube name='Sales'>\n"
            + "    <Dimensions><Dimension source='Lead'/></Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='F' table='he_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Row Count' aggregator='count'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Lead'"
            + " foreignKeyColumn='dim_id'/>\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n"
            + "</Schema>\n";
        String catalog = form.equals("yaml")
            ? M4YamlToXml.toXml(M4XmlToYaml.toYaml(hourSchema))
            : hourSchema;
        Connection conn = connectUrl(url, catalog);
        try {
            Map<String, Double> g = toMap(rowsOn(conn,
                "SELECT {[Measures].[Row Count]} ON COLUMNS,\n"
                + " [Lead].[Lead Hours].Members ON ROWS\n"
                + "FROM [Sales]"));
            assertEquals(1.0, g.get("5"), 0.001, "5-hour lead");
            assertEquals(1.0, g.get("12"), 0.001, "12-hour lead (sub-day unit)");
        } finally {
            conn.close();
        }
    }

    // ---- edge-test helpers (isolated H2 DBs + schemas) -----------------

    private static Connection connectUrl(String url, String catalog) {
        Util.PropertyList props = new Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), url);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        return DriverManager.getConnection(props, null, null);
    }

    /** rows() against an explicit connection. */
    private List<Map.Entry<String, Double>> rowsOn(Connection conn, String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        Axis rowsAxis = r.getAxes()[1];
        int i = 0;
        for (Position rp : rowsAxis.getPositions()) {
            Object v = r.getCell(new int[]{0, i}).getValue();
            out.add(new java.util.AbstractMap.SimpleEntry<>(
                rp.get(0).getName(),
                v == null ? null : ((Number) v).doubleValue()));
            i++;
        }
        r.close();
        return out;
    }
}
