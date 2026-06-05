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
import mondrian.olap.Util;
import mondrian.rolap.RolapConnection;
import mondrian.rolap.RolapCube;
import mondrian.rolap.RolapCubeDimension;
import mondrian.rolap.RolapMeasureGroup;
import mondrian.rolap.RolapSchema;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #107, Phase 1: a {@code <BridgeLink>} loads into the model as a
 * two-hop path (fact → bridge → dimension) whose first hop is flagged
 * one-to-many (the fan-out), with the allocation semantics recorded — and
 * load-time validation fails loudly on misconfiguration. No query execution
 * yet (that is Phases 3–4); this pins the schema surface.
 */
public class BridgeLinkLoadTest {

    private static final String[] DDL = {
        "DROP TABLE \"account_fact\" IF EXISTS",
        "DROP TABLE \"account_owner\" IF EXISTS",
        "DROP TABLE \"dim_customer\" IF EXISTS",
        "DROP TABLE \"dim_date\" IF EXISTS",
        "CREATE TABLE \"account_fact\" (\"account_id\" INTEGER,"
            + " \"date_key\" INTEGER, \"balance\" INTEGER)",
        "CREATE TABLE \"account_owner\" (\"account_id\" INTEGER,"
            + " \"customer_id\" VARCHAR(16), \"weight\" DECIMAL(5,4))",
        "CREATE TABLE \"dim_customer\" (\"customer_id\" VARCHAR(16),"
            + " \"customer_name\" VARCHAR(32))",
        "CREATE TABLE \"dim_date\" (\"date_key\" INTEGER, \"yr\" INTEGER)",
    };

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
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
    }

    /** Build a Bank schema, parameterising the fact-key and bridge-link XML. */
    private static String schema(String factKeyXml, String bridgeXml) {
        return "<Schema name='Bank' metamodelVersion='4.0'>\n"
            + "  <PhysicalSchema>\n"
            + "    <Table name='account_fact'>" + factKeyXml + "</Table>\n"
            + "    <Table name='account_owner'/>\n"
            + "    <Table name='dim_customer'/>\n"
            + "    <Table name='dim_date'/>\n"
            + "  </PhysicalSchema>\n"
            + "  <Dimension name='Customer' table='dim_customer'"
            + " key='Customer'>\n"
            + "    <Attributes>\n"
            + "      <Attribute name='Customer'>\n"
            + "        <Key><Column name='customer_id'/></Key>\n"
            + "        <Name><Column name='customer_name'/></Name>\n"
            + "      </Attribute>\n"
            + "    </Attributes>\n"
            + "  </Dimension>\n"
            + "  <Dimension name='Date' table='dim_date' key='Date Id'"
            + " type='TIME'>\n"
            + "    <Attributes>\n"
            + "      <Attribute name='Date Id'>"
            + "<Key><Column name='date_key'/></Key></Attribute>\n"
            + "    </Attributes>\n"
            + "  </Dimension>\n"
            + "  <Cube name='Accounts'>\n"
            + "    <Dimensions>\n"
            + "      <Dimension source='Customer'/>\n"
            + "      <Dimension source='Date'/>\n"
            + "    </Dimensions>\n"
            + "    <MeasureGroups>\n"
            + "      <MeasureGroup name='Balances' table='account_fact'>\n"
            + "        <Measures>\n"
            + "          <Measure name='Balance' column='balance'"
            + " aggregator='sum'/>\n"
            + "        </Measures>\n"
            + "        <DimensionLinks>\n"
            + "          <ForeignKeyLink dimension='Date'"
            + " foreignKeyColumn='date_key'/>\n"
            + "          " + bridgeXml + "\n"
            + "        </DimensionLinks>\n"
            + "      </MeasureGroup>\n"
            + "    </MeasureGroups>\n"
            + "  </Cube>\n"
            + "</Schema>\n";
    }

    private static final String FACT_KEY =
        "<Key><Column name='account_id'/></Key>";

    private static Connection connect(String catalog) {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    private static RolapSchema.PhysPath bridgePath(
        Connection conn, RolapMeasureGroup[] mgOut, RolapCubeDimension[] dimOut)
    {
        RolapSchema schema = ((RolapConnection) conn).getSchema();
        RolapCube cube = (RolapCube) schema.lookupCube("Accounts", true);
        RolapMeasureGroup mg = cube.getMeasureGroups().get(0);
        RolapCubeDimension customer = null;
        for (RolapCubeDimension d : cube.getDimensionList()) {
            if (d.getName().equals("Customer")) {
                customer = d;
            }
        }
        mgOut[0] = mg;
        dimOut[0] = customer;
        return mg.dimensionMap3.get(customer);
    }

    @Test
    public void fullCountBridgeLoadsAsFanOutPath() {
        String bridge =
            "<BridgeLink dimension='Customer' bridgeTable='account_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'/>";
        Connection conn = connect(schema(FACT_KEY, bridge));
        try {
            RolapMeasureGroup[] mg = new RolapMeasureGroup[1];
            RolapCubeDimension[] dim = new RolapCubeDimension[1];
            RolapSchema.PhysPath path = bridgePath(conn, mg, dim);

            assertNotNull(path, "Customer must be linked via a bridge path");
            // fact -> bridge -> dim = 3 hops (root + 2 links)
            assertEquals(3, path.hopList.size(), "two-hop bridge path");
            assertTrue(
                path.hopList.get(1).link.oneToMany,
                "fact->bridge hop is one-to-many (the fan-out)");
            assertFalse(
                path.hopList.get(2).link.oneToMany,
                "bridge->dim hop is many-to-one");

            RolapMeasureGroup.BridgeInfo info =
                mg[0].getBridgeInfo(dim[0]);
            assertNotNull(info, "bridge allocation recorded");
            assertFalse(info.weighted, "default allocation is fullCount");
        } finally {
            conn.close();
        }
    }

    @Test
    public void weightedBridgeRecordsWeightColumn() {
        String bridge =
            "<BridgeLink dimension='Customer' bridgeTable='account_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'"
            + " aggregation='weighted' weightColumn='weight'/>";
        Connection conn = connect(schema(FACT_KEY, bridge));
        try {
            RolapMeasureGroup[] mg = new RolapMeasureGroup[1];
            RolapCubeDimension[] dim = new RolapCubeDimension[1];
            bridgePath(conn, mg, dim);
            RolapMeasureGroup.BridgeInfo info = mg[0].getBridgeInfo(dim[0]);
            assertNotNull(info);
            assertTrue(info.weighted, "weighted allocation");
            assertNotNull(info.weightColumn, "weight column resolved");
        } finally {
            conn.close();
        }
    }

    @Test
    public void weightedWithoutWeightColumnFailsLoudly() {
        String bridge =
            "<BridgeLink dimension='Customer' bridgeTable='account_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'"
            + " aggregation='weighted'/>";
        assertThrows(
            Throwable.class,
            () -> connect(schema(FACT_KEY, bridge)).close(),
            "weighted bridge without weightColumn must fail to load");
    }

    @Test
    public void fullCountWithoutFactKeyFailsLoudly() {
        String bridge =
            "<BridgeLink dimension='Customer' bridgeTable='account_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'/>";
        assertThrows(
            Throwable.class,
            () -> connect(schema("", bridge)).close(),
            "fullCount bridge without a fact <Key> must fail to load");
    }
}
