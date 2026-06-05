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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #107 / #103: a populated bridge (many-to-many) test fixture — the
 * canonical bank example — with hand-computed golden values.
 *
 * <p>Dataset:
 * <pre>
 *   ACCOUNTS (fact)          OWNERSHIP (bridge, with weights)
 *   acct  year  balance      acct  customer  weight
 *    1    2024   1000          1    Alice      0.50
 *    2    2024    500          1    Bob        0.50
 *    3    2025    300          2    Bob        1.00
 *                              3    Alice      0.25
 *   total balance = 1800      3    Carol      0.75
 * </pre>
 *
 * <p>Golden values:
 * <ul>
 *   <li>Balance by Year (plain ForeignKeyLink, works today):
 *       2024 → 1500, 2025 → 300.</li>
 *   <li>Balance grand total = 1800.</li>
 *   <li><b>fullCount</b> Balance by Customer: Alice 1300, Bob 1500,
 *       Carol 300. Grand total over the bridge dedupes to 1800 — NOT the
 *       naive 3100 (acct1×2 + acct2 + acct3×2) — this is the #103
 *       symmetric aggregate.</li>
 *   <li><b>weighted</b> Balance by Customer: Alice 575, Bob 1000,
 *       Carol 225 — reconciles to 1800.</li>
 * </ul>
 *
 * <p>The non-bridge tests are enabled now (they validate the fixture and the
 * normal FK path). The bridge-query tests are {@link Disabled} until the
 * Calcite bridge read/aggregate (Phases 3–4) lands; they are the acceptance
 * criteria for those phases.
 */
public class BridgeDimensionTest {

    private static final String[] DDL = {
        "DROP TABLE \"account_fact\" IF EXISTS",
        "DROP TABLE \"account_owner\" IF EXISTS",
        "DROP TABLE \"dim_customer\" IF EXISTS",
        "DROP TABLE \"dim_date\" IF EXISTS",
        "CREATE TABLE \"account_fact\" (\"account_id\" INTEGER,"
            + " \"date_key\" INTEGER, \"balance\" INTEGER)",
        "CREATE TABLE \"account_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"dim_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32))",
        "CREATE TABLE \"dim_date\" (\"date_key\" INTEGER, \"yr\" INTEGER)",
        "INSERT INTO \"account_fact\" VALUES (1, 2024, 1000)",
        "INSERT INTO \"account_fact\" VALUES (2, 2024, 500)",
        "INSERT INTO \"account_fact\" VALUES (3, 2025, 300)",
        "INSERT INTO \"account_owner\" VALUES (1, 'Alice', 0.5)",
        "INSERT INTO \"account_owner\" VALUES (1, 'Bob', 0.5)",
        "INSERT INTO \"account_owner\" VALUES (2, 'Bob', 1.0)",
        "INSERT INTO \"account_owner\" VALUES (3, 'Alice', 0.25)",
        "INSERT INTO \"account_owner\" VALUES (3, 'Carol', 0.75)",
        "INSERT INTO \"dim_customer\" VALUES ('Alice', 'Alice')",
        "INSERT INTO \"dim_customer\" VALUES ('Bob', 'Bob')",
        "INSERT INTO \"dim_customer\" VALUES ('Carol', 'Carol')",
        "INSERT INTO \"dim_date\" VALUES (2024, 2024)",
        "INSERT INTO \"dim_date\" VALUES (2025, 2025)",
    };

    private static final String SCHEMA =
        "<Schema name='Bank' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='account_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='account_owner'/>\n"
        + "    <Table name='dim_customer'/>\n"
        + "    <Table name='dim_date'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Customer' table='dim_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Customer'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Date' table='dim_date' key='Date Id'"
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
        + cube("AccountsFull", "")
        + cube("AccountsWeighted",
               " aggregation='weighted' weightColumn='weight'")
        + "</Schema>\n";

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "      <Dimension source='Date'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='account_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Date'"
            + " foreignKeyColumn='date_key'/>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='account_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'"
            + bridgeAggAttrs + "/>\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n";
    }

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

    /** Run an MDX query and return rowMemberCaption → cell value (axis 1). */
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

    private double grandTotal(String cube) {
        Query q = conn.parseQuery(
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [" + cube + "]");
        Result r = conn.execute(q);
        double v = ((Number) r.getCell(new int[]{0}).getValue()).doubleValue();
        r.close();
        return v;
    }

    // ---- enabled now: fixture + non-bridge (ForeignKeyLink) paths -----

    /** Grand total Balance — no dimension, no bridge. Works today. */
    @Test
    public void grandTotalBalance() {
        assertEquals(1800.0, grandTotal("AccountsFull"), 0.001);
    }

    /** Balance by Year — a plain ForeignKeyLink. Works today. */
    @Test
    public void balanceByYear() {
        Map<String, Double> m = rowMap(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " [Date].[Calendar].[Year].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1500.0, m.get("2024"), 0.001);
        assertEquals(300.0, m.get("2025"), 0.001);
    }

    // ---- acceptance tests for Phases 3-4 (bridge read + aggregate) ----

    /** fullCount: each member counted in full, deduped per account grain. */
    @Disabled("enabled when the Calcite bridge read/aggregate lands (#107 P3-4)")
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

    /** fullCount All Customers dedupes the fan-out to 1800 (not naive 3100). */
    @Disabled("enabled when the Calcite bridge read/aggregate lands (#107 P3-4)")
    @Test
    public void fullCountAllCustomersDedupes() {
        Query q = conn.parseQuery(
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " {[Customer].[Customer].[All Customer]} ON ROWS\n"
            + "FROM [AccountsFull]");
        Result r = conn.execute(q);
        double all =
            ((Number) r.getCell(new int[]{0, 0}).getValue()).doubleValue();
        r.close();
        assertEquals(1800.0, all, 0.001, "deduped, not the naive 3100");
    }

    /** weighted: each balance split by ownership weight; reconciles to 1800. */
    @Disabled("enabled when the Calcite bridge read/aggregate lands (#107 P3-4)")
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
}
