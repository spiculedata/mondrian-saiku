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

/**
 * Issue #103 / #107: multi-level bridge roll-up. A bridge dimension with a
 * level ABOVE its leaf (Customer rolled up into Segment) is the case the
 * "load the All level straight from the fact" structural trick cannot
 * handle — once you group by an intermediate bridge level, the SAME fact row
 * can appear more than once WITHIN a single group and a naive SUM
 * double-counts. The fix is the #103 symmetric aggregate (de-duplicate on
 * the fact grain before summing).
 *
 * <pre>
 *   accounts          ownership (bridge)     customer → segment
 *   acct balance      acct cust  weight       alice → Premium
 *    1    1000         1   alice 0.50          bob   → Premium
 *    2     500         1   bob   0.50          carol → Standard
 *    3     300         2   bob   1.00          dave  → Standard (no accts)
 *                      3   alice 0.25
 *   total 1800         3   carol 0.75
 * </pre>
 *
 * <p>Golden values:
 * <ul>
 *   <li><b>fullCount by Segment</b> — de-duplicated per account WITHIN each
 *       segment: Premium owns {acct1, acct2, acct3} = 1800; Standard owns
 *       {acct3} = 300. (acct3 is in both segments — intended fullCount
 *       overlap ACROSS segments — but acct1 is NOT double-counted within
 *       Premium even though both its owners are Premium. Naive = 2800.)</li>
 *   <li><b>fullCount leaf (Customer)</b> unchanged: Alice 1300, Bob 1500,
 *       Carol 300.</li>
 *   <li><b>weighted by Segment</b>: Premium 1575, Standard 225 (no dedup
 *       needed — weighted shares roll up additively).</li>
 * </ul>
 */
public class BridgeMultiLevelTest {

    private static final String[] DDL = {
        "DROP TABLE \"mlb_fact\" IF EXISTS",
        "DROP TABLE \"mlb_owner\" IF EXISTS",
        "DROP TABLE \"mlb_customer\" IF EXISTS",
        "CREATE TABLE \"mlb_fact\" (\"account_id\" INTEGER,"
            + " \"balance\" INTEGER)",
        "CREATE TABLE \"mlb_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"mlb_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32), \"segment\" VARCHAR(16))",
        "INSERT INTO \"mlb_fact\" VALUES (1, 1000)",
        "INSERT INTO \"mlb_fact\" VALUES (2, 500)",
        "INSERT INTO \"mlb_fact\" VALUES (3, 300)",
        "INSERT INTO \"mlb_owner\" VALUES (1, 'alice', 0.50)",
        "INSERT INTO \"mlb_owner\" VALUES (1, 'bob',   0.50)",
        "INSERT INTO \"mlb_owner\" VALUES (2, 'bob',   1.00)",
        "INSERT INTO \"mlb_owner\" VALUES (3, 'alice', 0.25)",
        "INSERT INTO \"mlb_owner\" VALUES (3, 'carol', 0.75)",
        "INSERT INTO \"mlb_customer\" VALUES ('alice', 'Alice', 'Premium')",
        "INSERT INTO \"mlb_customer\" VALUES ('bob',   'Bob',   'Premium')",
        "INSERT INTO \"mlb_customer\" VALUES ('carol', 'Carol', 'Standard')",
        "INSERT INTO \"mlb_customer\" VALUES ('dave',  'Dave',  'Standard')",
    };

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='mlb_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='mlb_owner'"
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
        + "    <Table name='mlb_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='mlb_owner'/>\n"
        + "    <Table name='mlb_customer'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Customer' table='mlb_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Segment'>"
        + "<Key><Column name='segment'/></Key></Attribute>\n"
        + "      <Attribute name='Customer'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "    <Hierarchies>\n"
        + "      <Hierarchy name='By Segment' allMemberName='All Customers'>\n"
        + "        <Level attribute='Segment'/>\n"
        + "        <Level attribute='Customer'/>\n"
        + "      </Hierarchy>\n"
        + "    </Hierarchies>\n"
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

    /** fullCount rolled up to Segment — the #103 symmetric aggregate. */
    @Test
    public void fullCountBySegment() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[By Segment].[Segment].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1800.0, m.get("Premium"), 0.001,
            "Premium de-duplicates acct1 (both owners Premium)");
        assertEquals(300.0, m.get("Standard"), 0.001);
    }

    /** Leaf level still correct (no rollup, naturally distinct). */
    @Test
    public void fullCountByCustomerLeaf() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[By Segment].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1300.0, m.get("Alice"), 0.001);
        assertEquals(1500.0, m.get("Bob"), 0.001);
        assertEquals(300.0, m.get("Carol"), 0.001);
    }

    /** weighted rolls up additively — no dedup needed, but must stay right. */
    @Test
    public void weightedBySegment() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[By Segment].[Segment].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(1575.0, m.get("Premium"), 0.001);
        assertEquals(225.0, m.get("Standard"), 0.001);
    }
}
