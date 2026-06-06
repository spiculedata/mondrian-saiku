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
 * Issue #103: a <b>calculated-column</b> measure over a full-count bridge
 * must also be fan-out-safe. {@code Net = balance - cost} is an
 * {@code arithExpr} measure (not a plain real column), so the symmetric
 * aggregate must de-duplicate the calc <em>expression</em> on the fact grain
 * — not just real-column measures.
 *
 * <pre>
 *   accounts                 ownership          customer → segment
 *   acct balance cost net    acct cust          alice → Premium
 *    1    1000   100  900     1   alice          bob   → Premium
 *    2     500    50  450     1   bob            carol → Standard
 *    3     300    30  270     2   bob
 *                             3   alice
 *                             3   carol
 * </pre>
 *
 * <p>Golden values — Net rolled up to Segment, de-duplicated per account:
 * <ul>
 *   <li>Premium owns {1,2,3} → 900 + 450 + 270 = <b>1620</b> (acct1's 900
 *       counted once, not twice — naive fan-out = 2520).</li>
 *   <li>Standard owns {3} → 270.</li>
 *   <li>By customer leaf: Alice 1170, Bob 1350, Carol 270.</li>
 * </ul>
 * Tested alongside the plain {@code Balance} measure in the same query to
 * confirm mixed real + calc measures de-duplicate together.
 */
public class BridgeCalcMeasureTest extends AbstractDualFormSchemaTest {

    private static final String[] DDL = {
        "DROP TABLE \"cm_fact\" IF EXISTS",
        "DROP TABLE \"cm_owner\" IF EXISTS",
        "DROP TABLE \"cm_customer\" IF EXISTS",
        "CREATE TABLE \"cm_fact\" (\"account_id\" INTEGER,"
            + " \"balance\" INTEGER, \"cost\" INTEGER)",
        "CREATE TABLE \"cm_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"cm_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32), \"segment\" VARCHAR(16))",
        "INSERT INTO \"cm_fact\" VALUES (1, 1000, 100)",
        "INSERT INTO \"cm_fact\" VALUES (2, 500, 50)",
        "INSERT INTO \"cm_fact\" VALUES (3, 300, 30)",
        "INSERT INTO \"cm_owner\" VALUES (1, 'alice', 0.50)",
        "INSERT INTO \"cm_owner\" VALUES (1, 'bob',   0.50)",
        "INSERT INTO \"cm_owner\" VALUES (2, 'bob',   1.00)",
        "INSERT INTO \"cm_owner\" VALUES (3, 'alice', 0.25)",
        "INSERT INTO \"cm_owner\" VALUES (3, 'carol', 0.75)",
        "INSERT INTO \"cm_customer\" VALUES ('alice', 'Alice', 'Premium')",
        "INSERT INTO \"cm_customer\" VALUES ('bob',   'Bob',   'Premium')",
        "INSERT INTO \"cm_customer\" VALUES ('carol', 'Carol', 'Standard')",
    };

    private static final String SCHEMA =
        "<Schema name='Bank' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='cm_fact'>\n"
        + "      <Key><Column name='account_id'/></Key>\n"
        + "      <ColumnDefs>\n"
        + "        <CalculatedColumnDef name='net' type='Integer'>\n"
        + "          <ExpressionView>\n"
        + "            <SQL dialect='generic'>"
        + "<Column name='balance'/> - <Column name='cost'/></SQL>\n"
        + "          </ExpressionView>\n"
        + "        </CalculatedColumnDef>\n"
        + "      </ColumnDefs>\n"
        + "    </Table>\n"
        + "    <Table name='cm_owner'/>\n"
        + "    <Table name='cm_customer'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Customer' table='cm_customer' key='Customer'>\n"
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
        + cube("Accounts", "")
        + cube("AccountsWeighted",
               " aggregation='weighted' weightColumn='weight'")
        + "</Schema>\n";

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='cm_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "          <Measure name='Net' column='net' aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='cm_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'"
            + bridgeAggAttrs + "/>\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n";
    }

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

    /** Calc-column measure de-duplicates at the rolled-up Segment level,
     *  alongside a plain real-column measure in the same query. */
    @ParameterizedTest
    @MethodSource("forms")
    public void calcMeasureBySegmentDedupes(String form) {
        Map<String, Double> g = grid(form,
            "SELECT {[Measures].[Balance], [Measures].[Net]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[By Segment].[Segment].Members ON ROWS\n"
            + "FROM [Accounts]");
        assertEquals(1800.0, g.get("Premium|Balance"), 0.001);
        assertEquals(1620.0, g.get("Premium|Net"), 0.001,
            "calc measure de-dups acct1's net (900) once, not twice");
        assertEquals(300.0, g.get("Standard|Balance"), 0.001);
        assertEquals(270.0, g.get("Standard|Net"), 0.001);
    }

    /** Leaf level: calc measure correct per customer (naturally distinct). */
    @ParameterizedTest
    @MethodSource("forms")
    public void calcMeasureByCustomerLeaf(String form) {
        Map<String, Double> g = grid(form,
            "SELECT {[Measures].[Net]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[By Segment].[Customer].Members ON ROWS\n"
            + "FROM [Accounts]");
        assertEquals(1170.0, g.get("Alice|Net"), 0.001);
        assertEquals(1350.0, g.get("Bob|Net"), 0.001);
        assertEquals(270.0, g.get("Carol|Net"), 0.001);
    }

    /** Weighted allocation over a calc-column measure: each account's
     *  net is split by the owner's weight — SUM((balance - cost) × weight). */
    @ParameterizedTest
    @MethodSource("forms")
    public void weightedCalcMeasureByCustomer(String form) {
        Map<String, Double> g = grid(form,
            "SELECT {[Measures].[Net]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[By Segment].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        // acct1 net 900 (alice .5 / bob .5), acct2 net 450 (bob 1),
        // acct3 net 270 (alice .25 / carol .75).
        assertEquals(517.5, g.get("Alice|Net"), 0.001);  // 450 + 67.5
        assertEquals(900.0, g.get("Bob|Net"), 0.001);    // 450 + 450
        assertEquals(202.5, g.get("Carol|Net"), 0.001);  // 270 × 0.75
    }

    /** Weighted calc measure rolled up to Segment (additive, no dedup). */
    @ParameterizedTest
    @MethodSource("forms")
    public void weightedCalcMeasureBySegment(String form) {
        Map<String, Double> g = grid(form,
            "SELECT {[Measures].[Net]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[By Segment].[Segment].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(1417.5, g.get("Premium|Net"), 0.001); // 450+450+450+67.5
        assertEquals(202.5, g.get("Standard|Net"), 0.001);
    }
}
