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
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Issue #46 / PR #51 sibling: the legacy SQL builder hits the same
 * alias-collision class that #51 addresses on the Calcite side, but the
 * trigger is <em>different shared Dimension declarations that target the
 * same physical Table</em> — not multiple DimensionUsages of one shared
 * dim. The loader's clone-on-duplicate logic (RolapSchemaLoader.java:3107)
 * is keyed on shared-dimension identity, so it never fires when {@code
 * Store2} and {@code Store3} are separate {@code <Dimension>} declarations
 * that happen to share {@code table='store'}.
 *
 * <p>Repro shape (from issue follow-up): three top-level shared dims
 * {@code Store / Store2 / Store3}, all mapping to {@code <Table
 * name='store'>}; a cube imports {@code Store2} and {@code Store3} both
 * linked to {@code sales_fact_1997}. A crossjoin spanning both dims
 * routes through {@code SqlTupleReader.generateSelectForLevels} →
 * {@code SqlQueryBuilder.flush} → {@code SqlQuery.addFromTable}, which
 * asserts {@code "query already contains alias 'store'"} because both
 * dims hand the same {@code physRelation.getAlias()} to {@code
 * addFromSuper}.
 *
 * <p>Forced {@code mondrian.backend=legacy} because the issue follow-up
 * specifically notes {@code -Dmondrian.backend=legacy} does NOT help —
 * the legacy SQL builder IS the failing code path.
 */
public class MultiSharedDimSameTableLegacyAliasTest {

    /**
     * Two extra shared dim declarations (Store2 / Store3) targeting the
     * same physical {@code store} table as the existing {@code Store}
     * shared dim, plus a cube that imports Store2 + Store3 together. We
     * deliberately omit Store from the cube — the failing combinations
     * in the issue follow-up all pair Store2 with Store3, never the
     * original Store.
     */
    private static final String SCHEMA_OVERLAY =
        // Store2: dim-level table='store' so the loader has a clear
        // hook on which to dedupe against Store3 (which is also
        // table='store'). The launcher seed uses attribute-level
        // table on Store2; the bug shape is the same once the dim's
        // physical target collides with another shared dim's already
        // in the cube — keeping this uniform here so the fix can key
        // off sharedDimension.table without needing to walk attribute
        // tables in the loader.
        "<Dimension name='Store2' table='store' key='Store Id'>\n"
        + "  <Attributes>\n"
        + "    <Attribute name='Store Id'"
        + " keyColumn='store_id' hasHierarchy='false'/>\n"
        + "    <Attribute name='Store Type'"
        + " keyColumn='store_type'/>\n"
        + "  </Attributes>\n"
        + "  <Hierarchies>\n"
        + "    <Hierarchy name='Store' allMemberName='All Path'>\n"
        + "      <Level attribute='Store Type'/>\n"
        + "    </Hierarchy>\n"
        + "  </Hierarchies>\n"
        + "</Dimension>\n"
        // Store3: dim-level table='store'.
        + "<Dimension name='Store3' table='store' key='Store Id'>\n"
        + "  <Attributes>\n"
        + "    <Attribute name='Store Id' keyColumn='store_id'"
        + " hasHierarchy='false'/>\n"
        + "    <Attribute name='Store Manager' keyColumn='store_manager'/>\n"
        + "  </Attributes>\n"
        + "  <Hierarchies>\n"
        + "    <Hierarchy name='Store' allMemberName='All Path'>\n"
        + "      <Level attribute='Store Manager'/>\n"
        + "    </Hierarchy>\n"
        + "  </Hierarchies>\n"
        + "</Dimension>\n"
        + "<Cube name='WarehouseAndSales46' defaultMeasure='Sales Count'>\n"
        + "  <Dimensions>\n"
        + "    <Dimension source='Store2'/>\n"
        + "    <Dimension source='Store3'/>\n"
        + "  </Dimensions>\n"
        + "  <MeasureGroups>\n"
        + "    <MeasureGroup name='Sales' table='sales_fact_1997'>\n"
        + "      <Measures>\n"
        + "        <Measure name='Sales Count' column='product_id'\n"
        + "                 aggregator='count'/>\n"
        + "      </Measures>\n"
        + "      <DimensionLinks>\n"
        // Match the launcher schema verbatim (issue follow-up): Store3's
        // FK is product_id, not store_id. Looks like a typo in the seed
        // file but it's what the live demo runs, and may well be what
        // forces Mondrian to keep two independent store-table references
        // rather than collapsing them.
        + "        <ForeignKeyLink dimension='Store2'"
        + " foreignKeyColumn='store_id'/>\n"
        + "        <ForeignKeyLink dimension='Store3'"
        + " foreignKeyColumn='product_id'/>\n"
        + "      </DimensionLinks>\n"
        + "    </MeasureGroup>\n"
        + "  </MeasureGroups>\n"
        + "</Cube>\n";

    private static Connection mondrianConn;

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
        String catalog = new String(
            Files.readAllBytes(
                Paths.get("demo/FoodMart.mondrian.xml")));
        String overlay = catalog.replace(
            "</Schema>", SCHEMA_OVERLAY + "</Schema>");
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(
            RolapConnectionProperties.CatalogContent.name(), overlay);
        props.remove(RolapConnectionProperties.Catalog.name());
        mondrianConn = DriverManager.getConnection(props, null, null);
    }

    @AfterAll
    public static void close() {
        if (mondrianConn != null) {
            mondrianConn.close();
            mondrianConn = null;
        }
    }

    @BeforeEach
    public void forceLegacy() {
        System.setProperty("mondrian.backend", "legacy");
        // Stack the deck for the failing path: turn on every native eval
        // route so the SqlTupleReader pathway from the user's stack
        // (RolapNativeSet.executeList → readTuples → prepareTuples →
        // makeLevelMembersSql → SqlQueryBuilder.flush → addFromTable) is
        // actually entered.
        System.setProperty(
            "mondrian.native.crossjoin.enable", "true");
        System.setProperty(
            "mondrian.native.nonempty.enable", "true");
    }

    @AfterEach
    public void clearBackend() {
        System.clearProperty("mondrian.backend");
        System.clearProperty("mondrian.native.crossjoin.enable");
        System.clearProperty("mondrian.native.nonempty.enable");
    }

    @Test
    public void crossjoinAcrossSharedDimsTargetingSameTable() {
        // Exercise all three failing shapes the issue follow-up enumerates:
        // crossjoin two members, crossjoin × rows, slicer + rows.
        String[] shapes = {
            "SELECT {[Measures].[Sales Count]} ON COLUMNS,\n"
            + "       NON EMPTY CROSSJOIN(\n"
            + "         [Store2].[Store].[Store Type].MEMBERS,\n"
            + "         [Store3].[Store].[Store Manager].MEMBERS\n"
            + "       ) ON ROWS\n"
            + "FROM [WarehouseAndSales46]",
            "SELECT NON EMPTY [Store2].[Store].[Store Type].MEMBERS"
            + " ON COLUMNS,\n"
            + "       NON EMPTY [Store3].[Store].[Store Manager].MEMBERS"
            + " ON ROWS\n"
            + "FROM [WarehouseAndSales46]\n"
            + "WHERE [Measures].[Sales Count]",
            "SELECT {[Measures].[Sales Count]} ON COLUMNS,\n"
            + "       NON EMPTY [Store3].[Store].[Store Manager].MEMBERS"
            + " ON ROWS\n"
            + "FROM [WarehouseAndSales46]\n"
            + "WHERE [Store2].[Store].[Store Type].[Supermarket]"
        };
        for (String mdx : shapes) {
            final String q = mdx;
            Result result = assertDoesNotThrow(() -> execute(q),
                "Multi-shared-dim shape under legacy backend must not "
                    + "assert 'query already contains alias store' — "
                    + "failing shape was:\n" + mdx);
            assertNotNull(result);
        }
    }

    private static Result execute(String mdx) {
        Query q = mondrianConn.parseQuery(mdx);
        return mondrianConn.execute(q);
    }
}
