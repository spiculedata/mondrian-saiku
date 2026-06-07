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

import mondrian.observability.MondrianMetrics;
import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #90 scope-item-3 end-to-end coverage for the divergence parity guard.
 *
 * <p>Two halves:
 * <ul>
 *   <li><b>Positive:</b> with {@code -Dmondrian.calcite.parityCheck=true}
 *       (+ strict) set, a handful of the parity-corpus shapes against the
 *       stock Calcite backend complete with NO divergence recorded — the
 *       guard stays silent (and strict mode does NOT throw) when Calcite is
 *       correct.</li>
 *   <li><b>RLS safety:</b> a predicate-secured ({@code <PredicateGrant>})
 *       load run with the parity check ON must NOT run the legacy comparison
 *       (the legacy generator drops the row-security filter). We assert no
 *       divergence is recorded AND the secured result is still correctly
 *       row-restricted — i.e. the parity check was safely SKIPPED, not
 *       silently leaking.</li>
 * </ul>
 *
 * <p>The system properties are reset in a finally / {@code @AfterEach} block
 * so the shared surefire JVM is left clean for other tests.
 */
public class CalciteParityCheckEndToEndTest {

    private static final String H2_URL =
        "jdbc:h2:mem:parity_rls;DB_CLOSE_DELAY=-1";

    // Same secured schema shape as PredicateGrantH2EndToEndTest.
    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"pp_sales\"",
        "CREATE TABLE \"pp_sales\" (\"tenant\" INTEGER,"
            + " \"region\" VARCHAR(8), \"amount\" INTEGER)",
        "INSERT INTO \"pp_sales\" VALUES"
            + " (1,'EAST',100),(1,'WEST',50),"
            + " (2,'EAST',7),(2,'WEST',3),"
            + " (3,'EAST',20)",
    };

    private static final String SECURED_SCHEMA =
        "<Schema name='PP' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='pp_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric'"
        + " defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "    <QueryParameterValue>3</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='pp_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='pp_sales'>\n"
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

    // #90/#119 distinct-grain fan-out — the legacy generator emits a plain
    // SUM(amount) that double-counts the fanned rows (850), while Calcite
    // de-dups on sale_id (450). Shape borrowed from
    // MeasureDistinctGrainH2EndToEndTest. The parity guard MUST skip this
    // load: comparing legacy's 850 against Calcite's correct 450 would record
    // a FALSE divergence (and strict mode would throw on a correct result).
    private static final String DG_URL =
        "jdbc:h2:mem:parity_dg;DB_CLOSE_DELAY=-1";

    private static final String[] DG_DDL = {
        "DROP TABLE IF EXISTS \"pdg_sale_line\"",
        "CREATE TABLE \"pdg_sale_line\" (\"sale_id\" INTEGER,"
            + " \"line\" VARCHAR(8), \"region\" VARCHAR(8), \"amount\" INTEGER)",
        // sale 1 → 3 lines @100, sale 2 → 1 line @50, sale 3 → 2 lines @300.
        // distinct sum over sale_id = 100+50+300 = 450 (CORRECT).
        // naive SUM over fanned rows = 300+50+600 = 950 (legacy, WRONG).
        // amount column carries the per-sale value repeated on every line,
        // and the median over the fanned rows differs from the de-duped set.
        "INSERT INTO \"pdg_sale_line\" VALUES"
            + " (1,'a','North',100),(1,'b','North',100),(1,'c','North',100),"
            + " (2,'a','South',50),"
            + " (3,'a','North',300),(3,'b','North',300)",
    };

    private static final String DG_SCHEMA =
        "<Schema name='ParityDG' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='pdg_sale_line'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Region' table='pdg_sale_line' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='pdg_sale_line'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Distinct Amount' column='amount'"
        + " aggregator='sum' distinctKeyColumn='sale_id'/>\n"
        + "          <Measure name='Median Amount' column='amount'"
        + " aggregator='median'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <FactLink dimension='Region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    private InMemoryMetricReader reader;
    private OpenTelemetrySdk sdk;

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            for (String sql : DDL) {
                st.execute(sql);
            }
        }
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(DG_URL, "sa", "");
             Statement st = c.createStatement())
        {
            for (String sql : DG_DDL) {
                st.execute(sql);
            }
        }
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    @AfterAll
    public static void shutdown() {
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    @BeforeEach
    public void registerInMemorySdk() {
        GlobalOpenTelemetry.resetForTest();
        MondrianMetrics.resetForTest();
        reader = InMemoryMetricReader.create();
        SdkMeterProvider mp = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        sdk = OpenTelemetrySdk.builder().setMeterProvider(mp).build();
        GlobalOpenTelemetry.set(sdk);
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty("mondrian.calcite.parityCheck");
        System.clearProperty("mondrian.calcite.parityCheck.strict");
        if (sdk != null) {
            sdk.close();
        }
        GlobalOpenTelemetry.resetForTest();
        MondrianMetrics.resetForTest();
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    /**
     * Positive: parity-corpus shapes complete with NO divergence under the
     * parity check (strict too), proving the guard stays silent when the
     * Calcite SQL is correct.
     */
    @Test
    public void correctCalciteLoadsRecordNoDivergence() throws Exception {
        System.setProperty("mondrian.calcite.parityCheck", "true");
        System.setProperty("mondrian.calcite.parityCheck.strict", "true");
        Connection conn = foodMartConnection();
        try {
            String[] mdx = {
                "SELECT {[Measures].[Unit Sales]} ON COLUMNS, NON EMPTY "
                + "[Store].[Stores].[Store Country].Members ON ROWS "
                + "FROM [Warehouse and Sales]",
                "SELECT {[Measures].[Warehouse Sales]} ON COLUMNS, NON EMPTY "
                + "[Product].[Products].[Product Family].Members ON ROWS "
                + "FROM [Warehouse and Sales]",
                "SELECT {[Measures].[Store Sales]} ON COLUMNS, "
                + "[Store].[Stores].[Store State].Members ON ROWS "
                + "FROM [Sales]",
            };
            for (String m : mdx) {
                Query q = conn.parseQuery(m);
                Result r = conn.execute(q);
                r.close();
            }
        } finally {
            conn.close();
        }
        long divergences = sumDivergence();
        assertEquals(0L, divergences,
            "correct Calcite loads must record ZERO parity divergences "
            + "(strict mode would have thrown otherwise)");
    }

    /**
     * RLS safety: a predicate-secured load under the parity check must SKIP
     * the legacy comparison (it would drop the row-security filter). No
     * divergence recorded, and the secured total stays row-restricted.
     */
    @Test
    public void predicateSecuredLoadSkipsParityCheck() {
        System.setProperty("mondrian.calcite.parityCheck", "true");
        // strict OFF: if the guard wrongly RAN the legacy comparison it would
        // disagree (legacy returns the unrestricted 180, Calcite the secured
        // 150) and record a divergence — which is exactly what we assert
        // must NOT happen.
        Connection t1 = securedConnection("1");
        try {
            long secured = total(t1);
            assertEquals(150L, secured,
                "tenant 1 secured total = 100+50 (row-restricted)");
            long divergences = sumDivergence();
            assertEquals(0L, divergences,
                "a predicate-secured load must SKIP the parity check "
                + "(running the legacy comparison would leak rows); "
                + "no divergence may be recorded");
        } finally {
            t1.close();
        }
    }

    /**
     * #90/#119 KEY skip-proof: a measure-level distinct-grain load run with
     * BOTH parityCheck AND strict ON must (a) NOT throw, (b) record ZERO
     * divergences, and (c) still return the CORRECT de-duped value (450, not
     * the legacy-fanned 850/950). This proves the Calcite-only-correct
     * aggregation is safely SKIPPED by {@code isCalciteOnlyAggregation}, never
     * mis-flagged as a divergence. Were the guard to run the legacy SUM
     * comparison it would see legacy's 950 vs Calcite's 450 → a FALSE
     * divergence → strict mode would THROW. So a clean run is the proof.
     */
    @Test
    public void distinctGrainLoadSkipsParityCheckInStrictMode() {
        System.setProperty("mondrian.calcite.parityCheck", "true");
        System.setProperty("mondrian.calcite.parityCheck.strict", "true");
        Connection conn = distinctGrainConnection();
        try {
            Double v = distinctAmount(conn);
            // (a) did not throw — reaching here is half the proof.
            // (c) correct de-duped value, NOT the legacy-fanned over-count.
            assertEquals(450.0, v, 0.001,
                "distinct grain must return the CORRECT de-duped 450 "
                + "(legacy would have fanned to 950)");
            // (b) zero divergences recorded — the Calcite-only-correct
            // aggregation was skipped, not silently mis-flagged.
            long divergences = sumDivergence();
            assertEquals(0L, divergences,
                "a Calcite-only-correct distinct-grain load must SKIP the "
                + "parity comparison (legacy SUM ignores the de-dup and runs, "
                + "returning a different-by-design number); no FALSE "
                + "divergence may be recorded and strict mode must NOT throw");
        } finally {
            conn.close();
        }
    }

    /**
     * #90/#104 analogue: a non-additive median load under parityCheck strict
     * must also skip — median's legacy SQL differs from Calcite's
     * PERCENTILE_CONT pushdown, so comparing would false-trip the guard. We
     * assert no throw and zero divergence (the value itself is exercised by
     * PercentileH2EndToEndTest).
     */
    @Test
    public void medianLoadSkipsParityCheckInStrictMode() {
        System.setProperty("mondrian.calcite.parityCheck", "true");
        System.setProperty("mondrian.calcite.parityCheck.strict", "true");
        Connection conn = distinctGrainConnection();
        try {
            Query q = conn.parseQuery(
                "SELECT {[Measures].[Median Amount]} ON COLUMNS FROM [Sales]");
            Result r = conn.execute(q);
            r.close();
            long divergences = sumDivergence();
            assertEquals(0L, divergences,
                "a non-additive median load must SKIP the parity comparison "
                + "(#104); strict mode must NOT throw and no divergence may be "
                + "recorded");
        } finally {
            conn.close();
        }
    }

    // ---------- helpers ----------

    private static Connection distinctGrainConnection() {
        Util.PropertyList props = new Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), DG_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(),
            "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), DG_SCHEMA);
        return DriverManager.getConnection(props, null, null);
    }

    private static Double distinctAmount(Connection conn) {
        Query q = conn.parseQuery(
            "SELECT {[Measures].[Distinct Amount]} ON COLUMNS FROM [Sales]");
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Connection foodMartConnection() throws Exception {
        String catalog = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("demo/FoodMart.mondrian.xml")));
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    private static Connection securedConnection(String tenant) {
        Util.PropertyList props = new Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(),
            "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(),
            SECURED_SCHEMA);
        props.put(RolapConnectionProperties.Role.name(), "Tenant");
        props.put("session.tenant", tenant);
        return DriverManager.getConnection(props, null, null);
    }

    private static Long total(Connection conn) {
        Query q = conn.parseQuery(
            "SELECT {[Measures].[Amount]} ON COLUMNS FROM [Sales]");
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).longValue();
    }

    private long sumDivergence() {
        Collection<MetricData> metrics = reader.collectAllMetrics();
        long total = 0;
        for (MetricData m : metrics) {
            if ("mondrian.calcite.divergence".equals(m.getName())) {
                for (LongPointData p : m.getLongSumData().getPoints()) {
                    total += p.getValue();
                }
            }
        }
        return total;
    }
}

// End CalciteParityCheckEndToEndTest.java
