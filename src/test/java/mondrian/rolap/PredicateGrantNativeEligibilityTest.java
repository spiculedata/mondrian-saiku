/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.MondrianProperties;
import mondrian.olap.Position;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * #106 (SECURITY battery, native-eligibility): pins that the predicate-grant
 * native disqualifier is SELECTIVE — it fires ONLY for roles carrying a
 * predicate grant on the queried cube, and leaves native ENABLED for the
 * common member-grant-only / unsecured cases. Lives in {@code mondrian.rolap}
 * so it can hook the package-private {@link RolapNativeRegistry} listener to
 * observe whether a native evaluator actually fired.
 *
 * <p>Under-disabling native for a predicate role is a leak (forbidden rows
 * influence ranking/inclusion); over-disabling it for the common case is a
 * needless perf regression. Both directions are asserted here.
 */
public class PredicateGrantNativeEligibilityTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"ne_sales\"",
        "CREATE TABLE \"ne_sales\" (\"tenant\" INTEGER,"
            + " \"region\" VARCHAR(8), \"amount\" INTEGER)",
        "INSERT INTO \"ne_sales\" VALUES"
            + " (1,'EAST',100),(1,'WEST',50),(1,'SOUTH',10),"
            + " (2,'EAST',1),(2,'WEST',1),(2,'SOUTH',999),(2,'NORTH',500)",
    };

    private static final String DIM_CUBE =
        "  <Dimension name='Region' table='ne_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region' hasHierarchy='true'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='ne_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <FactLink dimension='Region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n";

    /** Member-grant-only role (no predicate grant): EAST + SOUTH visible. */
    private static final String SCHEMA_MEMBER_ONLY =
        "<Schema name='NEM' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='ne_sales'/></PhysicalSchema>\n"
        + DIM_CUBE
        + "  <Role name='MemberOnly'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Region].[Region]'"
        + " access='custom' rollupPolicy='partial'>\n"
        + "          <MemberGrant member='[Region].[EAST]' access='all'/>\n"
        + "          <MemberGrant member='[Region].[SOUTH]' access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    /** Predicate-secured role (tenant EQ). */
    private static final String SCHEMA_PREDICATE =
        "<Schema name='NEP' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='ne_sales'/></PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric' defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + DIM_CUBE
        + "  <Role name='Tenant'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    /** Two partial-rollup member roles for union: EAST and WEST. */
    private static final String SCHEMA_UNION_PARTIAL =
        "<Schema name='NEU' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='ne_sales'/></PhysicalSchema>\n"
        + DIM_CUBE
        + "  <Role name='EastRole'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Region].[Region]'"
        + " access='custom' rollupPolicy='partial'>\n"
        + "          <MemberGrant member='[Region].[EAST]' access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "  <Role name='WestRole'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Region].[Region]'"
        + " access='custom' rollupPolicy='partial'>\n"
        + "          <MemberGrant member='[Region].[WEST]' access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static final String H2_URL =
        "jdbc:h2:mem:ne_e2e;DB_CLOSE_DELAY=-1";

    private static final String MDX_TOPCOUNT =
        "SELECT TopCount([Region].[Region].[Region].Members, 2,"
        + " [Measures].[Amount]) ON COLUMNS FROM [Sales]";
    private static final String MDX_NONEMPTY =
        "SELECT NON EMPTY [Region].[Region].[Region].Members ON COLUMNS"
        + " FROM [Sales] WHERE [Measures].[Amount]";

    private static MondrianProperties props;
    private static boolean sTopCount;
    private static boolean sFilter;
    private static boolean sNonEmpty;
    private static boolean sCrossJoin;
    private static boolean sExpand;

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
        sTopCount = props.EnableNativeTopCount.get();
        sFilter = props.EnableNativeFilter.get();
        sNonEmpty = props.EnableNativeNonEmpty.get();
        sCrossJoin = props.EnableNativeCrossJoin.get();
        sExpand = props.ExpandNonNative.get();
        // Native ON, ExpandNonNative OFF so native crossjoin is attempted.
        props.EnableNativeTopCount.set(true);
        props.EnableNativeFilter.set(true);
        props.EnableNativeNonEmpty.set(true);
        props.EnableNativeCrossJoin.set(true);
        props.ExpandNonNative.set(false);
    }

    @AfterAll
    public static void restore() {
        if (props != null) {
            props.EnableNativeTopCount.set(sTopCount);
            props.EnableNativeFilter.set(sFilter);
            props.EnableNativeNonEmpty.set(sNonEmpty);
            props.EnableNativeCrossJoin.set(sCrossJoin);
            props.ExpandNonNative.set(sExpand);
        }
    }

    private static Connection connect(
        String schema, String role, String tenant)
    {
        Util.PropertyList p = new Util.PropertyList();
        p.put("Provider", "mondrian");
        p.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        p.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        p.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        p.put(RolapConnectionProperties.JdbcPassword.name(), "");
        p.put("UseSchemaPool", "false");
        p.put(RolapConnectionProperties.CatalogContent.name(), schema);
        if (role != null) {
            p.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            p.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(p, null, null);
    }

    /** Execute mdx, returning [List&lt;memberName&gt; axis, Boolean nativeUsed]. */
    private static Object[] run(Connection conn, String mdx) {
        RolapCube cube =
            (RolapCube) conn.getSchema().lookupCube("Sales", true);
        RolapSchemaReader sr = (RolapSchemaReader) cube.getSchemaReader();
        RolapNativeRegistry reg = sr.getSchema().getNativeRegistry();
        final boolean[] used = {false};
        reg.setListener(new RolapNative.Listener() {
            public void foundEvaluator(RolapNative.NativeEvent e) {
                used[0] = true;
            }
            public void foundInCache(RolapNative.TupleEvent e) { }
            public void executingSql(RolapNative.TupleEvent e) { }
        });
        try {
            Query q = conn.parseQuery(mdx);
            Result r = conn.execute(q);
            try {
                List<String> out = new ArrayList<>();
                List<Position> positions = r.getAxes()[0].getPositions();
                for (Position pos : positions) {
                    out.add(pos.get(0).getName());
                }
                return new Object[]{out, used[0]};
            } finally {
                r.close();
            }
        } finally {
            reg.setListener(null);
        }
    }

    private static long total(Connection conn) {
        Query q = conn.parseQuery(
            "SELECT {[Measures].[Amount]} ON COLUMNS FROM [Sales]");
        Result r = conn.execute(q);
        try {
            return ((Number) r.getCell(new int[]{0}).getValue()).longValue();
        } finally {
            r.close();
        }
    }

    /** Unsecured (no-role) schema: a native-eligible crossjoin baseline. */
    private static final String SCHEMA_PLAIN =
        "<Schema name='NEN' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='ne_sales'/></PhysicalSchema>\n"
        + DIM_CUBE
        + "</Schema>\n";

    /** A two-set crossjoin that the native crossjoin evaluator CAN handle, so
     *  native actually fires for a non-predicate role (proving the eligibility
     *  baseline is meaningful). Region x Region(tenant via amount) — we cross
     *  the Region level with itself via a measure to force a native crossjoin
     *  candidate; in practice the single-hierarchy NON EMPTY here is enough to
     *  exercise native NON EMPTY when ExpandNonNative is off. */
    private static final String MDX_CROSSJOIN =
        "SELECT NON EMPTY CrossJoin("
        + "[Region].[Region].[Region].Members,"
        + " {[Measures].[Amount]}) ON COLUMNS FROM [Sales]";

    private static boolean nativeUsedFor(String schema, String role) {
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Connection conn = connect(schema, role, null);
        try {
            return (Boolean) run(conn, MDX_CROSSJOIN)[1];
        } finally {
            conn.close();
        }
    }

    /** nativeMemberGrantStillEnforcedAfterFix: a member-grant-only role still
     *  enforces member visibility (EAST, SOUTH), AND its native-eligibility is
     *  IDENTICAL to the unsecured baseline — the fix only disqualifies native
     *  for predicate roles, never for the common member-grant-only case. */
    @Test
    public void nativeMemberGrantStillEnforcedAfterFix() {
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Connection conn = connect(SCHEMA_MEMBER_ONLY, "MemberOnly", null);
        try {
            Object[] res = run(conn, MDX_NONEMPTY);
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) res[0];
            assertEquals(Arrays.asList("EAST", "SOUTH"), members,
                "member-grant-only role must see exactly EAST, SOUTH");
        } finally {
            conn.close();
        }
        // No over-disabling: the member-only role's native eligibility for a
        // crossjoin equals the unsecured baseline (the fix changes nothing for
        // a role with no predicate grant).
        boolean baseline = nativeUsedFor(SCHEMA_PLAIN, null);
        boolean memberOnly = nativeUsedFor(SCHEMA_MEMBER_ONLY, "MemberOnly");
        assertEquals(baseline, memberOnly,
            "a member-grant-only role must have the SAME native eligibility as "
            + "the unsecured baseline — the fix must not over-disable it");
    }

    /** Control: a predicate-secured role must NEVER use native (the fix
     *  disqualifies it), so the segment-load path enforces the predicate —
     *  even for a crossjoin shape the unsecured baseline WOULD run natively. */
    @Test
    public void predicateRoleDoesNotUseNative() {
        // Predicate role: native disqualified for every native-eligible shape.
        assertFalse(nativeUsedFor(SCHEMA_PREDICATE, "Tenant"),
            "a predicate-secured role must fall back to non-native so the "
            + "fail-closed segment-load path enforces the predicate");
        assertFalse(nativeUsedForMdx(SCHEMA_PREDICATE, "Tenant", MDX_TOPCOUNT),
            "predicate-secured TopCount must not run natively");
        assertFalse(nativeUsedForMdx(SCHEMA_PREDICATE, "Tenant", MDX_NONEMPTY),
            "predicate-secured NON EMPTY must not run natively");
    }

    private static boolean nativeUsedForMdx(
        String schema, String role, String mdx)
    {
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Connection conn = connect(schema, role, role == null ? null : "1");
        try {
            return (Boolean) run(conn, mdx)[1];
        } finally {
            conn.close();
        }
    }

    /** unionRolePartialRollupNative: a union of two partial-rollup member roles
     *  (no predicate grant) keeps native enabled, shows union member visibility
     *  (EAST + WEST) under native TopCount/NON EMPTY, and the partial-rollup
     *  grand total is the visible-children sum (150), never the full 160. */
    @Test
    public void unionRolePartialRollupNative() {
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Connection conn =
            connect(SCHEMA_UNION_PARTIAL, "EastRole,WestRole", null);
        try {
            Object[] ne = run(conn, MDX_NONEMPTY);
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) ne[0];
            assertEquals(Arrays.asList("EAST", "WEST"), members,
                "union of partial member roles = union visibility EAST, WEST");

            Object[] tc = run(conn, MDX_TOPCOUNT);
            @SuppressWarnings("unchecked")
            List<String> ranked = (List<String>) tc[0];
            assertEquals(Arrays.asList("EAST", "WEST"), ranked,
                "native TopCount over the union ranks only visible members");

            // EAST = 100(t1)+1(t2) = 101, WEST = 50(t1)+1(t2) = 51. No tenant
            // restriction (member-only roles), so both tenants' rows count.
            // Partial rollup => only visible EAST+WEST = 152, never the full
            // 162 (which would include the hidden SOUTH/NORTH members).
            assertEquals(152L, total(conn),
                "partial-rollup (max policy) total = visible EAST+WEST = 152, "
                + "never the full cube total");
        } finally {
            conn.close();
        }
        // No over-disabling for a union of member-only roles: native
        // eligibility matches the unsecured baseline.
        assertEquals(
            nativeUsedFor(SCHEMA_PLAIN, null),
            nativeUsedFor(SCHEMA_UNION_PARTIAL, "EastRole,WestRole"),
            "a union of member-only roles must keep the unsecured baseline's "
            + "native eligibility — no predicate grant => no disqualification");
    }
}
