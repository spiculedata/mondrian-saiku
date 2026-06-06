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
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
 *   <li>a bridge dimension crossed with a normal FK dimension (on
 *       different axes, and as a crossjoin on the same axis — a
 *       (bridge, non-bridge) tuple load);</li>
 *   <li>the bridge dimension on the COLUMNS axis;</li>
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
public class BridgeDimensionScenariosTest extends AbstractDualFormSchemaTest {

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

    // ---- full-count: leaf + All ---------------------------------------

    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountBalanceByCustomer(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1300.0, m.get("Alice"), 0.001);
        assertEquals(1500.0, m.get("Bob"), 0.001);
        assertEquals(300.0, m.get("Carol"), 0.001);
    }

    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountFeesByCustomer(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Fees]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(13.0, m.get("Alice"), 0.001);
        assertEquals(15.0, m.get("Bob"), 0.001);
        assertEquals(3.0, m.get("Carol"), 0.001);
    }

    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountAllDedupesToFactTotals(String form) {
        assertEquals(1800.0, scalar(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsFull]"),
            0.001);
        assertEquals(18.0, scalar(form,
            "SELECT {[Measures].[Fees]} ON COLUMNS FROM [AccountsFull]"),
            0.001);
    }

    /**
     * #107 (CRITICAL double-count): within ONE connection, warm the cache with
     * the per-customer LEAF query (Alice 1300, Bob 1500, Carol 300 cached as
     * leaf segments), THEN ask for the All level. A naive in-memory rollup
     * would SUM the cached leaf cells (1300+1500+300 = 3100), double-counting
     * accounts shared by several owners. The correct answer is the
     * fact-grain de-duplicated total (1800). A full-count bridge segment must
     * therefore be ineligible as a rollup source across the bridge dimension,
     * forcing a fresh fact-grain SQL load (emitSymmetricAggregate).
     *
     * <p>This also makes {@link #fullCountAllDedupesToFactTotals} deterministic
     * for the right reason: that test only flaked by run-order luck of cache
     * poisoning from a prior leaf query.
     */
    @ParameterizedTest
    @MethodSource("forms")
    public void bridgeAllLevelRollupFromCachedLeafStillDedupes(String form) {
        // 1) Warm the cache with per-customer leaf cells. Use the full member
        //    set WITHOUT NON EMPTY so the bridge (customer) column is cached
        //    as a complete, wildcard-coverable leaf segment — exactly the
        //    shape the rollup planner treats as a viable source for a coarser
        //    (All-level) request.
        Map<String, Double> leaf = rowMap(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1300.0, leaf.get("Alice"), 0.001);
        assertEquals(1500.0, leaf.get("Bob"), 0.001);
        assertEquals(300.0, leaf.get("Carol"), 0.001);
        // 2) Now the All level — must NOT be 3100 (rolled-up sum of leaves).
        assertEquals(1800.0, scalar(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsFull]"),
            0.001);
    }

    // ---- weighted: leaf + All -----------------------------------------

    @ParameterizedTest
    @MethodSource("forms")
    public void weightedBalanceByCustomer(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(575.0, m.get("Alice"), 0.001);
        assertEquals(1000.0, m.get("Bob"), 0.001);
        assertEquals(225.0, m.get("Carol"), 0.001);
    }

    @ParameterizedTest
    @MethodSource("forms")
    public void weightedFeesByCustomer(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Fees]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(5.75, m.get("Alice"), 0.001);
        assertEquals(10.0, m.get("Bob"), 0.001);
        assertEquals(2.25, m.get("Carol"), 0.001);
    }

    @ParameterizedTest
    @MethodSource("forms")
    public void weightedAllReconcilesToFactTotal(String form) {
        // Weights sum to 1 per account, so the All level equals the fact
        // total regardless of allocation.
        assertEquals(1800.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsWeighted]"),
            0.001);
    }

    // ---- bridge × normal FK dimension ---------------------------------

    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountBridgeCrossRegion(String form) {
        Map<String, Double> g = grid(form, 
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

    @ParameterizedTest
    @MethodSource("forms")
    public void weightedBridgeCrossRegion(String form) {
        Map<String, Double> g = grid(form, 
            "SELECT NON EMPTY [Region].[Region].Members ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]\n"
            + "WHERE [Measures].[Balance]");
        assertEquals(575.0, g.get("Alice|North"), 0.001);
        assertEquals(500.0, g.get("Bob|North"), 0.001);
        assertEquals(500.0, g.get("Bob|South"), 0.001);
        assertEquals(225.0, g.get("Carol|North"), 0.001);
    }

    /** The bridge dimension on the COLUMNS axis (the transpose of the cross
     *  above): bridge members on columns, a normal FK dim on rows. */
    @ParameterizedTest
    @MethodSource("forms")
    public void bridgeOnColumnsRegionOnRows(String form) {
        Map<String, Double> g = grid(form, 
            "SELECT NON EMPTY [Customer].[Customer].Members ON COLUMNS,\n"
            + " NON EMPTY [Region].[Region].Members ON ROWS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Measures].[Balance]");
        // grid(form, ) keys are rowCaption|colCaption → "Region|Customer".
        assertEquals(1300.0, g.get("North|Alice"), 0.001);
        assertEquals(1000.0, g.get("North|Bob"), 0.001);
        assertEquals(300.0, g.get("North|Carol"), 0.001);
        assertEquals(500.0, g.get("South|Bob"), 0.001);
        assertNull(g.get("South|Alice"));
    }

    // ---- bridge × non-bridge crossjoin on the SAME axis ---------------

    /** A crossjoin of the bridge dimension with a normal FK dimension on a
     *  single axis (Customer × Branch on ROWS), so one segment load groups
     *  by a (bridge, non-bridge) tuple. Full-count allocation. */
    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountBridgeCrossNonBridgeSameAxis(String form) {
        Map<String, Double> m = rowTupleMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members"
            + " * [Region].[Region].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1300.0, m.get("Alice|North"), 0.001);
        assertEquals(1000.0, m.get("Bob|North"), 0.001);
        assertEquals(500.0, m.get("Bob|South"), 0.001);
        assertEquals(300.0, m.get("Carol|North"), 0.001);
        // Empty tuples are suppressed by NON EMPTY.
        assertNull(m.get("Alice|South"));
        assertNull(m.get("Carol|South"));
    }

    /** The same same-axis crossjoin under weighted allocation. */
    @ParameterizedTest
    @MethodSource("forms")
    public void weightedBridgeCrossNonBridgeSameAxis(String form) {
        Map<String, Double> m = rowTupleMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members"
            + " * [Region].[Region].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(575.0, m.get("Alice|North"), 0.001);
        assertEquals(500.0, m.get("Bob|North"), 0.001);
        assertEquals(500.0, m.get("Bob|South"), 0.001);
        assertEquals(225.0, m.get("Carol|North"), 0.001);
    }

    // ---- bridge member in the slicer ----------------------------------

    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountBridgeMemberSlicer(String form) {
        assertEquals(1300.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Alice]"), 0.001);
        assertEquals(1500.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Bob]"), 0.001);
    }

    @ParameterizedTest
    @MethodSource("forms")
    public void weightedBridgeMemberSlicer(String form) {
        assertEquals(575.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsWeighted]\n"
            + "WHERE [Customer].[Customer].[Alice]"), 0.001);
    }

    /** Normal dimension on rows, bridge member slicing. */
    @ParameterizedTest
    @MethodSource("forms")
    public void regionRowsBridgeSlicerFullCount(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Region].[Region].Members ON ROWS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Bob]");
        assertEquals(1000.0, m.get("North"), 0.001);
        assertEquals(500.0, m.get("South"), 0.001);
    }

    // ---- multiple measures together over a bridge ---------------------

    @ParameterizedTest
    @MethodSource("forms")
    public void multipleMeasuresOverBridge(String form) {
        Map<String, Double> g = grid(form, 
            "SELECT {[Measures].[Balance], [Measures].[Fees]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(575.0, g.get("Alice|Balance"), 0.001);
        assertEquals(5.75, g.get("Alice|Fees"), 0.001);
        assertEquals(1000.0, g.get("Bob|Balance"), 0.001);
        assertEquals(10.0, g.get("Bob|Fees"), 0.001);
    }

    // ---- single-owner vs multi-owner; explicit member sets ------------

    @ParameterizedTest
    @MethodSource("forms")
    public void singleOwnerAccountExplicitMember(String form) {
        // Carol owns only a fraction of one account.
        assertEquals(300.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsFull]\n"
            + "WHERE [Customer].[Customer].[Carol]"), 0.001);
        assertEquals(225.0, scalar(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS\n"
            + "FROM [AccountsWeighted]\n"
            + "WHERE [Customer].[Customer].[Carol]"), 0.001);
    }

    @ParameterizedTest
    @MethodSource("forms")
    public void explicitMemberSetRows(String form) {
        Map<String, Double> m = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " {[Customer].[Customer].[Bob],"
            + " [Customer].[Customer].[Carol]} ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1500.0, m.get("Bob"), 0.001);
        assertEquals(300.0, m.get("Carol"), 0.001);
    }

    // ---- NON EMPTY suppression of an unowned customer -----------------

    @ParameterizedTest
    @MethodSource("forms")
    public void daveUnownedIsNullThenSuppressed(String form) {
        // Without NON EMPTY, Dave appears with a null cell.
        Map<String, Double> all = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertTrue(all.containsKey("Dave"), "Dave present without NON EMPTY");
        assertNull(all.get("Dave"), "Dave has no accounts → null");
        // With NON EMPTY, Dave is suppressed.
        Map<String, Double> ne = rowMap(form, 
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertTrue(!ne.containsKey("Dave"), "Dave suppressed by NON EMPTY");
    }

    // ---- bridge × bridge-independent time dimension -------------------

    @ParameterizedTest
    @MethodSource("forms")
    public void fullCountBridgeCrossYear(String form) {
        Map<String, Double> g = grid(form, 
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

    // ---- bridge dimension + role grant + slicer (#106 / #107) ---------

    /**
     * #106/#107 interaction (previously untested): a role whose
     * {@code <HierarchyGrant>} restricts the BRIDGE (Customer) dimension to a
     * subset of members, combined with a slicer. The restricted user must see
     * the bridge allocation computed only over the granted members — the grant
     * predicate and the fan-out de-duplication must compose, not bypass each
     * other. Here the role grants only Alice and Bob; a slicer on North then
     * pins their North allocation, and the role must hide Carol entirely.
     *
     * <p>Built on a fresh, role-scoped connection (a warm member cache from
     * the unrestricted tests above must not leak granted-away members).
     */
    @ParameterizedTest
    @MethodSource("forms")
    public void bridgeWithRoleGrantSlicer(String form) {
        String roleSchema = SCHEMA.replace(
            "</Schema>",
            "  <Role name='AliceBob'>\n"
            + "    <SchemaGrant access='all'>\n"
            + "      <CubeGrant cube='AccountsFull' access='all'>\n"
            + "        <HierarchyGrant hierarchy='[Customer].[Customer]'"
            + " access='custom'"
            + " topLevel='[Customer].[Customer].[Customer]'>\n"
            + "          <MemberGrant"
            + " member='[Customer].[Customer].[Alice]' access='all'/>\n"
            + "          <MemberGrant"
            + " member='[Customer].[Customer].[Bob]' access='all'/>\n"
            + "        </HierarchyGrant>\n"
            + "      </CubeGrant>\n"
            + "    </SchemaGrant>\n"
            + "  </Role>\n"
            + "</Schema>");
        String catalog = form.equals("yaml")
            ? mondrian.schema.yaml.m4.M4YamlToXml.toXml(
                mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(roleSchema))
            : roleSchema;
        Connection conn = null;
        try {
            Util.PropertyList props =
                Util.parseConnectString(TestContext.getDefaultConnectString());
            props.put("UseSchemaPool", "false");
            props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
            props.put(RolapConnectionProperties.Role.name(), "AliceBob");
            props.remove(RolapConnectionProperties.Catalog.name());
            conn = DriverManager.getConnection(props, null, null);

            // Restricted member list: Carol must be hidden by the grant.
            Query members = conn.parseQuery(
                "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
                + " [Customer].[Customer].Members ON ROWS\n"
                + "FROM [AccountsFull]");
            mondrian.olap.Result r = conn.execute(members);
            java.util.Set<String> visible = new java.util.HashSet<>();
            for (mondrian.olap.Position p
                : r.getAxes()[1].getPositions())
            {
                visible.add(p.get(0).getName());
            }
            r.close();
            assertTrue(visible.contains("Alice"), "Alice granted");
            assertTrue(visible.contains("Bob"), "Bob granted");
            assertTrue(!visible.contains("Carol"), "Carol hidden by grant");

            // Slicer on North + the grant, over the explicitly-granted
            // members (no NON EMPTY, so a granted member with a cell is always
            // present): Alice 1300 (acct1 1000 + acct3 300, both North), Bob
            // 1000 (acct1's North share). Full-count de-dup still applies and
            // the grant restricts the visible set — the two compose cleanly.
            Query q = conn.parseQuery(
                "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
                + " {[Customer].[Customer].[Alice],"
                + " [Customer].[Customer].[Bob]} ON ROWS\n"
                + "FROM [AccountsFull]\n"
                + "WHERE [Region].[Region].[North]");
            mondrian.olap.Result r2 = conn.execute(q);
            Map<String, Double> m = new java.util.LinkedHashMap<>();
            int i = 0;
            for (mondrian.olap.Position p
                : r2.getAxes()[1].getPositions())
            {
                Object v = r2.getCell(new int[]{0, i}).getValue();
                m.put(p.get(0).getName(),
                    v == null ? null : ((Number) v).doubleValue());
                i++;
            }
            r2.close();
            assertEquals(1300.0, m.get("Alice"), 0.001,
                "Alice North allocation (deduped) under the grant");
            assertEquals(1000.0, m.get("Bob"), 0.001,
                "Bob North allocation under the grant");
        } finally {
            if (conn != null) {
                conn.close();
            }
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- legacy backend must reject a bridge query loudly -------------

    @ParameterizedTest
    @MethodSource("forms")
    public void legacyBackendRejectsBridge(String form) {
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
