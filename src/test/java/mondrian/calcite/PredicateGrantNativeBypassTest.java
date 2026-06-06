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
import mondrian.olap.MondrianProperties;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #106 (SECURITY, TDD): the native set evaluators
 * ({@code RolapNativeTopCount}, {@code RolapNativeFilter},
 * {@code RolapNativeCrossJoin} / native NON EMPTY) build their OWN SQL and
 * historically applied member-grant IN-lists but NEVER the
 * {@code <PredicateGrant>} row-security filter. With native enabled, a
 * predicate-secured measure group would therefore rank / filter / non-empty
 * prune using UNFILTERED fact rows — forbidden rows influencing which allowed
 * members appear and in what order: a row-level disclosure.
 *
 * <p>The fix makes a native evaluation fall back to the non-native path (which
 * routes through the fail-closed Calcite segment-load injection that DOES
 * enforce the predicate) whenever the active role carries a predicate grant
 * touching the queried cube. This test pins that: every native query returns
 * IDENTICAL results to the same query with native disabled.
 *
 * <p>RED before the fix (native leaks tenant-2 influence); GREEN after.
 */
public class PredicateGrantNativeBypassTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"nb_sales\"",
        // Two tenants. Crucially, tenant 2's amounts are LARGER than tenant 1's
        // for some regions, so if tenant-2 rows leak into a TopCount ranking or
        // a Filter(>threshold) the ALLOWED (tenant-1) member set / order changes
        // observably. Region NORTH exists ONLY for tenant 2: it must never
        // appear for a tenant-1 restricted role, and a native NON EMPTY that
        // ignored the predicate would surface it.
        "CREATE TABLE \"nb_sales\" (\"tenant\" INTEGER,"
            + " \"region\" VARCHAR(8), \"amount\" INTEGER)",
        "INSERT INTO \"nb_sales\" VALUES"
            // tenant 1: EAST=100, WEST=50, SOUTH=10
            + " (1,'EAST',100),(1,'WEST',50),(1,'SOUTH',10),"
            // tenant 2: EAST=1, WEST=1, SOUTH=999, NORTH=500
            + " (2,'EAST',1),(2,'WEST',1),(2,'SOUTH',999),(2,'NORTH',500)",
    };

    private static final String SCHEMA =
        "<Schema name='NB' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='nb_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric'"
        + " defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='nb_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region' hasHierarchy='true'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='nb_sales'>\n"
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

    private static final String H2_URL =
        "jdbc:h2:mem:nb_e2e;DB_CLOSE_DELAY=-1";

    // TopCount(2) over Region by Amount. For tenant 1 the correct top-2 is
    // EAST(100), WEST(50). If tenant-2 rows leak, SOUTH would rank with 10+999
    // and NORTH(500) could appear — the ranking/membership would differ.
    private static final String MDX_TOPCOUNT =
        "SELECT TopCount([Region].[Region].[Region].Members, 2,"
        + " [Measures].[Amount]) ON COLUMNS FROM [Sales]";

    // Filter regions whose Amount > 40. tenant 1: EAST(100), WEST(50). If
    // tenant-2 leaks, SOUTH (10 -> 1009) and NORTH would pass too.
    private static final String MDX_FILTER =
        "SELECT Filter([Region].[Region].[Region].Members,"
        + " [Measures].[Amount] > 40) ON COLUMNS FROM [Sales]";

    // NON EMPTY crossjoin: which regions are non-empty for tenant 1. tenant 1
    // has EAST, WEST, SOUTH (no NORTH). A native NON EMPTY ignoring the
    // predicate would also surface NORTH (tenant-2-only).
    private static final String MDX_NONEMPTY =
        "SELECT NON EMPTY [Region].[Region].[Region].Members ON COLUMNS"
        + " FROM [Sales] WHERE [Measures].[Amount]";

    private static MondrianProperties props;
    private static boolean savedTopCount;
    private static boolean savedFilter;
    private static boolean savedNonEmpty;
    private static boolean savedCrossJoin;
    private static boolean savedExpandNonNative;

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
        props = MondrianProperties.instance();
        savedTopCount = props.EnableNativeTopCount.get();
        savedFilter = props.EnableNativeFilter.get();
        savedNonEmpty = props.EnableNativeNonEmpty.get();
        savedCrossJoin = props.EnableNativeCrossJoin.get();
        savedExpandNonNative = props.ExpandNonNative.get();
    }

    @AfterAll
    public static void restore() {
        if (props != null) {
            props.EnableNativeTopCount.set(savedTopCount);
            props.EnableNativeFilter.set(savedFilter);
            props.EnableNativeNonEmpty.set(savedNonEmpty);
            props.EnableNativeCrossJoin.set(savedCrossJoin);
            props.ExpandNonNative.set(savedExpandNonNative);
        }
    }

    private static void setNative(boolean on) {
        props.EnableNativeTopCount.set(on);
        props.EnableNativeFilter.set(on);
        props.EnableNativeNonEmpty.set(on);
        props.EnableNativeCrossJoin.set(on);
        props.ExpandNonNative.set(on);
    }

    private static Connection connect(String role, String tenant) {
        Util.PropertyList p = new Util.PropertyList();
        p.put("Provider", "mondrian");
        p.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        p.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        p.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        p.put(RolapConnectionProperties.JdbcPassword.name(), "");
        p.put("UseSchemaPool", "false");
        p.put(RolapConnectionProperties.CatalogContent.name(), SCHEMA);
        if (role != null) {
            p.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            p.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(p, null, null);
    }

    /** The column-axis members and their values, as a stable "REGION=amount"
     *  list, so we compare BOTH membership/order AND the per-member value. */
    private static List<String> axis(String role, String tenant, String mdx) {
        Connection conn = connect(role, tenant);
        try {
            Query q = conn.parseQuery(mdx);
            Result r = conn.execute(q);
            try {
                List<String> out = new ArrayList<>();
                List<mondrian.olap.Position> positions =
                    r.getAxes()[0].getPositions();
                for (int i = 0; i < positions.size(); i++) {
                    mondrian.olap.Position pos = positions.get(i);
                    String member = pos.get(0).getName();
                    Object v = r.getCell(new int[]{i}).getValue();
                    out.add(member + "="
                        + (v == null ? "null" : ((Number) v).longValue()));
                }
                return out;
            } finally {
                r.close();
            }
        } finally {
            conn.close();
        }
    }

    /** TopCount: native must match non-native (tenant-1 ranking only). */
    @Test
    public void nativeTopCountNotBypassed() {
        setNative(false);
        List<String> nonNative = axis("Tenant", "1", MDX_TOPCOUNT);
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        setNative(true);
        List<String> nativeResult = axis("Tenant", "1", MDX_TOPCOUNT);
        assertEquals(nonNative, nativeResult,
            "native TopCount must NOT rank using forbidden tenant-2 rows");
    }

    /** Filter: native must match non-native (tenant-1 inclusion only). */
    @Test
    public void nativeFilterNotBypassed() {
        setNative(false);
        List<String> nonNative = axis("Tenant", "1", MDX_FILTER);
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        setNative(true);
        List<String> nativeResult = axis("Tenant", "1", MDX_FILTER);
        assertEquals(nonNative, nativeResult,
            "native Filter must NOT include members based on tenant-2 rows");
    }

    /** NON EMPTY: native must match non-native (tenant-1 non-emptiness only). */
    @Test
    public void nativeNonEmptyNotBypassed() {
        setNative(false);
        List<String> nonNative = axis("Tenant", "1", MDX_NONEMPTY);
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        setNative(true);
        List<String> nativeResult = axis("Tenant", "1", MDX_NONEMPTY);
        assertEquals(nonNative, nativeResult,
            "native NON EMPTY must NOT surface tenant-2-only members");
    }

    /** Sanity: the correct (non-native) tenant-1 answers are what we expect,
     *  so the equality assertions above pin the RIGHT target, not garbage. */
    @Test
    public void nonNativeBaselineIsTenantOneOnly() {
        setNative(false);
        assertEquals(
            java.util.Arrays.asList("EAST=100", "WEST=50"),
            axis("Tenant", "1", MDX_TOPCOUNT),
            "tenant-1 TopCount(2) = EAST(100), WEST(50)");
        assertEquals(
            java.util.Arrays.asList("EAST=100", "WEST=50"),
            axis("Tenant", "1", MDX_FILTER),
            "tenant-1 Filter(>40) = EAST, WEST");
    }
}
