/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.test.calcite;

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.agg.SegmentLoader;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Row-security cover for the saiku#1665 fix. Dropping the compound-slicer
 * restriction from a Calcite member read widens that read, so it must be
 * proven NOT to widen what a role-restricted user can see.
 *
 * <p>The demo schema's "California manager" role grants
 * {@code [Store].[Stores].[USA].[CA]} and explicitly denies
 * {@code [USA].[CA].[Los Angeles]}. Under a compound slicer the axis must
 * still show CA cities only, never Los Angeles and never another state —
 * and must agree with the legacy backend.
 */
public class CompoundSlicerNonEmptyRlsTest {

    private static final String ROLE = "California manager";

    private static final String CITIES_MDX =
        "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\n"
        + "       NON EMPTY {[Store].[Stores].[Store City].Members} ON ROWS\n"
        + "FROM [Sales]\n"
        + "WHERE {[Time].[Time].[1997], [Time].[Time].[1998]}";

    @BeforeAll public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    @AfterEach public void clearBackend() {
        System.clearProperty("mondrian.backend");
        SegmentLoader.clearCalcitePlannerCache();
    }

    @Test public void compoundSlicerHonoursMemberGrants() {
        String legacy = runAsRole("legacy", CITIES_MDX);
        String calcite = runAsRole("calcite", CITIES_MDX);

        assertEquals(
            "role-restricted compound-slicer read must match legacy",
            legacy, calcite);
        assertTrue(
            "granted CA cities must be visible (the fix must not "
            + "over-restrict): " + calcite,
            calcite.contains("[Store].[Stores].[USA].[CA].[San Francisco]"));
        assertFalse(
            "denied member [USA].[CA].[Los Angeles] must not leak: " + calcite,
            calcite.contains("[Los Angeles]"));
        assertFalse(
            "ungranted state WA must not leak: " + calcite,
            calcite.contains("[USA].[WA]"));
        assertFalse(
            "ungranted state OR must not leak: " + calcite,
            calcite.contains("[USA].[OR]"));
    }

    private static String runAsRole(String backend, String mdx) {
        System.setProperty("mondrian.backend", backend);
        SegmentLoader.clearCalcitePlannerCache();
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put("Role", ROLE);
        Connection conn = DriverManager.getConnection(props, null, null);
        try {
            conn.getCacheControl(null).flushSchemaCache();
            Query parsed = conn.parseQuery(mdx);
            Result result = conn.execute(parsed);
            try {
                return TestContext.toString(result);
            } finally {
                result.close();
            }
        } finally {
            conn.close();
        }
    }
}

// End CompoundSlicerNonEmptyRlsTest.java
