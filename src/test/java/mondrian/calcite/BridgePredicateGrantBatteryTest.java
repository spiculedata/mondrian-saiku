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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #107 × #106 — the bridge (many-to-many) dimension crossed with a
 * predicate-based row-security grant. A {@code <PredicateGrant>} on a bridged
 * measure group must filter fact rows INSIDE the DISTINCT fan-out subquery
 * (pre-aggregation, pre-dedup), so two tenants see disjoint, correctly-deduped
 * bridge aggregates and a tenant's {@code [All]} partitions the ungranted
 * total over only its own fact rows.
 *
 * <pre>
 *   bp_fact                       bp_owner (bridge)
 *   acct tenant balance weight    acct customer weight
 *    1     1     1000             1   Alice    0.5
 *    2     1      500             1   Bob      0.5   (acct1 joint)
 *    3     2      300             2   Bob      1.0
 *                                  3   Carol    1.0
 *   tenant 1 accounts: 1,2  -> deduped 1500
 *   tenant 2 accounts: 3    -> 300
 *   ungranted [All]: 1800 (= 1500 + 300, disjoint partition)
 * </pre>
 */
public class BridgePredicateGrantBatteryTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"bp_fact\"",
        "DROP TABLE IF EXISTS \"bp_owner\"",
        "DROP TABLE IF EXISTS \"bp_customer\"",
        "CREATE TABLE \"bp_fact\" (\"account_id\" INTEGER,"
            + " \"tenant\" INTEGER, \"balance\" INTEGER)",
        "CREATE TABLE \"bp_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"bp_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32))",
        "INSERT INTO \"bp_fact\" VALUES (1, 1, 1000)",
        "INSERT INTO \"bp_fact\" VALUES (2, 1, 500)",
        "INSERT INTO \"bp_fact\" VALUES (3, 2, 300)",
        "INSERT INTO \"bp_owner\" VALUES (1, 'Alice', 0.5)",
        "INSERT INTO \"bp_owner\" VALUES (1, 'Bob', 0.5)",
        "INSERT INTO \"bp_owner\" VALUES (2, 'Bob', 1.0)",
        "INSERT INTO \"bp_owner\" VALUES (3, 'Carol', 1.0)",
        "INSERT INTO \"bp_customer\" VALUES ('Alice', 'Alice')",
        "INSERT INTO \"bp_customer\" VALUES ('Bob', 'Bob')",
        "INSERT INTO \"bp_customer\" VALUES ('Carol', 'Carol')",
    };

    private static String cube(String name, String bridgeAggAttrs) {
        return "  <Cube name='" + name + "'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='S' table='bp_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <BridgeLink dimension='Customer'"
            + " bridgeTable='bp_owner'"
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
        "<Schema name='BP' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='bp_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='bp_owner'/>\n"
        + "    <Table name='bp_customer'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric'"
        + " defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Customer' table='bp_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Customer'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + cube("AccountsFull", "")
        + cube("AccountsWeighted",
               " aggregation='weighted' weightColumn='weight'")
        + "  <Role name='Tenant'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='AccountsFull' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "      <CubeGrant cube='AccountsWeighted' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static final String H2_URL =
        "jdbc:h2:mem:bp_e2e;DB_CLOSE_DELAY=-1";

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

    private static String schemaFor(String form) {
        return "yaml".equals(form)
            ? mondrian.schema.yaml.m4.M4YamlToXml.toXml(
                mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(SCHEMA))
            : SCHEMA;
    }

    private static Connection connect(
        String catalog, String role, String tenant, boolean cache)
    {
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
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            props.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(props, null, null);
    }

    private static Long allBalance(Connection conn, String cube) {
        Query q = conn.parseQuery(
            "SELECT {[Measures].[Balance]} ON COLUMNS FROM [" + cube + "]");
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).longValue();
    }

    // ---- 1) full-count bridge + predicate grant, filtered pre-fan-out ----

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void fullCountBridgePredicateGrantFiltersPreFanout(String form) {
        String schema = schemaFor(form);
        Connection t1 = connect(schema, "Tenant", "1", false);
        Connection t2 = connect(schema, "Tenant", "2", false);
        Connection ungranted = connect(schema, null, null, false);
        try {
            // Tenant 1 sees only its fact rows (accts 1,2), deduped over the
            // fan-out (acct1 joint Alice+Bob counts once) = 1500.
            assertEquals(1500L, allBalance(t1, "AccountsFull"),
                "tenant 1 [All] = deduped accts 1,2 (predicate inside fan-out)");
            // Tenant 2 sees only acct3 = 300; disjoint from tenant 1.
            assertEquals(300L, allBalance(t2, "AccountsFull"),
                "tenant 2 [All] = acct3");
            // Ungranted total partitions: 1500 + 300 = 1800.
            assertEquals(1800L, allBalance(ungranted, "AccountsFull"),
                "ungranted [All] = full deduped fact total");
        } finally {
            t1.close();
            t2.close();
            ungranted.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- 2) weighted bridge + predicate grant scales only visible rows ---

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void weightedBridgePredicateGrantScalesOnlyVisibleRows(String form) {
        String schema = schemaFor(form);
        Connection t1 = connect(schema, "Tenant", "1", false);
        Connection t2 = connect(schema, "Tenant", "2", false);
        try {
            // Weighted: SUM(balance * weight) over predicate-passing rows.
            // tenant 1: acct1 1000*(0.5+0.5)=1000 + acct2 500*1.0=500 = 1500.
            assertEquals(1500L, allBalance(t1, "AccountsWeighted"),
                "tenant 1 weighted [All] over its rows only");
            // tenant 2: acct3 300*1.0 = 300.
            assertEquals(300L, allBalance(t2, "AccountsWeighted"),
                "tenant 2 weighted [All] over its rows only");
        } finally {
            t1.close();
            t2.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- 3) secured bridged load refuses the legacy fallback -------------

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void securedBridgeLoadRefusesLegacyFallback(String form) {
        // A bridged load on the legacy backend has no native SQL path, and
        // the predicate grant must never be silently dropped. The load must
        // fail loudly (fail-closed), not return an unfiltered total.
        String prior = System.getProperty("mondrian.backend");
        System.setProperty("mondrian.backend", "legacy");
        Connection t1 = null;
        try {
            t1 = connect(schemaFor(form), "Tenant", "1", false);
            Query q = t1.parseQuery(
                "SELECT {[Measures].[Balance]} ON COLUMNS,\n"
                + " NON EMPTY [Customer].[Customer].Members ON ROWS\n"
                + "FROM [AccountsFull]");
            final Connection fc = t1;
            assertThrows(RuntimeException.class, () -> fc.execute(q),
                "a secured bridged load must fail closed on the legacy backend");
        } finally {
            if (t1 != null) {
                t1.close();
            }
            if (prior == null) {
                System.clearProperty("mondrian.backend");
            } else {
                System.setProperty("mondrian.backend", prior);
            }
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    // ---- 4) a bridge segment is not served across param values -----------

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void bridgeSegmentNotServedAcrossParamValues(String form) {
        // With the segment cache ON (default), warm it as tenant 1, then ask
        // as tenant 2 on a FRESH connection sharing the same schema/cache.
        // The cached tenant-1 bridge segment must NOT be served to tenant 2
        // (distinct per-user cache identity); tenant 2 must see its own 300.
        String schema = schemaFor(form);
        Connection t1 = connect(schema, "Tenant", "1", true);
        try {
            assertEquals(1500L, allBalance(t1, "AccountsFull"),
                "warm tenant 1 segment");
        } finally {
            t1.close();
        }
        Connection t2 = connect(schema, "Tenant", "2", true);
        try {
            Long v = allBalance(t2, "AccountsFull");
            assertTrue(v != null && v == 300L,
                "tenant 2 must see its own 300, never tenant 1's cached 1500"
                + " (got " + v + ")");
        } finally {
            t2.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
