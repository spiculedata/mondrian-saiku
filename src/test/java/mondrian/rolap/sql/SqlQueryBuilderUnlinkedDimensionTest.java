/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap.sql;

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression test for issue #46's secondary failure class: a
 * {@link NullPointerException} from {@code SqlQueryBuilder.parentTable}
 * when a virtual-cube query asks for cells of a measure whose measure
 * group has no link declared for the dimension on the other axis.
 *
 * <p>FoodMart's {@code Warehouse and Sales} cube declares
 * {@code <NoLink dimension='Customer'/>} on its Warehouse measure group
 * (see {@code demo/FoodMart.mondrian.xml}). Querying
 * {@code [Measures].[Warehouse Sales]} against the {@code [Customer]}
 * dimension routes a tuple read through
 * {@code SqlTupleReader → SqlQueryBuilder.parentTable} where
 * {@code RolapMeasureGroup.dimensionMap3.get(customer)} returns
 * {@code null} — pre-fix, the immediate {@code path.getLinks().isEmpty()}
 * call NPEs and the query fails with an opaque error. Post-fix the
 * null path is treated as a degenerate / unlinked dimension and the
 * tuple read returns empty rows for that combination, matching the
 * legacy SQL planner's behaviour.
 */
public class SqlQueryBuilderUnlinkedDimensionTest {

    private static Connection mondrianConn;

    @BeforeAll
    public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        // Bypass the schema pool — guarantees a fresh RolapSchema build
        // so this test's outcome doesn't depend on whatever state an
        // earlier test left in the pool.
        props.put("UseSchemaPool", "false");
        mondrianConn = DriverManager.getConnection(props, null, null);
    }

    @AfterAll
    public static void closeFoodMart() {
        if (mondrianConn != null) {
            mondrianConn.close();
            mondrianConn = null;
        }
    }

    @Test
    public void warehouseMeasureAgainstCustomerDimensionDoesNotNpe() {
        // [Customer] is linked to the Sales measure group but explicitly
        // <NoLink/>'d on the Warehouse measure group. Asking for
        // [Warehouse Sales] along [Customer].[Customers] rows used to NPE
        // in SqlQueryBuilder.parentTable when dimensionMap3.get(customer)
        // returned null. The same shape feeds the second-class failures
        // reported in the issue #46 fuzz.
        String mdx =
            "SELECT { [Measures].[Warehouse Sales] } ON COLUMNS,\n"
            + "       NON EMPTY { [Customer].[Customers].[Country].MEMBERS }"
            + " ON ROWS\n"
            + "FROM [Warehouse and Sales]";
        Result result = assertDoesNotThrow(() -> execute(mdx),
            "Warehouse-measure × Customer-dim must not NPE");
        assertNotNull(result, "result must be populated");
        // No cell-value contract here — the legacy planner returns all
        // nulls for an unlinked-dimension × measure combination, and the
        // fix preserves that. The regression we care about is the
        // absence of the NPE, not the cell contents.
    }

    private static Result execute(String mdx) {
        Query q = mondrianConn.parseQuery(mdx);
        return mondrianConn.execute(q);
    }
}
