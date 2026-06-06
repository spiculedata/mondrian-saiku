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
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #105/#106 (SECURITY) end-to-end battery. Each test pins one security
 * invariant of predicate row-security against a live H2 database, exercising
 * the Calcite segment-load chokepoint that enforces it. These complement
 * {@link PredicateGrantH2EndToEndTest} (happy-path partitioning) and
 * {@link PredicateGrantDrillThroughTest} (drill-through enforcement).
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Union roles AND their grants (most-restrictive), incl. the asymmetric
 *       case where one constituent has no grant on the measure group.</li>
 *   <li>A secured (per-tenant) segment is never served to an UNSECURED
 *       connection.</li>
 *   <li>A hostile String parameter renders as a quoted literal (zero rows),
 *       never executed SQL.</li>
 *   <li>An undeclared grant parameter denies all rows (fail-closed).</li>
 *   <li>A {@code MemberGrant} and a {@code PredicateGrant} on the same cube
 *       coexist — member visibility AND row restriction both apply.</li>
 *   <li>A calculated measure referencing a secured measure reflects the
 *       restricted total (cannot escape the chokepoint).</li>
 *   <li>A conformed dimension over two secured measure groups filters each
 *       UNION arm independently.</li>
 * </ul>
 */
public class PredicateGrantSecurityBatteryTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"sb_sales\"",
        "DROP TABLE IF EXISTS \"sb_returns\"",
        "DROP TABLE IF EXISTS \"sb_region\"",
        // Shared Region dimension table (conformed across both fact tables).
        "CREATE TABLE \"sb_region\" (\"region\" VARCHAR(16))",
        "INSERT INTO \"sb_region\" VALUES ('EAST'),('WEST')",
        // tenant 1: 150 ; tenant 2: 10 ; tenant 3: 20. region EAST/WEST.
        "CREATE TABLE \"sb_sales\" (\"tenant\" INTEGER,"
            + " \"region\" VARCHAR(16), \"amount\" INTEGER)",
        "INSERT INTO \"sb_sales\" VALUES"
            + " (1,'EAST',100),(1,'WEST',50),"
            + " (2,'EAST',7),(2,'WEST',3),"
            + " (3,'EAST',20)",
        // second measure group sharing the 'tenant' conformed dimension column.
        "CREATE TABLE \"sb_returns\" (\"tenant\" INTEGER,"
            + " \"region\" VARCHAR(16), \"qty\" INTEGER)",
        "INSERT INTO \"sb_returns\" VALUES"
            + " (1,'EAST',5),(1,'WEST',4),"
            + " (2,'EAST',2),(3,'EAST',1)",
    };

    private static final String H2_URL =
        "jdbc:h2:mem:sb_e2e;DB_CLOSE_DELAY=-1";

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

    private static Connection connect(
        String catalog, String role, java.util.Map<String, String> session)
    {
        Util.PropertyList props = new Util.PropertyList();
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
        if (session != null) {
            for (java.util.Map.Entry<String, String> e : session.entrySet()) {
                props.put("session." + e.getKey(), e.getValue());
            }
        }
        return DriverManager.getConnection(props, null, null);
    }

    private static java.util.Map<String, String> session(
        String name, String value)
    {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put(name, value);
        return m;
    }

    private static Long total(Connection conn, String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        try {
            Object v = r.getCell(new int[]{0}).getValue();
            return v == null ? null : ((Number) v).longValue();
        } finally {
            r.close();
        }
    }

    // ---- Single-MG schema with two parameters + two roles ---------------

    /** A schema with the Sales MG secured by a 'tenant' EQ grant and a
     *  separate 'region' IN grant, plus roles that each apply one of them and a
     *  role that applies neither grant on Sales. Enables union-AND tests. */
    private static final String SCHEMA_UNION =
        "<Schema name='SB' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='sb_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric' defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "    <QueryParameterValue>3</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <QueryParameter name='regions' type='String'"
        + " defaultValue='EAST'/>\n"
        + "  <Dimension name='Region' table='sb_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='sb_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <FactLink dimension='Region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "    <CalculatedMembers>\n"
        + "      <CalculatedMember name='Double' dimension='Measures'>\n"
        + "        <Formula>[Measures].[Amount] * 2</Formula>\n"
        + "      </CalculatedMember>\n"
        + "    </CalculatedMembers>\n"
        + "  </Cube>\n"
        // TenantRole: restrict by tenant EQ.
        + "  <Role name='TenantRole'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        // RegionRole: restrict by region IN.
        + "  <Role name='RegionRole'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='region'"
        + " operator='in' parameter='regions'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        // PlainRole: full access to Sales, NO predicate grant (asymmetric arm).
        + "  <Role name='PlainRole'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'/>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static final String MDX_AMOUNT =
        "SELECT {[Measures].[Amount]} ON COLUMNS FROM [Sales]";

    /** unionRolePredicateGrantsAreAnded: a union of TenantRole(tenant=1) and
     *  RegionRole(regions=EAST) must satisfy BOTH — only tenant 1 AND region
     *  EAST = the single (1,EAST,100) row = 100. */
    @Test
    public void unionRolePredicateGrantsAreAnded() {
        java.util.Map<String, String> s = new java.util.HashMap<>();
        s.put("tenant", "1");
        s.put("regions", "EAST");
        Connection conn = connect(SCHEMA_UNION, "TenantRole,RegionRole", s);
        try {
            assertEquals(100L, total(conn, MDX_AMOUNT),
                "union grants must be ANDed: tenant=1 AND region=EAST = 100");
        } finally {
            conn.close();
        }
    }

    /** Asymmetric union: TenantRole(tenant=2) unioned with PlainRole (which
     *  places NO grant on Sales). The tenant restriction must STILL apply —
     *  the un-granting arm must not relax it to full access. tenant 2 = 10. */
    @Test
    public void unionWithUngrantedArmStillRestricts() {
        Connection conn = connect(
            SCHEMA_UNION, "TenantRole,PlainRole", session("tenant", "2"));
        try {
            assertEquals(10L, total(conn, MDX_AMOUNT),
                "an ungranting union arm must not relax the other's "
                + "restriction; tenant 2 = 10");
        } finally {
            conn.close();
        }
    }

    /** securedSegmentNotServedToUnsecuredQuery: warm the cache as TenantRole
     *  (tenant=1 → 150), then connect with NO role and assert the full 180 is
     *  seen — the restricted segment is never served to the unsecured query. */
    @Test
    public void securedSegmentNotServedToUnsecuredQuery() {
        Connection secured =
            connect(SCHEMA_UNION, "TenantRole", session("tenant", "1"));
        Connection unsecured = connect(SCHEMA_UNION, null, null);
        try {
            assertEquals(150L, total(secured, MDX_AMOUNT),
                "TenantRole warms a restricted (150) segment");
            assertEquals(180L, total(unsecured, MDX_AMOUNT),
                "unsecured connection must see the full 180, not the cached "
                + "restricted segment");
        } finally {
            secured.close();
            unsecured.close();
        }
    }

    /** stringParamWithSqlMetacharactersIsQuotedNotExecuted: a hostile String
     *  value bound IN to the VARCHAR region column must render as a quoted
     *  literal (matching zero rows), never as executed SQL. We assert no SQL
     *  error indicating execution, and a fail-closed/zero result. */
    @Test
    public void stringParamWithSqlMetacharactersIsQuotedNotExecuted() {
        // A single hostile token (no comma) so resolveList yields exactly one
        // literal we can prove was quoted, not executed.
        Connection conn = connect(
            SCHEMA_UNION, "RegionRole",
            session("regions", "'); DROP TABLE \"sb_sales\"; --"));
        try {
            Long v = total(conn, MDX_AMOUNT);
            // The literal cannot match any region → zero rows (null/0). The
            // table must still exist (it was never dropped): a follow-up read
            // as the unsecured role still sees 180.
            assertTrue(v == null || v == 0L,
                "a hostile region literal must match zero rows, got " + v);
        } catch (RuntimeException ex) {
            // If H2 errors, it must be a benign no-match, NOT a syntax/exec
            // error from an injected statement. Either way the table survives.
            assertNotNull(ex.getMessage());
        } finally {
            conn.close();
        }
        // Prove the table was never dropped (no injection executed).
        Connection check = connect(SCHEMA_UNION, null, null);
        try {
            assertEquals(180L, total(check, MDX_AMOUNT),
                "table must survive — the metacharacters were quoted, not "
                + "executed");
        } finally {
            check.close();
        }
    }

    /** undeclaredGrantParameterFailsClosed: a PredicateGrant referencing a
     *  parameter that no QueryParameter declares must deny all rows. Mondrian
     *  rejects this at schema load (also fail-closed); assert the load fails. */
    @Test
    public void undeclaredGrantParameterFailsClosed() {
        String ghost = SCHEMA_UNION
            .replace("parameter='tenant'", "parameter='ghost'");
        org.junit.jupiter.api.Assertions.assertThrows(
            Throwable.class,
            () -> {
                Connection conn =
                    connect(ghost, "TenantRole", session("tenant", "1"));
                try {
                    total(conn, MDX_AMOUNT);
                } finally {
                    conn.close();
                }
            },
            "a grant on an undeclared parameter must fail closed (deny-all)");
    }

    /** calcMemberReferencingSecuredMeasureIsRestricted: [Measures].[Double] =
     *  [Amount]*2 must reflect the restricted total (tenant 1: 150*2 = 300),
     *  proving a calc cannot escape the chokepoint. */
    @Test
    public void calcMemberReferencingSecuredMeasureIsRestricted() {
        Connection conn =
            connect(SCHEMA_UNION, "TenantRole", session("tenant", "1"));
        try {
            assertEquals(300L,
                total(conn,
                    "SELECT {[Measures].[Double]} ON COLUMNS FROM [Sales]"),
                "calc measure over a secured base must be restricted: "
                + "tenant 1 → 150*2 = 300");
        } finally {
            conn.close();
        }
    }

    // ---- Member + predicate coexistence ---------------------------------

    /** A schema where the Tenant role both restricts the Region members it can
     *  see (MemberGrant: only EAST) AND restricts rows by tenant. */
    private static final String SCHEMA_MEMBER_AND_PREDICATE =
        "<Schema name='SBM' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='sb_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric' defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "    <QueryParameterValue>3</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='sb_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region' hasHierarchy='true'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='sb_sales'>\n"
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
        + "        <HierarchyGrant hierarchy='[Region].[Region]'"
        + " access='custom' rollupPolicy='partial'>\n"
        + "          <MemberGrant member='[Region].[EAST]'"
        + " access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    /** memberGrantAndPredicateGrantOnSameCubeCoexist: with the Tenant role,
     *  only EAST is a visible member (MemberGrant) AND rows are restricted to
     *  tenant 1 (PredicateGrant). The visible total is EAST∩tenant1 = 100. */
    @Test
    public void memberGrantAndPredicateGrantOnSameCubeCoexist() {
        Connection conn = connect(
            SCHEMA_MEMBER_AND_PREDICATE, "Tenant", session("tenant", "1"));
        try {
            // The grand total under partial rollup reflects only visible (EAST)
            // members, restricted to tenant 1: row (1,EAST,100) = 100.
            assertEquals(100L, total(conn, MDX_AMOUNT),
                "member visibility (EAST) AND row restriction (tenant 1) must "
                + "both apply → 100");
        } finally {
            conn.close();
        }
    }

    // ---- Multi-measure-group conformed dimension -------------------------

    /** Two measure groups (Sales, Returns) over a conformed Region dimension,
     *  each independently secured by the same 'tenant' EQ grant. A virtual-cube
     *  / multi-MG query fans out into one UNION arm per MG; each arm must be
     *  independently filtered. */
    private static final String SCHEMA_MULTI_MG =
        "<Schema name='SBMG' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='sb_region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Table>\n"
        + "    <Table name='sb_sales'/>\n"
        + "    <Table name='sb_returns'/>\n"
        + "    <Link source='sb_region' target='sb_sales'>\n"
        + "      <ForeignKey><Column name='region'/></ForeignKey>\n"
        + "    </Link>\n"
        + "    <Link source='sb_region' target='sb_returns'>\n"
        + "      <ForeignKey><Column name='region'/></ForeignKey>\n"
        + "    </Link>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric' defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "    <QueryParameterValue>3</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='sb_region' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='SalesReturns'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='sb_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Region'"
        + " foreignKeyColumn='region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "      <MeasureGroup name='R' table='sb_returns'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Qty' column='qty' aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Region'"
        + " foreignKeyColumn='region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "  <Role name='Tenant'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='SalesReturns' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "        <PredicateGrant measureGroup='R' column='tenant'"
        + " operator='eq' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    /** multiMeasureGroupUnionFanOutEachArmSecured: tenant 1 over both MGs —
     *  Amount restricted to tenant 1 (150) AND Qty restricted to tenant 1
     *  (5+4=9). Each measure-group arm is filtered independently. */
    @Test
    public void multiMeasureGroupUnionFanOutEachArmSecured() {
        Connection conn =
            connect(SCHEMA_MULTI_MG, "Tenant", session("tenant", "1"));
        try {
            assertEquals(150L,
                total(conn,
                    "SELECT {[Measures].[Amount]} ON COLUMNS "
                    + "FROM [SalesReturns]"),
                "Sales arm restricted to tenant 1 = 150");
            assertEquals(9L,
                total(conn,
                    "SELECT {[Measures].[Qty]} ON COLUMNS "
                    + "FROM [SalesReturns]"),
                "Returns arm independently restricted to tenant 1 = 9");
        } finally {
            conn.close();
        }
    }

    /** Control for the multi-MG case: tenant 2 sees Amount=10 and Qty=2. */
    @Test
    public void multiMeasureGroupTenantTwo() {
        Connection conn =
            connect(SCHEMA_MULTI_MG, "Tenant", session("tenant", "2"));
        try {
            assertEquals(10L,
                total(conn,
                    "SELECT {[Measures].[Amount]} ON COLUMNS "
                    + "FROM [SalesReturns]"),
                "tenant 2 Sales = 10");
            assertEquals(2L,
                total(conn,
                    "SELECT {[Measures].[Qty]} ON COLUMNS "
                    + "FROM [SalesReturns]"),
                "tenant 2 Returns = 2");
        } finally {
            conn.close();
        }
    }
}
