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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #107 (SECURITY): DRILLTHROUGH on a BRIDGE (many-to-many) cube secured
 * by a {@code <MemberGrant>}/{@code <HierarchyGrant>} must not leak raw fact
 * rows owned only by hidden bridge members.
 *
 * <p>Drillthrough runs through the LEGACY {@code DrillThroughQuerySpec}, which
 * injects only {@code <PredicateGrant>} filters — it has no bridge fan-out join
 * and no member-grant filter. The bridge member filter
 * ({@code applyBridgeMemberGrant}) lives only in the Calcite segment path, so a
 * bridge drillthrough cannot enforce it. Consistent with the segment-load
 * policy (#106/#107 fail-closed), such a drillthrough must fail closed — either
 * refuse, or return no hidden-owner rows — never hand back the unsecured fact
 * table.
 *
 * <pre>
 *   br_fact                br_owner (bridge)
 *   acct balance           acct customer
 *    1   1000               1   Alice
 *    2    500               2   Bob
 *    3    700               3   Carol   (acct3 owned ONLY by hidden Carol)
 *
 *   Role AliceBob grants Alice + Bob, hides Carol. A correct drillthrough on
 *   the [All] cell must NOT return acct3 (balance 700, Carol-only).
 * </pre>
 */
public class BridgeMemberGrantDrillThroughTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"bdt_fact\"",
        "DROP TABLE IF EXISTS \"bdt_owner\"",
        "DROP TABLE IF EXISTS \"bdt_customer\"",
        "CREATE TABLE \"bdt_fact\" (\"account_id\" INTEGER,"
            + " \"balance\" INTEGER)",
        "CREATE TABLE \"bdt_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16))",
        "CREATE TABLE \"bdt_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32))",
        "INSERT INTO \"bdt_fact\" VALUES (1, 1000)",
        "INSERT INTO \"bdt_fact\" VALUES (2, 500)",
        "INSERT INTO \"bdt_fact\" VALUES (3, 700)",
        "INSERT INTO \"bdt_owner\" VALUES (1, 'Alice')",
        "INSERT INTO \"bdt_owner\" VALUES (2, 'Bob')",
        "INSERT INTO \"bdt_owner\" VALUES (3, 'Carol')",
        "INSERT INTO \"bdt_customer\" VALUES ('Alice', 'Alice')",
        "INSERT INTO \"bdt_customer\" VALUES ('Bob', 'Bob')",
        "INSERT INTO \"bdt_customer\" VALUES ('Carol', 'Carol')",
    };

    private static final String SCHEMA =
        "<Schema name='BDT' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='bdt_fact'>"
        + "<Key><Column name='account_id'/></Key></Table>\n"
        + "    <Table name='bdt_owner'/>\n"
        + "    <Table name='bdt_customer'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Customer' table='bdt_customer' key='Customer'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Customer'>\n"
        + "        <Key><Column name='customer_id'/></Key>\n"
        + "        <Name><Column name='customer_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "    <Hierarchies>\n"
        + "      <Hierarchy name='Customers' allMemberName='All Customers'>\n"
        + "        <Level attribute='Customer'/>\n"
        + "      </Hierarchy>\n"
        + "    </Hierarchies>\n"
        + "  </Dimension>\n"
        + "  <Cube name='AccountsFull'>\n"
        + "    <Dimensions>\n"
        + "      <Dimension source='Customer'/>\n"
        + "    </Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='bdt_fact'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Balance' column='balance'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <BridgeLink dimension='Customer'"
        + " bridgeTable='bdt_owner'"
        + " factForeignKeyColumn='account_id'"
        + " bridgeFactKeyColumn='account_id'"
        + " bridgeDimensionKeyColumn='customer_id'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "  <Role name='AliceBob'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='AccountsFull' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Customer].[Customers]'"
        + " access='custom'"
        + " bottomLevel='[Customer].[Customers].[Customer]'>\n"
        + "          <MemberGrant"
        + " member='[Customer].[Customers].[Alice]' access='all'/>\n"
        + "          <MemberGrant"
        + " member='[Customer].[Customers].[Bob]' access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static final String MDX =
        "SELECT {[Measures].[Balance]} ON COLUMNS FROM [AccountsFull]";

    private static final String H2_URL =
        "jdbc:h2:mem:bdt_e2e;DB_CLOSE_DELAY=-1";

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

    private static Connection connect(String catalog, String role) {
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
        return DriverManager.getConnection(props, null, null);
    }

    /** SECURITY: drillthrough on a member-grant-secured bridge cube must not
     *  surface the hidden-only-owner account (acct3, balance 700). Either the
     *  drillthrough fails closed (refused) or it returns no row carrying 700. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void drillThroughBridgeMemberGrantDoesNotLeakHiddenOwner(String form)
        throws Exception
    {
        Connection conn = connect(schemaFor(form), "AliceBob");
        try {
            Query q = conn.parseQuery(MDX);
            Result r = conn.execute(q);
            String sql;
            try {
                RolapCell cell = (RolapCell) r.getCell(new int[]{0});
                sql = cell.getDrillThroughSQL(true);
            } catch (RuntimeException failClosed) {
                // Refusing to generate the drillthrough SQL is fail-closed —
                // no fact rows ever reach the user. Acceptable.
                return;
            } finally {
                r.close();
            }
            if (sql == null || sql.isEmpty()) {
                // No SQL emitted → nothing leaked.
                return;
            }
            long balanceSum = 0L;
            boolean sawHiddenOwnerRow = false;
            try (java.sql.Connection jc =
                     java.sql.DriverManager.getConnection(H2_URL, "sa", "");
                 Statement st = jc.createStatement();
                 ResultSet rs = st.executeQuery(sql))
            {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int balanceCol = -1;
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String label = md.getColumnLabel(i);
                    if (label != null
                        && label.toLowerCase().contains("balance"))
                    {
                        balanceCol = i;
                    }
                }
                while (rs.next()) {
                    if (balanceCol > 0) {
                        long bal = rs.getLong(balanceCol);
                        balanceSum += bal;
                        if (bal == 700L) {
                            sawHiddenOwnerRow = true;
                        }
                    }
                }
            }
            assertTrue(!sawHiddenOwnerRow && balanceSum <= 1500L,
                "drillthrough leaked the hidden-only-owner account (Carol's"
                + " acct3, balance 700): visible total should be <= 1500, got "
                + balanceSum);
        } finally {
            conn.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}

// End BridgeMemberGrantDrillThroughTest.java
