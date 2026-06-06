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
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.TestContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #107 SECURITY (DB-driven, caching ON, SHARED schema — exactly how Saiku
 * runs): a bridge (many-to-many) member grant constrains the fan-out fact
 * aggregation BELOW the aggregate, so the segment's VALUE depends on the role's
 * visible-member set — but that {@code Filter} is NOT part of the segment cache
 * identity ({@code SegmentHeader}). In a single JVM with ONE schema string and
 * ONE shared segment/value cache, role A's cached bridge {@code [All]} could be
 * cross-served to role B with a DIFFERENT visible set (cross-role disclosure).
 *
 * <p>This is the residual the #107 Vector-2b SQL fix left open; it is the same
 * class the #106 predicate-grant fix closed by folding a security key into the
 * cache identity. The fix folds the role's visible bridge-member-key SET into
 * the SAME {@code predicateSecurityCacheKey} on write + every read side.
 *
 * <p>Fixture (shared with {@code BridgeMemberGrantRlsTest}):
 * <pre>
 *   rl_fact                       rl_owner (bridge)
 *    acct balance fees             acct customer  weight
 *     1   1000    10                1   Alice      0.5
 *     2    500     5                1   Bob        0.5   (joint)
 *     3    300     3                2   Bob        1.0
 *     4    700     7                3   Alice      0.5
 *                                   3   Carol      0.5   (joint)
 *                                   4   Carol      1.0   (Carol-only)
 * </pre>
 *
 * <p>Role A ("AliceBob") sees {Alice,Bob}; Role B ("AliceOnly") sees {Alice}.
 * <ul>
 *   <li><b>Full-count [All]</b>: A = visible-owner accts 1,2,3 = 1800;
 *       B = visible-owner accts 1,3 (Alice on both) = 1000+300 = 1300.</li>
 *   <li><b>Weighted [All]</b>: A = 2500 - Carol's shares (150+700) = 1650;
 *       B = Alice's shares only (acct1 .5*1000 + acct3 .5*300) = 650.</li>
 * </ul>
 * Warming A then querying B (and vice-versa) must give each role its OWN
 * correctly-constrained value — never the other's cached segment. A second
 * connection with the SAME role MUST share the cached segment (the key is keyed
 * to the visible SET, not per-connection).
 */
public class BridgeMemberGrantCacheIsolationTest {

    private static final String[] DDL = {
        "DROP TABLE \"ci_fact\" IF EXISTS",
        "DROP TABLE \"ci_owner\" IF EXISTS",
        "DROP TABLE \"ci_customer\" IF EXISTS",
        "CREATE TABLE \"ci_fact\" (\"account_id\" INTEGER,"
            + " \"balance\" INTEGER, \"fees\" INTEGER)",
        "CREATE TABLE \"ci_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"ci_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32))",
        "INSERT INTO \"ci_fact\" VALUES (1, 1000, 10)",
        "INSERT INTO \"ci_fact\" VALUES (2, 500, 5)",
        "INSERT INTO \"ci_fact\" VALUES (3, 300, 3)",
        "INSERT INTO \"ci_fact\" VALUES (4, 700, 7)",
        "INSERT INTO \"ci_owner\" VALUES (1, 'Alice', 0.5)",
        "INSERT INTO \"ci_owner\" VALUES (1, 'Bob', 0.5)",
        "INSERT INTO \"ci_owner\" VALUES (2, 'Bob', 1.0)",
        "INSERT INTO \"ci_owner\" VALUES (3, 'Alice', 0.5)",
        "INSERT INTO \"ci_owner\" VALUES (3, 'Carol', 0.5)",
        "INSERT INTO \"ci_owner\" VALUES (4, 'Carol', 1.0)",
        "INSERT INTO \"ci_customer\" VALUES ('Alice', 'Alice')",
        "INSERT INTO \"ci_customer\" VALUES ('Bob', 'Bob')",
        "INSERT INTO \"ci_customer\" VALUES ('Carol', 'Carol')",
    };

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='ci_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='ci_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'"
            + bridgeAggAttrs + "/>\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n";
    }

    /** A single role definition (custom access to the named visible members). */
    private static String role(String roleName, String... visibleMembers) {
        StringBuilder grants = new StringBuilder();
        for (String m : visibleMembers) {
            grants.append("          <MemberGrant member='")
                .append(m).append("' access='all'/>\n");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  <Role name='").append(roleName).append("'>\n")
            .append("    <SchemaGrant access='all'>\n");
        for (String cubeName : new String[]{"AccountsFull", "AccountsWeighted"}) {
            sb.append("      <CubeGrant cube='").append(cubeName)
                .append("' access='all'>\n")
                .append("        <HierarchyGrant"
                    + " hierarchy='[Customer].[Customer]'"
                    + " access='custom'>\n")
                .append(grants)
                .append("        </HierarchyGrant>\n")
                .append("      </CubeGrant>\n");
        }
        sb.append("    </SchemaGrant>\n")
            .append("  </Role>\n");
        return sb.toString();
    }

    private static final String SCHEMA =
        "<Schema name='BankCI' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='ci_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='ci_owner'/>\n"
        + "    <Table name='ci_customer'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Customer' table='ci_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Customer' hasHierarchy='true'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + cube("AccountsFull", "")
        + cube("AccountsWeighted",
               " aggregation='weighted' weightColumn='weight'")
        + role("AliceBob",
               "[Customer].[Customer].[Alice]",
               "[Customer].[Customer].[Bob]")
        + role("AliceOnly", "[Customer].[Customer].[Alice]")
        + "</Schema>\n";

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
    }

    /**
     * A role-scoped connection on the SHARED schema string. SchemaPool OFF so
     * each role binds its own role-resolved reader, but the segment/value cache
     * stays ON — the point of the test is shared-cache isolation.
     */
    private static Connection connect(String role) {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), SCHEMA);
        props.remove(RolapConnectionProperties.Catalog.name());
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        } else {
            props.remove(RolapConnectionProperties.Role.name());
        }
        return DriverManager.getConnection(props, null, null);
    }

    private static double all(Connection conn, String cube) {
        Query q = conn.parseQuery(
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [" + cube + "]");
        Result r = conn.execute(q);
        try {
            return ((Number) r.getCell(new int[]{0}).getValue()).doubleValue();
        } finally {
            r.close();
        }
    }

    // ---- full-count bridge: A warms, B must NOT be served A's [All] --------

    @Test
    public void fullCountAThenBNoBleed() {
        Connection a = connect("AliceBob");
        Connection b = connect("AliceOnly");
        try {
            assertEquals(1800.0, all(a, "AccountsFull"), 0.001,
                "A (Alice+Bob) full-count [All] = visible-owner accts 1,2,3");
            assertEquals(1300.0, all(b, "AccountsFull"), 0.001,
                "B (Alice only) MUST see its own 1300 (accts 1,3), NEVER A's"
                + " cached 1800");
        } finally {
            a.close();
            b.close();
        }
    }

    /** Reverse order: B warms first, A must still get its own 1800. */
    @Test
    public void fullCountBThenANoBleed() {
        Connection b = connect("AliceOnly");
        Connection a = connect("AliceBob");
        try {
            assertEquals(1300.0, all(b, "AccountsFull"), 0.001,
                "B (Alice only) full-count [All] = accts 1,3 = 1300");
            assertEquals(1800.0, all(a, "AccountsFull"), 0.001,
                "A (Alice+Bob) MUST see its own 1800, NEVER B's cached 1300");
            assertEquals(1300.0, all(b, "AccountsFull"), 0.001,
                "B re-read unchanged after A's read (cache not corrupted)");
        } finally {
            b.close();
            a.close();
        }
    }

    // ---- weighted bridge: same isolation -----------------------------------

    @Test
    public void weightedAThenBNoBleed() {
        Connection a = connect("AliceBob");
        Connection b = connect("AliceOnly");
        try {
            assertEquals(1650.0, all(a, "AccountsWeighted"), 0.001,
                "A weighted [All] = 2500 - Carol's shares (850) = 1650");
            assertEquals(650.0, all(b, "AccountsWeighted"), 0.001,
                "B weighted [All] = Alice's shares only (500+150), NEVER A's"
                + " cached 1650");
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    public void weightedBThenANoBleed() {
        Connection b = connect("AliceOnly");
        Connection a = connect("AliceBob");
        try {
            assertEquals(650.0, all(b, "AccountsWeighted"), 0.001,
                "B weighted [All] = Alice's shares only = 650");
            assertEquals(1650.0, all(a, "AccountsWeighted"), 0.001,
                "A weighted [All] MUST see its own 1650, NEVER B's cached 650");
        } finally {
            b.close();
            a.close();
        }
    }

    // ---- same visible set DOES share the cache (not per-connection) --------

    @Test
    public void sameRoleSharesCachedSegment() {
        // Two DISTINCT connections, SAME role/visible-set. The second must read
        // the SAME correctly-constrained value (and may legitimately hit the
        // first's cached segment — the key is keyed to the visible SET).
        Connection a1 = connect("AliceBob");
        Connection a2 = connect("AliceBob");
        try {
            assertEquals(1800.0, all(a1, "AccountsFull"), 0.001,
                "first AliceBob connection full-count [All]");
            assertEquals(1800.0, all(a2, "AccountsFull"), 0.001,
                "second AliceBob connection sees the SAME 1800 (shared key)");
            assertEquals(1650.0, all(a1, "AccountsWeighted"), 0.001);
            assertEquals(1650.0, all(a2, "AccountsWeighted"), 0.001,
                "same role shares weighted segment too");
        } finally {
            a1.close();
            a2.close();
        }
    }
}
