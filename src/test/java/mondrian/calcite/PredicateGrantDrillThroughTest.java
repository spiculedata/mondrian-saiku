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
import mondrian.rolap.RolapCell;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #106 (TDD, SECURITY): DRILLTHROUGH must respect predicate-based row
 * security. The aggregate segment-load path injects the role's predicate
 * grant as a pre-aggregation WHERE on the real fact column; DRILLTHROUGH goes
 * through a SEPARATE legacy path
 * ({@code RolapCell.getDrillThroughSQL} →
 * {@code AggregationManager.getDrillThroughSql} →
 * {@code DrillThroughQuerySpec}) that historically had NO predicate awareness.
 * A user with a row-security role who drills through therefore used to receive
 * RAW FACT ROWS FOR EVERY TENANT — a confirmed data leak.
 *
 * <p>These tests connect as a {@code Tenant} role bound to a single tenant,
 * generate the drillthrough SQL for the cell, execute it against the same H2
 * database, and assert the returned rows are restricted to that tenant. The
 * fail-closed test asserts an undeclared/unbound grant parameter denies all
 * rows rather than emitting an unfiltered scan.
 */
public class PredicateGrantDrillThroughTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"dt_sales\"",
        "CREATE TABLE \"dt_sales\" (\"tenant\" INTEGER,"
            + " \"region\" VARCHAR(8), \"amount\" INTEGER)",
        // tenant 1: 3 rows summing 153 ; tenant 2: 1 row (7) ;
        // tenant 3: 1 row (20). Unsecured: 5 rows summing 180. Distinct row
        // counts and sums per tenant make a leak unambiguous.
        "INSERT INTO \"dt_sales\" VALUES"
            + " (1,'EAST',100),(1,'WEST',50),(1,'EAST',3),"
            + " (2,'EAST',7),"
            + " (3,'EAST',20)",
    };

    private static final String SCHEMA =
        "<Schema name='PGDT' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='dt_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric'"
        + " defaultValue='1'>\n"
        + "    <QueryParameterValue>1</QueryParameterValue>\n"
        + "    <QueryParameterValue>2</QueryParameterValue>\n"
        + "    <QueryParameterValue>3</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='dt_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='dt_sales'>\n"
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
        "jdbc:h2:mem:pgdt_e2e;DB_CLOSE_DELAY=-1";

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
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            props.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(props, null, null);
    }

    /** A drillthrough result summary: number of raw fact rows returned and
     *  the sum of the Amount measure column across them. */
    private static final class DrillRows {
        final int count;
        final long amountSum;
        DrillRows(int count, long amountSum) {
            this.count = count;
            this.amountSum = amountSum;
        }
    }

    /** Drill through the single (All) cell, execute the generated SQL against
     *  H2, and summarise the raw fact rows it returns (count + amount sum). */
    private static DrillRows drillThrough(Connection conn) throws Exception {
        Query q = conn.parseQuery(MDX);
        Result r = conn.execute(q);
        try {
            RolapCell cell = (RolapCell) r.getCell(new int[]{0});
            String sql = cell.getDrillThroughSQL(true);
            assertTrue(sql != null && !sql.isEmpty(),
                "drillthrough SQL must be generated");
            int amountCol = -1;
            int count = 0;
            long sum = 0L;
            try (java.sql.Connection jc =
                     java.sql.DriverManager.getConnection(H2_URL, "sa", "");
                 Statement st = jc.createStatement();
                 ResultSet rs = st.executeQuery(sql))
            {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String label = md.getColumnLabel(i);
                    if (label != null
                        && label.toLowerCase().contains("amount"))
                    {
                        amountCol = i;
                    }
                }
                while (rs.next()) {
                    count++;
                    if (amountCol > 0) {
                        sum += rs.getLong(amountCol);
                    }
                }
            }
            return new DrillRows(count, sum);
        } finally {
            r.close();
        }
    }

    /** SECURITY: drillthrough on a row-secured cube must return ONLY the
     *  bound tenant's fact rows. Pre-fix this leaks every tenant (red). */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void drillThroughOnSecuredCubeDoesNotLeakRows(String form)
        throws Exception
    {
        Connection t1 = connect(schemaFor(form), "Tenant", "1");
        try {
            DrillRows d = drillThrough(t1);
            // The drillthrough on the (All) cell aggregates all fact rows into
            // a single Amount row; the SUM is the leak signal. Tenant 1's rows
            // sum to 153; a leak would surface the full table's 180.
            assertEquals(153L, d.amountSum,
                "drillthrough amount sum must be tenant 1's 153 (leak would "
                + "show other tenants' rows), got " + d.amountSum);
        } finally {
            t1.close();
        }
    }

    /** SECURITY: tenant 2 sees only its single row; confirms the restriction
     *  tracks the bound parameter value, not a constant. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void drillThroughRestrictsToBoundTenant(String form)
        throws Exception
    {
        Connection t2 = connect(schemaFor(form), "Tenant", "2");
        try {
            DrillRows d = drillThrough(t2);
            assertEquals(7L, d.amountSum,
                "tenant 2 amount = 7 (a leak would show 180)");
        } finally {
            t2.close();
        }
    }

    /** Control: a connection with NO role drills through the full,
     *  unrestricted table (180 across 2 regions). Confirms the test's leak
     *  signal is real — the secured connections above genuinely see less. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void drillThroughUnsecuredSeesAllRows(String form)
        throws Exception
    {
        Connection ungranted = connect(schemaFor(form), null, null);
        try {
            DrillRows d = drillThrough(ungranted);
            assertEquals(180L, d.amountSum, "unsecured amount sum = 180");
        } finally {
            ungranted.close();
        }
    }

    /** Fail-closed: a declared-but-unbound grant parameter must make
     *  drillthrough deny all rows (zero rows or a loud failure), never emit an
     *  unfiltered scan. The schema declares {@code tenant} as REQUIRED (no
     *  default) and the connection applies the role but supplies no
     *  {@code session.tenant}; the grant therefore cannot resolve a value.
     *  Connect-time validation may reject this outright (also fail-closed); if
     *  a connection is obtained, the drillthrough must still not leak. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void drillThroughUnboundGrantParamFailsClosed(String form)
        throws Exception
    {
        // Drop the default so 'tenant' is required and goes unbound.
        String src = SCHEMA.replace(" defaultValue='1'", "");
        String schema = "yaml".equals(form)
            ? mondrian.schema.yaml.m4.M4YamlToXml.toXml(
                mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(src))
            : src;
        try {
            Connection conn = connect(schema, "Tenant", null);
            try {
                DrillRows d = drillThrough(conn);
                assertEquals(0, d.count,
                    "an unbound grant parameter must deny all drillthrough "
                    + "rows, got " + d.count);
            } finally {
                conn.close();
            }
        } catch (RuntimeException expected) {
            // Connect-time / generation-time loud failure is also fail-closed:
            // no fact rows ever reached the user.
            assertTrue(expected.getMessage() != null);
        }
    }
}
