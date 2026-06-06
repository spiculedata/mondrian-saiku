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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #107: executable proof that the shipped cube-library "many-to-many"
 * example is correct. This mirrors, row-for-row, the 8-account / 8-customer
 * joint-accounts dataset in {@code saiku-cloud/cube-library/many-to-many/}
 * and asserts every golden value documented in that template's README, so
 * the example a customer downloads and the engine that runs it can never
 * silently drift apart.
 *
 * <p>The schema here is the Mondrian-4 equivalent of that template's
 * {@code schema.xml} (two cubes — full-count and weighted — over one fact),
 * retargeted to the test's HSQLDB instead of Postgres.
 */
public class BridgeExampleParityTest extends AbstractDualFormSchemaTest {

    private static final String[] DDL = {
        "DROP TABLE \"mm_fact\" IF EXISTS",
        "DROP TABLE \"mm_owner\" IF EXISTS",
        "DROP TABLE \"mm_customer\" IF EXISTS",
        "DROP TABLE \"mm_branch\" IF EXISTS",
        "DROP TABLE \"mm_date\" IF EXISTS",
        "CREATE TABLE \"mm_fact\" (\"account_id\" INTEGER,"
            + " \"date_key\" INTEGER, \"branch_id\" VARCHAR(8),"
            + " \"balance\" INTEGER, \"fees\" INTEGER)",
        "CREATE TABLE \"mm_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"mm_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32))",
        "CREATE TABLE \"mm_branch\" (\"branch_id\" VARCHAR(8),"
            + " \"branch_name\" VARCHAR(32))",
        "CREATE TABLE \"mm_date\" (\"date_key\" INTEGER, \"yr\" INTEGER)",
        // fact: account_id, date, branch, balance, fees
        "INSERT INTO \"mm_fact\" VALUES (1, 2024, 'LON', 1000, 10)",
        "INSERT INTO \"mm_fact\" VALUES (2, 2024, 'LON',  500,  5)",
        "INSERT INTO \"mm_fact\" VALUES (3, 2025, 'LDS',  300,  3)",
        "INSERT INTO \"mm_fact\" VALUES (4, 2024, 'LON', 2000, 20)",
        "INSERT INTO \"mm_fact\" VALUES (5, 2024, 'LDS', 1500, 15)",
        "INSERT INTO \"mm_fact\" VALUES (6, 2025, 'LDS',  700,  7)",
        "INSERT INTO \"mm_fact\" VALUES (7, 2025, 'LON', 4000, 40)",
        "INSERT INTO \"mm_fact\" VALUES (8, 2025, 'LDS', 3000, 30)",
        // bridge: weights sum to 1 per account
        "INSERT INTO \"mm_owner\" VALUES (1, 'alice', 0.50)",
        "INSERT INTO \"mm_owner\" VALUES (1, 'bob',   0.50)",
        "INSERT INTO \"mm_owner\" VALUES (2, 'bob',   1.00)",
        "INSERT INTO \"mm_owner\" VALUES (3, 'alice', 0.25)",
        "INSERT INTO \"mm_owner\" VALUES (3, 'carol', 0.75)",
        "INSERT INTO \"mm_owner\" VALUES (4, 'erin',  0.50)",
        "INSERT INTO \"mm_owner\" VALUES (4, 'frank', 0.50)",
        "INSERT INTO \"mm_owner\" VALUES (5, 'frank', 1.00)",
        "INSERT INTO \"mm_owner\" VALUES (6, 'grace', 0.40)",
        "INSERT INTO \"mm_owner\" VALUES (6, 'heidi', 0.60)",
        "INSERT INTO \"mm_owner\" VALUES (7, 'erin',  0.50)",
        "INSERT INTO \"mm_owner\" VALUES (7, 'grace', 0.50)",
        "INSERT INTO \"mm_owner\" VALUES (8, 'heidi', 1.00)",
        // customers — 'dave' owns nothing
        "INSERT INTO \"mm_customer\" VALUES ('alice', 'Alice')",
        "INSERT INTO \"mm_customer\" VALUES ('bob',   'Bob')",
        "INSERT INTO \"mm_customer\" VALUES ('carol', 'Carol')",
        "INSERT INTO \"mm_customer\" VALUES ('dave',  'Dave')",
        "INSERT INTO \"mm_customer\" VALUES ('erin',  'Erin')",
        "INSERT INTO \"mm_customer\" VALUES ('frank', 'Frank')",
        "INSERT INTO \"mm_customer\" VALUES ('grace', 'Grace')",
        "INSERT INTO \"mm_customer\" VALUES ('heidi', 'Heidi')",
        "INSERT INTO \"mm_branch\" VALUES ('LON', 'London')",
        "INSERT INTO \"mm_branch\" VALUES ('LDS', 'Leeds')",
        "INSERT INTO \"mm_date\" VALUES (2024, 2024)",
        "INSERT INTO \"mm_date\" VALUES (2025, 2025)",
    };

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "      <Dimension source='Branch'/>\n"
            + "      <Dimension source='Date'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='mm_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "          <Measure name='Fees' column='fees'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Branch'"
            + " foreignKeyColumn='branch_id'/>\n"
            + "          <ForeignKeyLink dimension='Date'"
            + " foreignKeyColumn='date_key'/>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='mm_owner'"
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
        "<Schema name='ManyToMany' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='mm_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='mm_owner'/>\n"
        + "    <Table name='mm_customer'/>\n"
        + "    <Table name='mm_branch'/>\n"
        + "    <Table name='mm_date'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Customer' table='mm_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Customer'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Branch' table='mm_branch' key='Branch'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Branch'>\n"
        + "        <Key><Column name='branch_id'/></Key>\n"
        + "        <Name><Column name='branch_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Date' table='mm_date' key='Date Id'"
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
        + cube("Accounts Full Count", "")
        + cube("Accounts Weighted",
               " aggregation='weighted' weightColumn='weight'")
        + "</Schema>\n";

    private static Map<String, Connection> conns;

    @BeforeAll
    public static void boot() throws Exception {
        conns = bootForms(DDL, SCHEMA);
    }

    @AfterAll
    public static void close() {
        closeForms(conns);
    }

    @Override
    protected Connection conn(String form) {
        return conns.get(form);
    }

    // README Query 1 — grand totals.
    @ParameterizedTest
    @MethodSource("forms")
    public void grandTotals(String form) {
        assertEquals(13000.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS"
            + " FROM [Accounts Full Count]"), 0.001);
        assertEquals(130.0, scalar(form, 
            "SELECT {[Measures].[Fees]} ON COLUMNS"
            + " FROM [Accounts Full Count]"), 0.001);
        // Weighted reconciles to the same totals.
        assertEquals(13000.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS"
            + " FROM [Accounts Weighted]"), 0.001);
    }

    // README Query 2 — balance by branch (normal star).
    @ParameterizedTest
    @MethodSource("forms")
    public void balanceByBranch(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,"
            + " [Branch].[Branch].Members ON ROWS"
            + " FROM [Accounts Full Count]");
        assertEquals(7500.0, m.get("London"), 0.001);
        assertEquals(5500.0, m.get("Leeds"), 0.001);
    }

    // README Query 3 — full-count balance by customer.
    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountByCustomer(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,"
            + " NON EMPTY [Customer].[Customer].[Customer].Members ON ROWS"
            + " FROM [Accounts Full Count]");
        assertEquals(1300.0, m.get("Alice"), 0.001);
        assertEquals(1500.0, m.get("Bob"), 0.001);
        assertEquals(300.0, m.get("Carol"), 0.001);
        assertEquals(6000.0, m.get("Erin"), 0.001);
        assertEquals(3500.0, m.get("Frank"), 0.001);
        assertEquals(4700.0, m.get("Grace"), 0.001);
        assertEquals(3700.0, m.get("Heidi"), 0.001);
        // Dave owns nothing → suppressed by NON EMPTY.
        org.junit.jupiter.api.Assertions.assertEquals(
            false, m.containsKey("Dave"));
    }

    // README — full-count (All) de-duplicates to 13000, not 21000.
    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountAllDedupes(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,"
            + " {[Customer].[Customer].[All Customer]} ON ROWS"
            + " FROM [Accounts Full Count]");
        assertEquals(13000.0, m.get("All Customer"), 0.001);
    }

    // README Query 4 — weighted balance by customer (sums to 13000).
    @ParameterizedTest
    @MethodSource("forms")
    public void weightedByCustomer(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,"
            + " NON EMPTY [Customer].[Customer].[Customer].Members ON ROWS"
            + " FROM [Accounts Weighted]");
        assertEquals(575.0, m.get("Alice"), 0.001);
        assertEquals(1000.0, m.get("Bob"), 0.001);
        assertEquals(225.0, m.get("Carol"), 0.001);
        assertEquals(3000.0, m.get("Erin"), 0.001);
        assertEquals(2500.0, m.get("Frank"), 0.001);
        assertEquals(2280.0, m.get("Grace"), 0.001);
        assertEquals(3420.0, m.get("Heidi"), 0.001);
        double sum = 0;
        for (Double v : m.values()) {
            if (v != null) {
                sum += v;
            }
        }
        assertEquals(13000.0, sum, 0.001, "weighted parts reconcile");
    }

    // README Query 6 — a bridge member in the slicer.
    @ParameterizedTest
    @MethodSource("forms")
    public void bridgeSlicerBranchRows(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,"
            + " NON EMPTY [Branch].[Branch].Members ON ROWS"
            + " FROM [Accounts Full Count]"
            + " WHERE [Customer].[Customer].[Bob]");
        assertEquals(1500.0, m.get("London"), 0.001);
        // Bob has no Leeds accounts.
        org.junit.jupiter.api.Assertions.assertEquals(
            false, m.containsKey("Leeds"));
    }
}
