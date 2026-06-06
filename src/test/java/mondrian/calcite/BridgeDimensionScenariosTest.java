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
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #107: a broad scenario sweep for bridge (many-to-many) dimensions,
 * exercising the feature "in a range of different ways" beyond the canonical
 * acceptance test in {@link BridgeDimensionTest}.
 *
 * <p>The fixture extends the bank example with a normal foreign-key
 * dimension (Region), a second measure (Fees), and a customer with no
 * accounts (Dave) so we can cover:
 * <ul>
 *   <li>full-count and weighted allocation, by leaf and at the All level;</li>
 *   <li>a bridge dimension crossed with a normal FK dimension;</li>
 *   <li>a bridge member in the slicer (WHERE);</li>
 *   <li>a normal dimension on the rows while a bridge member slices;</li>
 *   <li>multiple measures over one bridge in a single query;</li>
 *   <li>single-owner vs multi-owner accounts;</li>
 *   <li>explicit member sets vs .Members;</li>
 *   <li>NON EMPTY suppression of an unowned customer;</li>
 *   <li>the legacy backend rejecting a bridge query loudly.</li>
 * </ul>
 *
 * <pre>
 *   account_fact                       account_owner (bridge, weighted)
 *   acct year  region balance fees     acct customer weight
 *    1   2024  North   1000    10        1   Alice    0.50
 *    2   2024  South    500     5        1   Bob      0.50
 *    3   2025  North    300     3        2   Bob      1.00
 *                                        3   Alice    0.25
 *   totals: balance 1800, fees 18       3   Carol    0.75
 *   dim_customer also has Dave (no ownership rows)
 * </pre>
 */
public class BridgeDimensionScenariosTest {

    private static final String[] DDL = {
        "DROP TABLE \"sc_fact\" IF EXISTS",
        "DROP TABLE \"sc_owner\" IF EXISTS",
        "DROP TABLE \"sc_customer\" IF EXISTS",
        "DROP TABLE \"sc_date\" IF EXISTS",
        "DROP TABLE \"sc_region\" IF EXISTS",
        "CREATE TABLE \"sc_fact\" (\"account_id\" INTEGER,"
            + " \"date_key\" INTEGER, \"region_id\" VARCHAR(16),"
            + " \"balance\" INTEGER, \"fees\" INTEGER)",
        "CREATE TABLE \"sc_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"sc_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32))",
        "CREATE TABLE \"sc_date\" (\"date_key\" INTEGER, \"yr\" INTEGER)",
        "CREATE TABLE \"sc_region\" (\"region_id\" VARCHAR(16),"
            + " \"region_name\" VARCHAR(32))",
        "INSERT INTO \"sc_fact\" VALUES (1, 2024, 'North', 1000, 10)",
        "INSERT INTO \"sc_fact\" VALUES (2, 2024, 'South', 500, 5)",
        "INSERT INTO \"sc_fact\" VALUES (3, 2025, 'North', 300, 3)",
        "INSERT INTO \"sc_owner\" VALUES (1, 'Alice', 0.5)",
        "INSERT INTO \"sc_owner\" VALUES (1, 'Bob', 0.5)",
        "INSERT INTO \"sc_owner\" VALUES (2, 'Bob', 1.0)",
        "INSERT INTO \"sc_owner\" VALUES (3, 'Alice', 0.25)",
        "INSERT INTO \"sc_owner\" VALUES (3, 'Carol', 0.75)",
        "INSERT INTO \"sc_customer\" VALUES ('Alice', 'Alice')",
        "INSERT INTO \"sc_customer\" VALUES ('Bob', 'Bob')",
        "INSERT INTO \"sc_customer\" VALUES ('Carol', 'Carol')",
        "INSERT INTO \"sc_customer\" VALUES ('Dave', 'Dave')",
        "INSERT INTO \"sc_date\" VALUES (2024, 2024)",
        "INSERT INTO \"sc_date\" VALUES (2025, 2025)",
        "INSERT INTO \"sc_region\" VALUES ('North', 'North')",
        "INSERT INTO \"sc_region\" VALUES ('South', 'South')",
    };

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "      <Dimension source='Date'/>\n"
            + "      <Dimension source='Region'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='sc_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "          <Measure name='Fees' column='fees'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Date'"
            + " foreignKeyColumn='date_key'/>\n"
            + "          <ForeignKeyLink dimension='Region'"
            + " foreignKeyColumn='region_id'/>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='sc_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'"
            + bridgeAggAttrs + "/>\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n";
    }

    private static final String SCHEMA =
        "<Schema name='Bank' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='sc_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='sc_owner'/>\n"
        + "    <Table name='sc_customer'/>\n"
        + "    <Table name='sc_date'/>\n"
        + "    <Table name='sc_region'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Customer' table='sc_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Customer'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Date' table='sc_date' key='Date Id'"
        + " type='TIME'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Date Id' hasHierarchy='false'>"
        + "<Key><Column name='date_key'/></Key></Attribute>\n"
        + "      <Attribute name='Year' levelType='TimeYears'"
        + " hasHierarchy='false'><Key><Column name='yr'/></Key></Attribute>\n"
        + "    </Attributes>\n"
        + "    <Hierarchies>\n"
        + "      <Hierarchy name='Calendar' allMemberName='All Years'>\n"
        + "        <Level attribute='Year'/>\n"
        + "      </Hierarchy>\n"
        + "    </Hierarchies>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Region' table='sc_region' key='Region'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Region'>\n"
        + "        <Key><Column name='region_id'/></Key>\n"
        + "        <Name><Column name='region_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + cube("AccountsFull", "")
        + cube("AccountsWeighted",
               " aggregation='weighted' weightColumn='weight'")
        + "</Schema>\n";

    private static Connection conn;

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
        Util.PropertyList base =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(
                     base.get("Jdbc"), base.get("JdbcUser"),
                     base.get("JdbcPassword"));
             Statement st = c.createStatement())
        {
            for (String sql : DDL) {
                st.execute(sql);
            }
        }
        // Re-reflect the JDBC catalog so the planner sees the fixture tables
        // (the cache is process-wide and a prior class may have warmed it).
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), SCHEMA);
        props.remove(RolapConnectionProperties.Catalog.name());
        conn = DriverManager.getConnection(props, null, null);
    }

    @AfterAll
    public static void close() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }

    // ---- helpers ------------------------------------------------------

    /** Row caption → value for the single measure on COLUMNS (axis 1). */
    private Map<String, Double> rowMap(String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Map<String, Double> out = new LinkedHashMap<>();
        Axis rows = r.getAxes()[1];
        int i = 0;
        for (Position pos : rows.getPositions()) {
            Object v = r.getCell(new int[]{0, i}).getValue();
            out.put(
                pos.get(0).getName(),
                v == null ? null : ((Number) v).doubleValue());
            i++;
        }
        r.close();
        return out;
    }

    /** Single-cell scalar from a COLUMNS-only query. */
    private Double scalar(String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    /** "rowCaption|colCaption" → value for a 2-axis grid. */
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

    // ---- full-count: leaf + All ---------------------------------------

    @Test
    public void fullCountBalanceByCustomer() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1300.0, m.get("Alice"), 0.001);
        assertEquals(1500.0, m.get("Bob"), 0.001);
        assertEquals(300.0, m.get("Carol"), 0.001);
    }

    @Test
    public void fullCountFeesByCustomer() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Fees]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(13.0, m.get("Alice"), 0.001);
        assertEquals(15.0, m.get("Bob"), 0.001);
        assertEquals(3.0, m.get("Carol"), 0.001);
    }

    @Test
    public void fullCountAllDedupesToFactTotals() {
        assertEquals(1800.0, scalar(
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsFull]"),
            0.001);
        assertEquals(18.0, scalar(
            "SELECT {[Measures].[Fees]} ON COLUMNS FROM [AccountsFull]"),
            0.001);
    }

    // ---- weighted: leaf + All -----------------------------------------

    @Test
    public void weightedBalanceByCustomer() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(575.0, m.get("Alice"), 0.001);
        assertEquals(1000.0, m.get("Bob"), 0.001);
        assertEquals(225.0, m.get("Carol"), 0.001);
    }

    @Test
    public void weightedFeesByCustomer() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Fees]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(5.75, m.get("Alice"), 0.001);
        assertEquals(10.0, m.get("Bob"), 0.001);
        assertEquals(2.25, m.get("Carol"), 0.001);
    }

    @Test
    public void weightedAllReconcilesToFactTotal() {
        // Weights sum to 1 per account, so the All level equals the fact
        // total regardless of allocation.
        assertEquals(1800.0, scalar(
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsWeighted]"),
            0.001);
    }

    // ---- bridge × normal FK dimension ---------------------------------

    @Test
    public void fullCountBridgeCrossRegion() {
        Map<String, Double> g = grid(
            "SELECT NON EMPTY [Region].[Region].Members ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Measures].[Balance]");
        assertEquals(1300.0, g.get("Alice|North"), 0.001);
        assertEquals(1000.0, g.get("Bob|North"), 0.001);
        assertEquals(500.0, g.get("Bob|South"), 0.001);
        assertEquals(300.0, g.get("Carol|North"), 0.001);
        // Alice has no South account.
        assertNull(g.get("Alice|South"));
    }

    @Test
    public void weightedBridgeCrossRegion() {
        Map<String, Double> g = grid(
            "SELECT NON EMPTY [Region].[Region].Members ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]\n"
            + "WHERE [Measures].[Balance]");
        assertEquals(575.0, g.get("Alice|North"), 0.001);
        assertEquals(500.0, g.get("Bob|North"), 0.001);
        assertEquals(500.0, g.get("Bob|South"), 0.001);
        assertEquals(225.0, g.get("Carol|North"), 0.001);
    }

    // ---- bridge member in the slicer ----------------------------------

    @Test
    public void fullCountBridgeMemberSlicer() {
        assertEquals(1300.0, scalar(
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Alice]"), 0.001);
        assertEquals(1500.0, scalar(
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Bob]"), 0.001);
    }

    @Test
    public void weightedBridgeMemberSlicer() {
        assertEquals(575.0, scalar(
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsWeighted]\n"
            + "WHERE [Customer].[Customer].[Alice]"), 0.001);
    }

    /** Normal dimension on rows, bridge member slicing. */
    @Test
    public void regionRowsBridgeSlicerFullCount() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Region].[Region].Members ON ROWS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Bob]");
        assertEquals(1000.0, m.get("North"), 0.001);
        assertEquals(500.0, m.get("South"), 0.001);
    }

    // ---- multiple measures together over a bridge ---------------------

    @Test
    public void multipleMeasuresOverBridge() {
        Map<String, Double> g = grid(
            "SELECT {[Measures].[Balance], [Measures].[Fees]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(575.0, g.get("Alice|Balance"), 0.001);
        assertEquals(5.75, g.get("Alice|Fees"), 0.001);
        assertEquals(1000.0, g.get("Bob|Balance"), 0.001);
        assertEquals(10.0, g.get("Bob|Fees"), 0.001);
    }

    // ---- single-owner vs multi-owner; explicit member sets ------------

    @Test
    public void singleOwnerAccountExplicitMember() {
        // Carol owns only a fraction of one account.
        assertEquals(300.0, scalar(
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Carol]"), 0.001);
        assertEquals(225.0, scalar(
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsWeighted]\n"
            + "WHERE [Customer].[Customer].[Carol]"), 0.001);
    }

    @Test
    public void explicitMemberSetRows() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " {[Customer].[Customer].[Bob],"
            + " [Customer].[Customer].[Carol]} ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1500.0, m.get("Bob"), 0.001);
        assertEquals(300.0, m.get("Carol"), 0.001);
    }

    // ---- NON EMPTY suppression of an unowned customer -----------------

    @Test
    public void daveUnownedIsNullThenSuppressed() {
        // Without NON EMPTY, Dave appears with a null cell.
        Map<String, Double> all = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertTrue(all.containsKey("Dave"), "Dave present without NON EMPTY");
        assertNull(all.get("Dave"), "Dave has no accounts → null");
        // With NON EMPTY, Dave is suppressed.
        Map<String, Double> ne = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertTrue(!ne.containsKey("Dave"), "Dave suppressed by NON EMPTY");
    }

    // ---- bridge × bridge-independent time dimension -------------------

    @Test
    public void fullCountBridgeCrossYear() {
        Map<String, Double> g = grid(
            "SELECT [Date].[Calendar].[Year].Members ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Measures].[Balance]");
        // acct1 (2024) Alice/Bob, acct2 (2024) Bob, acct3 (2025) Alice/Carol
        assertEquals(1000.0, g.get("Alice|2024"), 0.001);
        assertEquals(300.0, g.get("Alice|2025"), 0.001);
        assertEquals(1500.0, g.get("Bob|2024"), 0.001);
        assertEquals(300.0, g.get("Carol|2025"), 0.001);
    }

    // ---- legacy backend must reject a bridge query loudly -------------

    @Test
    public void legacyBackendRejectsBridge() {
        // A bridge tuple read has no legacy SQL path, so the legacy backend
        // must fail loudly rather than silently mis-count. Use a FRESH
        // connection so the customer members are not already cached from the
        // Calcite tests above (a warm member cache would skip the guard).
        String prior = System.getProperty("mondrian.backend");
        System.setProperty("mondrian.backend", "legacy");
        Connection legacy = null;
        try {
            Util.PropertyList props =
                Util.parseConnectString(TestContext.getDefaultConnectString());
            props.put("UseSchemaPool", "false");
            props.put(RolapConnectionProperties.CatalogContent.name(), SCHEMA);
            props.remove(RolapConnectionProperties.Catalog.name());
            legacy = DriverManager.getConnection(props, null, null);
            Query q = legacy.parseQuery(
                "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
                + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
                + "FROM [AccountsFull]");
            legacy.execute(q);
            fail("legacy backend should not silently serve a bridge read");
        } catch (RuntimeException expected) {
            String msg = String.valueOf(expected.getMessage())
                + " " + String.valueOf(
                    expected.getCause() == null
                        ? "" : expected.getCause().getMessage());
            assertTrue(
                msg.toLowerCase().contains("calcite")
                    || msg.toLowerCase().contains("bridge"),
                "expected a clear bridge/Calcite failure, got: " + msg);
        } finally {
            if (legacy != null) {
                legacy.close();
            }
            if (prior == null) {
                System.clearProperty("mondrian.backend");
            } else {
                System.setProperty("mondrian.backend", prior);
            }
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
