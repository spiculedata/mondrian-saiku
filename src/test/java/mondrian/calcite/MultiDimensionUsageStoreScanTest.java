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
 * Regression test for the third class of failure surfaced by the issue
 * #46 audit: a {@code CalciteException: Table 'store_1' not found} from
 * {@link org.apache.calcite.tools.RelBuilder#scan(String)} when a cube
 * carries more than one DimensionUsage of the same shared dim.
 *
 * <p>Mondrian aliases the duplicate PhysTable instances ({@code store},
 * {@code store_1}, {@code store_2}); a NON EMPTY tuple-read against a
 * level on the second/third usage routes through SqlTupleReader →
 * CalcitePlannerAdapters.fromTupleRead → CalciteSqlPlanner.build where
 * {@code b.scan(<alias>)} is called with the Mondrian alias rather than
 * the underlying physical table name. JdbcSchema only knows the
 * physical name, so the lookup fails.
 *
 * <p>Schema overlay (see {@link #SALES_MULTI_STORE_CUBE}): a Sales-like
 * cube with three DimensionUsages of the shared Store dim. Standard
 * {@code demo/FoodMart.mondrian.xml} only declares one Store dim, which
 * is why this issue doesn't surface against the canonical schema.
 */
public class MultiDimensionUsageStoreScanTest {

    private static final String SALES_MULTI_STORE_CUBE =
        "<Cube name='SalesMultiStore' defaultMeasure='Unit Sales'>\n"
        + "  <Dimensions>\n"
        + "    <Dimension source='Store'/>\n"
        + "    <Dimension name='Store2' source='Store'/>\n"
        + "    <Dimension name='Store3' source='Store'/>\n"
        + "  </Dimensions>\n"
        + "  <MeasureGroups>\n"
        + "    <MeasureGroup name='Sales' table='sales_fact_1997'>\n"
        + "      <Measures>\n"
        + "        <Measure name='Unit Sales' column='unit_sales'\n"
        + "                 aggregator='sum'/>\n"
        + "        <Measure name='Sales Count' column='product_id'\n"
        + "                 aggregator='count'/>\n"
        + "      </Measures>\n"
        + "      <DimensionLinks>\n"
        + "        <ForeignKeyLink dimension='Store'\n"
        + "                        foreignKeyColumn='store_id'/>\n"
        + "        <ForeignKeyLink dimension='Store2'\n"
        + "                        foreignKeyColumn='store_id'/>\n"
        + "        <ForeignKeyLink dimension='Store3'\n"
        + "                        foreignKeyColumn='store_id'/>\n"
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
        // Inject our cube right before the closing </Schema>.
        String overlay = catalog.replace(
            "</Schema>",
            SALES_MULTI_STORE_CUBE + "</Schema>");
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
    public void forceCalcite() {
        System.setProperty("mondrian.backend", "calcite");
        System.setProperty("mondrian.calcite.strict", "true");
    }

    @AfterEach
    public void clearBackend() {
        System.clearProperty("mondrian.backend");
        System.clearProperty("mondrian.calcite.strict");
    }

    /**
     * Reproducer for the {@code Table 'store_1' not found} class from
     * issue #46. NON EMPTY forces a tuple-read against
     * {@code [Store3].[Stores]} which Mondrian maps onto the third
     * aliased {@code store} PhysTable ({@code store_2} in the alias
     * sequence). The Calcite path used to die in {@code b.scan(store_2)}
     * because JdbcSchema only knows the physical name {@code store}.
     */
    @Test
    public void multiDimUsageStoreLevelMembersDoesNotFail() {
        String mdx =
            "SELECT NON EMPTY {[Measures].[Sales Count]} ON COLUMNS,\n"
            + "       NON EMPTY [Store3].[Stores].[Store Country].MEMBERS"
            + " ON ROWS\n"
            + "FROM [SalesMultiStore]";
        Result result = assertDoesNotThrow(() -> execute(mdx),
            "Multi-DimUsage Store level-members must not fail with "
                + "CalciteException: Table 'store_<n>' not found");
        assertNotNull(result);
    }

    private static Result execute(String mdx) {
        Query q = mondrianConn.parseQuery(mdx);
        return mondrianConn.execute(q);
    }
}
