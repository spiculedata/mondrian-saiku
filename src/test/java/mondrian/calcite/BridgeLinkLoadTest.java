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
import mondrian.rolap.RolapStar;
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
        // Drop the process-wide Calcite planner cache so it re-reflects the
        // JDBC catalog including the fixture tables created above (a prior
        // test class may have warmed it against FoodMart only).
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
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

    private static RolapStar.Table findStarTable(
        RolapStar.Table t, String alias)
    {
        if (t.getRelation().getAlias().equals(alias)) {
            return t;
        }
        for (RolapStar.Table c : t.getChildren()) {
            RolapStar.Table f = findStarTable(c, alias);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** Phase 2: the bridge dimension's table is registered in the star,
     *  reachable via the bridge, with the fact→bridge hop flagged fan-out. */
    @Test
    public void bridgePathRegistersInStarAsFanOut() {
        String bridge =
            "<BridgeLink dimension='Customer' bridgeTable='account_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'/>";
        Connection conn = connect(schema(FACT_KEY, bridge));
        try {
            RolapMeasureGroup[] mg = new RolapMeasureGroup[1];
            RolapCubeDimension[] dim = new RolapCubeDimension[1];
            bridgePath(conn, mg, dim);
            RolapStar.Table customer =
                findStarTable(mg[0].getStar().getFactTable(), "dim_customer");
            assertNotNull(
                customer,
                "dim_customer is registered in the star via the bridge");
            RolapSchema.PhysPath p = customer.getPath();
            assertEquals(3, p.hopList.size(), "fact->bridge->dim star path");
            assertTrue(
                p.hopList.get(1).link.oneToMany,
                "fact->bridge hop in the star path is fan-out");
            assertEquals(
                "account_owner",
                p.hopList.get(1).relation.getAlias(),
                "intermediate is the bridge table");
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

    /**
     * #107 (CRITICAL double-count): the in-memory rollup guard's two decision
     * inputs for a FULL-COUNT bridge measure group are
     * (1) {@link RolapMeasureGroup#hasFullCountBridge()} is {@code true}, and
     * (2) the bridge dimension's star column reports
     * {@link RolapStar.Column#isBridgeFanoutReached()} {@code true} while a
     * normal FK dimension's column reports {@code false}. Together these make
     * a fanned-out leaf segment ineligible as a rollup source across the
     * bridge dimension, so the All/intermediate query reloads from the fact
     * and de-duplicates rather than summing the cached fan-out cells to 3100.
     */
    @Test
    public void fullCountBridgeNotEligibleForInMemoryRollup() {
        String bridge =
            "<BridgeLink dimension='Customer' bridgeTable='account_owner'"
            + " factForeignKeyColumn='account_id'"
            + " bridgeFactKeyColumn='account_id'"
            + " bridgeDimensionKeyColumn='customer_id'/>";
        Connection conn = connect(schema(FACT_KEY, bridge));
        try {
            RolapMeasureGroup[] mg = new RolapMeasureGroup[1];
            RolapCubeDimension[] dim = new RolapCubeDimension[1];
            bridgePath(conn, mg, dim);

            assertTrue(
                mg[0].hasFullCountBridge(),
                "full-count bridge MG is flagged for the rollup guard");

            // The customer dimension column, reached through the bridge, is a
            // fan-out grain — the rollup guard treats it as unsafe to collapse.
            RolapStar.Column customerCol = bridgeDimColumn(mg[0], "dim_customer");
            assertNotNull(customerCol, "customer star column resolved");
            assertTrue(
                customerCol.isBridgeFanoutReached(),
                "customer column is reached through the fan-out hop");

            // A normal FK dimension column (Date) is NOT fan-out — safe to
            // roll up, so the guard must leave it alone.
            RolapStar.Column dateCol = bridgeDimColumn(mg[0], "dim_date");
            assertNotNull(dateCol, "date star column resolved");
            assertFalse(
                dateCol.isBridgeFanoutReached(),
                "normal FK column is not fan-out — stays rollup-eligible");
        } finally {
            conn.close();
        }
    }

    /**
     * #107: a WEIGHTED bridge rolls up additively (the weight allocation sums
     * correctly across grains), so its measure group is NOT flagged by the
     * guard and stays eligible for in-memory rollup — only full-count is
     * excluded.
     */
    @Test
    public void weightedBridgeRemainsRollupEligible() {
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
            assertFalse(
                mg[0].hasFullCountBridge(),
                "weighted bridge MG stays rollup-eligible (not full-count)");
        } finally {
            conn.close();
        }
    }

    /** First constrained column whose star table is the given dimension table
     *  (resolved by walking the star from the fact). */
    private static RolapStar.Column bridgeDimColumn(
        RolapMeasureGroup mg, String dimTableAlias)
    {
        RolapStar.Table t =
            findStarTable(mg.getStar().getFactTable(), dimTableAlias);
        if (t == null || t.getColumns().isEmpty()) {
            return null;
        }
        return t.getColumns().get(0);
    }
}
