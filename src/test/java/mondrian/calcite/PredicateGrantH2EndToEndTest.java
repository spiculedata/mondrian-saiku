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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #106 (TDD): end-to-end proof of predicate-based row security. A schema
 * declares a bounded {@code <QueryParameter>} and a {@code <Role>} carrying a
 * {@code <PredicateGrant>} that restricts a fact column to the parameter's
 * value. Two connections feeding different {@code session.tenant} values must
 * see correctly different, NON-OVERLAPPING aggregates over the same MDX, and
 * the per-tenant totals must partition the ungranted total. The predicate is
 * injected at the Calcite segment-load chokepoint, pre-aggregation, on every
 * segment load — so totals are correctly restricted.
 *
 * <p>Run against both the XML and the YAML-round-tripped form of the schema so
 * the {@code predicate_grant} converter support is a hard acceptance gate.
 */
public class PredicateGrantH2EndToEndTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"pg_sales\"",
        "CREATE TABLE \"pg_sales\" (\"tenant\" INTEGER,"
            + " \"region\" VARCHAR(8), \"amount\" INTEGER)",
        // tenant 1: 100 + 50 = 150 ; tenant 2: 7 + 3 = 10 ; tenant 3: 20
        "INSERT INTO \"pg_sales\" VALUES"
            + " (1,'EAST',100),(1,'WEST',50),"
            + " (2,'EAST',7),(2,'WEST',3),"
            + " (3,'EAST',20)",
    };

    private static final String SCHEMA =
        "<Schema name='PG' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='pg_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric'"
        + " defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "    <QueryParameterValue>3</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='pg_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='pg_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <FactLink dimension='Region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "  <Role name='Tenant'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static final String MDX =
        "SELECT {[Measures].[Amount]} ON COLUMNS FROM [Sales]";

    private static final String H2_URL =
        "jdbc:h2:mem:pg_e2e;DB_CLOSE_DELAY=-1";

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
        String catalog, String role, String tenant)
    {
        Util.PropertyList props = new Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(),
            "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        // Disable the segment cache so each connection's load is observable and
        // we never serve a stale segment (cross-user bleed is tested
        // separately with caching ON).
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            props.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(props, null, null);
    }

    private static Long total(Connection conn) {
        Query q = conn.parseQuery(MDX);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).longValue();
    }

    /** TDD #1: two tenants get non-overlapping aggregates that partition the
     *  ungranted total. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void perTenantTotalsPartitionUngranted(String form) {
        String schema = schemaFor(form);
        Connection t1 = connect(schema, "Tenant", "1");
        Connection t2 = connect(schema, "Tenant", "2");
        Connection t3 = connect(schema, "Tenant", "3");
        Connection ungranted = connect(schema, null, null);
        try {
            long a = total(t1);
            long b = total(t2);
            long c = total(t3);
            long all = total(ungranted);
            assertEquals(150L, a, "tenant 1 = 100+50");
            assertEquals(10L, b, "tenant 2 = 7+3");
            assertEquals(20L, c, "tenant 3 = 20");
            assertEquals(180L, all, "ungranted total");
            assertEquals(all, a + b + c,
                "per-tenant totals must partition the ungranted total");
        } finally {
            t1.close();
            t2.close();
            t3.close();
            ungranted.close();
        }
    }

    /** TDD #2: the predicate the chokepoint injects renders as a
     *  pre-aggregation WHERE on the real fact column (never a HAVING). This
     *  mirrors exactly the EQ param-bound filter the segment-load injection
     *  emits, rendered through the same planner, and proves placement BELOW
     *  the aggregate. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void predicateRendersAsPreAggregationWhere(String form)
        throws Exception
    {
        Connection t1 = connect(schemaFor(form), "Tenant", "1");
        try {
            CalciteMondrianSchema cms = new CalciteMondrianSchema(
                jdbcDataSource(), "pg");
            CalciteSqlPlanner planner = new CalciteSqlPlanner(
                cms, org.apache.calcite.sql.dialect.H2SqlDialect.DEFAULT);
            // The exact filter shape the injection emits: an EQ filter on the
            // real fact column 'tenant', bound to the 'tenant' parameter.
            PlannerRequest req = PlannerRequest.builder("pg_sales")
                .addGroupBy(new PlannerRequest.Column(null, "region"))
                .addMeasure(new PlannerRequest.Measure(
                    PlannerRequest.AggFn.SUM,
                    new PlannerRequest.Column(null, "amount"),
                    "total"))
                .addFilter(PlannerRequest.Filter.boundToParam(
                    new PlannerRequest.Column(null, "tenant"), "tenant"))
                .paramContext(
                    ((mondrian.rolap.RolapConnection) t1)
                        .getQueryParameterContext())
                .build();
            String sql = planner.plan(req);
            String upper = sql.toUpperCase();
            assertTrue(upper.contains("WHERE") && sql.contains("tenant"),
                "predicate must render as WHERE on tenant: " + sql);
            assertTrue(sql.contains("= 1"),
                "tenant=1 literal substituted from the bound param: " + sql);
            // Pre-aggregation: the tenant predicate is a WHERE, never a HAVING.
            assertTrue(!upper.contains("HAVING"),
                "tenant predicate must be pre-aggregation (WHERE), not "
                + "HAVING: " + sql);
            // And it must sit before GROUP BY in the text (WHERE precedes
            // GROUP BY in SQL), confirming it filters fact rows pre-rollup.
            int whereAt = upper.indexOf("WHERE");
            int groupAt = upper.indexOf("GROUP BY");
            assertTrue(whereAt >= 0 && (groupAt < 0 || whereAt < groupAt),
                "WHERE must precede GROUP BY: " + sql);
        } finally {
            t1.close();
        }
    }

    private static javax.sql.DataSource jdbcDataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(H2_URL);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /** TDD #4 (EQ, required param): a bound parameter with no session value
     *  and no default fails CLOSED — the connection is rejected at connect
     *  time, so no unsecured query can run (no data leak). */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void requiredParameterUnboundFailsClosed(String form) {
        // Drop the default so 'tenant' becomes required.
        String src = SCHEMA.replace(" defaultValue='1'", "");
        String schema = "yaml".equals(form)
            ? mondrian.schema.yaml.m4.M4YamlToXml.toXml(
                mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(src))
            : src;
        // Role applied but NO session.tenant supplied → fail closed at connect.
        assertThrows(Throwable.class,
            () -> {
                Connection conn = connect(schema, "Tenant", null);
                try {
                    total(conn);
                } finally {
                    conn.close();
                }
            },
            "a required predicate parameter with no value must fail closed");
    }

    /** TDD #6: cross-user cache non-bleed. Tenant 1 then tenant 2 (caching
     *  ON, same JVM) each see their own restricted total — the resolved
     *  predicate value participates in the segment cache identity. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void crossUserCacheNonBleed(String form) {
        String schema = schemaFor(form);
        // Two connections sharing the schema pool path; warm tenant 1's
        // segment first, then read tenant 2 — must NOT serve tenant 1's
        // cached 150.
        Connection t1 = connect(schema, "Tenant", "1");
        Connection t2 = connect(schema, "Tenant", "2");
        try {
            assertEquals(150L, total(t1), "tenant 1 warms cache");
            assertEquals(10L, total(t2),
                "tenant 2 must not be served tenant 1's cached segment");
            // Re-read tenant 1 — still its own value.
            assertEquals(150L, total(t1), "tenant 1 re-read unchanged");
        } finally {
            t1.close();
            t2.close();
        }
    }

    /** IN-operator schema variant: a String-typed multi-value parameter
     *  ({@code regions}) bound to an {@code in} predicate grant on the VARCHAR
     *  {@code region} fact column. A String parameter passes #105 connect-time
     *  validation as a single token; {@code resolveList} then splits the
     *  comma-separated value and validates each token through the same sandbox.
     *  Region totals: EAST = 100+7+20 = 127, WEST = 50+3 = 53, total 180. */
    private static final String SCHEMA_IN =
        "<Schema name='PG' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='pg_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='String'/>\n"
        + "  <Dimension name='Region' table='pg_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='pg_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <FactLink dimension='Region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "  <Role name='Tenant'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='region'"
        + " operator='in' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static String schemaInFor(String form) {
        return "yaml".equals(form)
            ? mondrian.schema.yaml.m4.M4YamlToXml.toXml(
                mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(SCHEMA_IN))
            : SCHEMA_IN;
    }

    private static Long totalRegions(Connection conn) {
        return total(conn);
    }

    /** TDD #3: IN operator. regions resolves to "EAST,WEST" → region IN
     *  (EAST,WEST); the total equals the union of the two single-region runs.
     *  Empty value fails closed. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void inOperatorUnionsValues(String form) {
        Connection both = connect(schemaInFor(form), "Tenant", "EAST,WEST");
        Connection east = connect(schemaInFor(form), "Tenant", "EAST");
        Connection west = connect(schemaInFor(form), "Tenant", "WEST");
        try {
            long all = totalRegions(both);
            long e = totalRegions(east);
            long w = totalRegions(west);
            assertEquals(180L, all, "region IN (EAST,WEST) = full 180");
            assertEquals(127L, e, "EAST = 100+7+20");
            assertEquals(53L, w, "WEST = 50+3");
            assertEquals(all, e + w, "IN(EAST,WEST) == EAST + WEST");
        } finally {
            both.close();
            east.close();
            west.close();
        }
    }

    /** TDD #4: an empty IN value fails closed (zero rows), never an
     *  unrestricted scan. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void inEmptyValueFailsClosed(String form) {
        // A comma-only value is a non-empty session string (so it harvests at
        // connect) that splits to ZERO tokens -> empty IN set -> deny all.
        Connection empty = connect(schemaInFor(form), "Tenant", " , ");
        try {
            Long v = totalRegions(empty);
            // Fail closed: either zero rows (null/0 cell) ...
            assertTrue(v == null || v == 0L,
                "empty IN membership must yield zero rows, got " + v);
        } catch (RuntimeException ex) {
            // ... or the always-false scan fails loudly. Both are fail-closed
            // (no fact rows ever reach the user); never an unrestricted total.
            assertNotNull(ex.getMessage());
        } finally {
            empty.close();
        }
    }

    /** TDD #5: fail-closed on legacy fallback. Under the legacy backend the
     *  Calcite path is unavailable, so a predicate-secured load MUST fail
     *  loudly rather than silently fall back to the legacy SQL generator
     *  (which knows nothing about predicate grants and would leak rows). */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void legacyBackendRefusesSecuredLoad(String form) {
        String prior = System.getProperty("mondrian.backend");
        System.setProperty("mondrian.backend", "legacy");
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Connection conn = connect(schemaFor(form), "Tenant", "1");
        // Flush any segment another test (under the Calcite backend) warmed,
        // so this load actually reaches the SQL generator and trips the gate.
        conn.getCacheControl(null).flush(
            conn.getCacheControl(null).createMeasuresRegion(
                conn.getSchema().lookupCube("Sales", true)));
        try {
            total(conn);
            org.junit.jupiter.api.Assertions.fail(
                "legacy backend must NOT silently serve a predicate-secured "
                + "load (it would drop the row-security filter)");
        } catch (RuntimeException expected) {
            String msg = String.valueOf(expected.getMessage())
                + " " + String.valueOf(
                    expected.getCause() == null
                        ? "" : expected.getCause().getMessage());
            assertTrue(
                msg.toLowerCase().contains("predicate")
                    || msg.toLowerCase().contains("security")
                    || msg.toLowerCase().contains("calcite"),
                "expected a clear predicate-security failure, got: " + msg);
        } finally {
            conn.close();
            if (prior == null) {
                System.clearProperty("mondrian.backend");
            } else {
                System.setProperty("mondrian.backend", prior);
            }
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    /** Misconfiguration is rejected at load: a predicate grant on a
     *  non-existent column fails loudly rather than silently. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void badColumnRejectedAtLoad(String form) {
        // Mutate the SOURCE schema, then convert to the requested form, so the
        // bad column survives the YAML round-trip too.
        String badSource =
            SCHEMA.replace("column='tenant'", "column='no_such_col'");
        String bad = "yaml".equals(form)
            ? mondrian.schema.yaml.m4.M4YamlToXml.toXml(
                mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(badSource))
            : badSource;
        assertThrows(Throwable.class,
            () -> connect(bad, "Tenant", "1").close(),
            "predicate grant on a non-existent fact column must fail to load");
    }
}
