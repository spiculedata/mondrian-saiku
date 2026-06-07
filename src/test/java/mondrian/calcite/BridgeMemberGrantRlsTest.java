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
import mondrian.olap.Position;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #107 — Vector 2b (row-security disclosure via the bridge fan-out).
 *
 * <p>A role's member/hierarchy grant on a BRIDGE (many-to-many) dimension MUST
 * constrain the bridge fan-out FACT aggregation, not merely the member axis
 * read. Without the fix, a full-count bridge leaks fact totals over accounts
 * owned <em>only</em> by hidden members: {@code [All]} and rollups reflect
 * owners the role cannot see.
 *
 * <p>Decided semantics (implemented):
 * <ul>
 *   <li>An account owned ONLY by hidden members is EXCLUDED entirely (from
 *       {@code [All]}, rollups, and every visible member's cell).</li>
 *   <li>A JOINT account (visible Alice + hidden Bob) counts ONCE via its
 *       visible owner; the hidden owner's bridge row is filtered out before the
 *       fan-out de-dup. Alice still sees the account's full balance
 *       (full-count); the hidden owner never inflates a total.</li>
 *   <li>Net: a user only ever sees accounts for which they have at least one
 *       visible owner; the {@code [All]}/symmetric de-dup operates over
 *       visible-owner bridge rows only.</li>
 * </ul>
 *
 * <pre>
 *   rl_fact                            rl_owner (bridge, full-count)
 *   acct region balance fees           acct customer
 *    1   North   1000    10              1   Alice
 *    2   South    500     5              1   Bob       (joint: Alice+Bob)
 *    3   North    300     3              2   Bob       (Bob-only)
 *    4   North    700     7              3   Alice
 *                                        3   Carol     (joint: Alice+Carol)
 *   full fact total: balance 2500         4   Carol     (Carol-ONLY)
 *
 *   Role "AliceBob" grants Alice + Bob, hides Carol.
 *   Accounts with >=1 visible owner: 1,2,3  (acct 4 is Carol-only -> excluded)
 *   Restricted [All] balance = 1000+500+300 = 1800  (NOT the full 2500)
 *   Alice = acct1(1000)+acct3(300) = 1300 ; Bob = acct1(1000)+acct2(500) = 1500
 * </pre>
 */
public class BridgeMemberGrantRlsTest extends AbstractDualFormSchemaTest {

    private static final String[] DDL = {
        "DROP TABLE \"rl_fact\" IF EXISTS",
        "DROP TABLE \"rl_owner\" IF EXISTS",
        "DROP TABLE \"rl_customer\" IF EXISTS",
        "DROP TABLE \"rl_region\" IF EXISTS",
        "DROP TABLE \"rl_seg\" IF EXISTS",
        "CREATE TABLE \"rl_fact\" (\"account_id\" INTEGER,"
            + " \"region_id\" VARCHAR(16), \"balance\" INTEGER,"
            + " \"fees\" INTEGER)",
        "CREATE TABLE \"rl_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"rl_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32), \"seg_id\" VARCHAR(16))",
        "CREATE TABLE \"rl_region\" (\"region_id\" VARCHAR(16),"
            + " \"region_name\" VARCHAR(32))",
        "CREATE TABLE \"rl_seg\" (\"seg_id\" VARCHAR(16),"
            + " \"seg_name\" VARCHAR(32))",
        "INSERT INTO \"rl_fact\" VALUES (1, 'North', 1000, 10)",
        "INSERT INTO \"rl_fact\" VALUES (2, 'South', 500, 5)",
        "INSERT INTO \"rl_fact\" VALUES (3, 'North', 300, 3)",
        "INSERT INTO \"rl_fact\" VALUES (4, 'North', 700, 7)",
        "INSERT INTO \"rl_owner\" VALUES (1, 'Alice', 0.5)",
        "INSERT INTO \"rl_owner\" VALUES (1, 'Bob', 0.5)",
        "INSERT INTO \"rl_owner\" VALUES (2, 'Bob', 1.0)",
        "INSERT INTO \"rl_owner\" VALUES (3, 'Alice', 0.5)",
        "INSERT INTO \"rl_owner\" VALUES (3, 'Carol', 0.5)",
        "INSERT INTO \"rl_owner\" VALUES (4, 'Carol', 1.0)",
        // Alice/Bob in segment 'Retail'; Carol in segment 'Private'.
        "INSERT INTO \"rl_customer\" VALUES ('Alice', 'Alice', 'Retail')",
        "INSERT INTO \"rl_customer\" VALUES ('Bob', 'Bob', 'Retail')",
        "INSERT INTO \"rl_customer\" VALUES ('Carol', 'Carol', 'Private')",
        "INSERT INTO \"rl_region\" VALUES ('North', 'North')",
        "INSERT INTO \"rl_region\" VALUES ('South', 'South')",
        "INSERT INTO \"rl_seg\" VALUES ('Retail', 'Retail')",
        "INSERT INTO \"rl_seg\" VALUES ('Private', 'Private')",
    };

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "      <Dimension source='Region'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='rl_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "          <Measure name='Fees' column='fees'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Region'"
            + " foreignKeyColumn='region_id'/>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='rl_owner'"
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
        + "    <Table name='rl_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='rl_owner'/>\n"
        + "    <Table name='rl_customer'/>\n"
        + "    <Table name='rl_region'/>\n"
        + "    <Table name='rl_seg'/>\n"
        + "  </PhysicalSchema>\n"
        // Customer dimension with a Segment->Customer hierarchy so the
        // multi-level rollup test can group at the Segment level.
        + "  <Dimension name='Customer' table='rl_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Segment'>\n"
        + "        <Key><Column name='seg_id'/></Key>\n"
        + "        <Name><Column name='seg_id'/></Name>\n"
        + "      </Attribute>\n"
        + "      <Attribute name='Customer'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "    <Hierarchies>\n"
        + "      <Hierarchy name='Customers' allMemberName='All Customers'>\n"
        + "        <Level attribute='Segment'/>\n"
        + "        <Level attribute='Customer'/>\n"
        + "      </Hierarchy>\n"
        + "    </Hierarchies>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Region' table='rl_region' key='Region'>\n"
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

    /** Role that grants Alice + Bob on the bridge Customer hierarchy and
     *  hides Carol (CUSTOM access at the Customer level). */
    private static final String ROLE_ALICE_BOB =
        "  <Role name='AliceBob'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='AccountsFull' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Customer].[Customers]'"
        + " access='custom'"
        + " bottomLevel='[Customer].[Customers].[Customer]'>\n"
        + "          <MemberGrant"
        + " member='[Customer].[Customers].[Retail].[Alice]'"
        + " access='all'/>\n"
        + "          <MemberGrant"
        + " member='[Customer].[Customers].[Retail].[Bob]'"
        + " access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "      <CubeGrant cube='AccountsWeighted' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Customer].[Customers]'"
        + " access='custom'"
        + " bottomLevel='[Customer].[Customers].[Customer]'>\n"
        + "          <MemberGrant"
        + " member='[Customer].[Customers].[Retail].[Alice]'"
        + " access='all'/>\n"
        + "          <MemberGrant"
        + " member='[Customer].[Customers].[Retail].[Bob]'"
        + " access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n";

    private static Map<String, Connection> conns;
    /** Unsecured (no-role) connections, used to prove the restricted weighted
     *  [All] EXCLUDES what the unsecured weighted [All] includes. */
    private static Map<String, Connection> noRoleConns;

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
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
        String yamlSchema = mondrian.schema.yaml.m4.M4YamlToXml.toXml(
            mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(SCHEMA));
        conns = new LinkedHashMap<>();
        conns.put("xml", roleConn(SCHEMA));
        conns.put("yaml", roleConn(yamlSchema));
        noRoleConns = new LinkedHashMap<>();
        noRoleConns.put("xml", noRoleConn(SCHEMA));
        noRoleConns.put("yaml", noRoleConn(yamlSchema));
    }

    @AfterEach
    public void clearCache() {
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    static Stream<String> forms() {
        return Stream.of("xml", "yaml");
    }

    @Override
    protected Connection conn(String form) {
        return conns.get(form);
    }

    /** A role-scoped connection (role AliceBob) on the given catalog. */
    private static Connection roleConn(String catalog) {
        String roleCatalog =
            catalog.replace("</Schema>", ROLE_ALICE_BOB + "</Schema>");
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), roleCatalog);
        props.put(RolapConnectionProperties.Role.name(), "AliceBob");
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    /** An unsecured (no-role) connection — the baseline the restricted
     *  weighted [All] is compared against. Built from the catalog with NO role
     *  defined (a structurally distinct schema → its own RolapStar/segment
     *  cache), so it cannot share a role-constrained segment with the role
     *  connection (row-security is applied below the aggregate, outside the
     *  segment cache key). This models "no-role = no constraint" cleanly. */
    private static Connection noRoleConn(String catalog) {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        props.remove(RolapConnectionProperties.Role.name());
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    private Double allScalarNoRole(String form, String mdx) {
        Connection conn = noRoleConns.get(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    // ---- helpers (role connections; rebuilds per call w/ clean cache) ----

    private Map<String, Double> custMap(String form, String mdx) {
        Connection conn = conn(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Map<String, Double> out = new LinkedHashMap<>();
        int i = 0;
        for (Position p : r.getAxes()[1].getPositions()) {
            Object v = r.getCell(new int[]{0, i}).getValue();
            out.put(p.get(0).getName(),
                v == null ? null : ((Number) v).doubleValue());
            i++;
        }
        r.close();
        return out;
    }

    private Double allScalar(String form, String mdx) {
        Connection conn = conn(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    // ---- 1) the 2b pin: hidden-only-owner account excluded from [All] ----

    @ParameterizedTest
    @MethodSource("forms")
    public void hiddenOnlyOwnerAccountExcludedFromAll(String form) {
        // acct4 (700) is owned ONLY by Carol (hidden). It must NOT appear in
        // [All], which must equal the de-duped fact total over accounts with
        // >=1 VISIBLE owner: acct1(1000)+acct2(500)+acct3(300) = 1800 — NOT
        // the full 2500.
        Double all = allScalar(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsFull]");
        assertEquals(1800.0, all, 0.001,
            "restricted [All] = visible-owner fact total (acct4 excluded)");

        // And it must not surface in any visible customer's cell either.
        Map<String, Double> m = custMap(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " [Customer].[Customers].[Customer].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertNull(m.get("Carol"), "Carol hidden by grant");
        // Sum of visible cells (full-count, so joint accts double-count across
        // owners) is independent of [All]; just assert Carol absent and the
        // visible owners present with their full-count cells.
        assertEquals(1300.0, m.get("Alice"), 0.001);
        assertEquals(1500.0, m.get("Bob"), 0.001);
    }

    // ---- 2) joint account counts once via visible owner ------------------

    @ParameterizedTest
    @MethodSource("forms")
    public void jointAccountCountsOnceViaVisibleOwner(String form) {
        // acct3 (300) is joint Alice(visible)+Carol(hidden). Under the grant
        // it counts ONCE via Alice in [All]; Carol's bridge row is filtered
        // out so it neither double-counts nor leaks. Alice's cell shows the
        // account's FULL balance (full-count): acct1 1000 + acct3 300 = 1300.
        Map<String, Double> m = custMap(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " {[Customer].[Customers].[Retail].[Alice]} ON ROWS\n"
            + "FROM [AccountsFull]");
        assertEquals(1300.0, m.get("Alice"), 0.001,
            "Alice sees full balance of her (incl. joint) accounts");

        // [All] over the visible owners de-dups acct3 once (via Alice), and
        // excludes acct4 (Carol-only): 1000+500+300 = 1800.
        Double all = allScalar(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsFull]");
        assertEquals(1800.0, all, 0.001,
            "joint acct3 counted once via Alice; acct4 (Carol-only) excluded");
    }

    // ---- 3) multi-level (Segment) rollup respects visibility -------------

    @ParameterizedTest
    @MethodSource("forms")
    public void bridgeMemberGrantRollupRespectsVisibility(String form) {
        // Group at the Segment level. Alice/Bob are in 'Retail'; Carol is in
        // 'Private'. Under the grant only 'Retail' is visible, and its rollup
        // must de-dup over the VISIBLE owners only: accounts owned by a Retail
        // customer are 1,2,3 -> 1000+500+300 = 1800. 'Private' (Carol) is
        // hidden, and acct4 (Carol-only) must not leak into any visible total.
        Map<String, Double> m = custMap(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " [Customer].[Customers].[Segment].Members ON ROWS\n"
            + "FROM [AccountsFull]");
        assertNull(m.get("Private"), "Private segment (Carol) hidden");
        assertEquals(1800.0, m.get("Retail"), 0.001,
            "Retail rollup de-dups visible-owner accounts (acct4 excluded)");
    }

    // ---- 4) WEIGHTED bridge: hidden-only-owner account excluded ----------

    @ParameterizedTest
    @MethodSource("forms")
    public void weightedBridgeHiddenOnlyOwnerExcludedFromAll(String form) {
        // Weighted [All] = SUM(balance * weight) over the bridge fan-out.
        //   acct1 1000: Alice .5 + Bob .5   -> 500 + 500
        //   acct2  500: Bob 1.0             -> 500
        //   acct3  300: Alice .5 + Carol .5 -> 150 + 150
        //   acct4  700: Carol 1.0           -> 700
        // Unsecured weighted [All] = 500+500+500+150+150+700 = 2500.
        Double unsecured = allScalarNoRole(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS"
            + " FROM [AccountsWeighted]");
        assertEquals(2500.0, unsecured, 0.001,
            "unsecured weighted [All] includes Carol's shares (acct3,acct4)");

        // Under role AliceBob, Carol is hidden: her bridge rows (acct3 .5,
        // acct4 1.0 = 150+700 = 850) must be excluded from the fan-out.
        // Restricted weighted [All] = 2500 - 850 = 1650.
        Double restricted = allScalar(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS"
            + " FROM [AccountsWeighted]");
        assertEquals(1650.0, restricted, 0.001,
            "restricted weighted [All] excludes Carol's weighted shares;"
            + " acct4 (Carol-only) fully gone");
        // It must be strictly less than the unsecured total (the leak closed).
        org.junit.jupiter.api.Assertions.assertTrue(restricted < unsecured,
            "restricted weighted [All] must exclude hidden owners' shares");
    }

    // ---- 5) WEIGHTED bridge: joint account contributes only visible share -

    @ParameterizedTest
    @MethodSource("forms")
    public void weightedBridgeJointAccountContributesOnlyVisibleShare(
        String form)
    {
        // acct3 (300) is joint Alice .5 (visible) + Carol .5 (hidden). Under
        // the grant it must contribute only Alice's 0.5*300 = 150 — NOT the
        // full 300, and NOT Carol's 150.
        // Alice's weighted cell = acct1 .5*1000 + acct3 .5*300 = 500+150 = 650.
        Map<String, Double> m = custMap(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
            + " {[Customer].[Customers].[Retail].[Alice]} ON ROWS\n"
            + "FROM [AccountsWeighted]");
        assertEquals(650.0, m.get("Alice"), 0.001,
            "Alice's weighted total = her shares only (acct1 .5 + acct3 .5)");

        // [All] excludes Carol entirely: acct3 contributes only Alice's 150,
        // acct4 (Carol-only) contributes nothing.
        Double restricted = allScalar(form,
            "SELECT {[Measures].[Balance]} ON COLUMNS"
            + " FROM [AccountsWeighted]");
        assertEquals(1650.0, restricted, 0.001,
            "joint acct3 contributes only Alice's 0.5 share to weighted [All]");
    }

    // ---- 6) member-grant-secured bridge load refuses the legacy fallback --

    @Test
    public void securedBridgeMemberGrantLoadRefusesLegacyFallback() {
        // The bridge member-grant filter (applyBridgeMemberGrant) is injected
        // ONLY in the Calcite SQL path. The SegmentLoader fail-closed gate
        // covers <PredicateGrant> loads but NOT <MemberGrant>/<HierarchyGrant>
        // bridge loads. On the legacy backend the load must fail CLOSED, never
        // execute legacy SQL that omits the member filter and leaks the
        // unsecured fan-out total (2500) for hidden owner Carol.
        String prior = System.getProperty("mondrian.backend");
        System.setProperty("mondrian.backend", "legacy");
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Connection rc = null;
        try {
            rc = roleConn(SCHEMA);
            final Connection fc = rc;
            final Query q = fc.parseQuery(
                "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsFull]");
            assertThrows(RuntimeException.class, () -> fc.execute(q),
                "a member-grant-secured bridge load must fail closed on the"
                + " legacy backend (no path injects the bridge member filter)");
        } finally {
            if (rc != null) {
                rc.close();
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
