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
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for the plain (non-NON-EMPTY) member-enumeration gaps
 * surfaced by {@link CalciteParityFuzzTest}:
 *
 * <ul>
 *   <li>#91 — a snowflake hierarchy leaf ({@code [Product].[Product Name]})
 *       enumerated under Calcite projects its key/name columns in a
 *       different order than the tuple-reader column layout expects,
 *       silently mis-keying members.</li>
 *   <li>#92 — a deep flat-hierarchy leaf carrying member-property columns
 *       ({@code [Store].[Store Name]}) under-projects those columns,
 *       raising an {@code AssertionError} (which, being an Error, escapes
 *       the SqlTupleReader fallback).</li>
 * </ul>
 *
 * Both are the plain {@code DefaultTupleConstraint} enumeration branch of
 * {@code CalcitePlannerAdapters.translateTupleRead}; the NON EMPTY variants
 * pass via the fact-joined path fixed for #89. These queries compare the
 * legacy and Calcite result grids and require an exact match.
 */
public class PlainEnumerationParityTest {

    private static Connection legacyConn;
    private static Connection calciteConn;

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
        legacyConn = newConnection();
        calciteConn = newConnection();
    }

    @AfterAll
    public static void close() {
        if (legacyConn != null) {
            legacyConn.close();
            legacyConn = null;
        }
        if (calciteConn != null) {
            calciteConn.close();
            calciteConn = null;
        }
    }

    private static Connection newConnection() {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        return DriverManager.getConnection(props, null, null);
    }

    private static String run(Connection conn, String backend, String mdx) {
        System.setProperty("mondrian.backend", backend);
        try {
            Query q = conn.parseQuery(mdx);
            Result r = conn.execute(q);
            String grid = TestContext.toString(r);
            r.close();
            return grid;
        } finally {
            System.clearProperty("mondrian.backend");
        }
    }

    private void assertParity(String mdx) {
        String legacy = run(legacyConn, "legacy", mdx);
        String calcite = run(calciteConn, "calcite", mdx);
        assertEquals(
            legacy, calcite,
            "Calcite plain-enumeration grid must match legacy for: " + mdx);
    }

    /** #91: snowflake leaf with a distinct name column. */
    @Test
    public void plainProductNameMatchesLegacy() {
        assertParity(
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "[Product].[Products].[Product Name].Members ON ROWS "
            + "FROM [Warehouse and Sales]");
    }

    /** #92: deep flat-hierarchy leaf carrying member-property columns. */
    @Test
    public void plainStoreNameMatchesLegacy() {
        assertParity(
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "[Store].[Stores].[Store Name].Members ON ROWS "
            + "FROM [Warehouse and Sales]");
    }
}
