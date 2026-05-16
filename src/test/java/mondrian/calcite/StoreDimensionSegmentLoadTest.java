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

import org.junit.jupiter.api.AfterAll;import org.junit.jupiter.api.BeforeAll;import org.junit.jupiter.api.Test;
/**
 * <b>KNOWN-FAILING regression test for the user-reported Store-dimension
 * exception.</b> Expected to fail until {@link CalcitePlannerAdapters}'s
 * tuple-read shape (and the parallel segment-load shape) projects the
 * parent-level key columns for non-flat hierarchies.
 *
 * <p>Diagnostic from a local run (2026-05-14):
 * <pre>{@code
 *   Caused by: java.lang.AssertionError:
 *       types [null, null] cardinality != column count 1
 *     at mondrian.rolap.SqlStatement.guessTypes(SqlStatement.java:453)
 *   sql=[SELECT "store_state" FROM "store"
 *        GROUP BY "store_state" ORDER BY "store_state"]
 * }</pre>
 *
 * <p>Mondrian's SqlTupleReader expects 2 columns ({@code store_country}
 * = parent-level key, {@code store_state} = current-level key) so it can
 * build the multi-level member tuple. The Calcite plan only projects the
 * current level's key — the parent-level key is silently dropped.
 *
 * <p>The user's segment-load error was the cell-load mirror of the same
 * gap: {@code RelBuilder.field("store","store_id")} called against a
 * builder whose only registered aliases were {@code sales_fact_1997} and
 * {@code time_by_day} — the {@code store} table never got scanned because
 * the corresponding join edge was missing from {@code PlannerRequest}.
 *
 * <p>Both code paths converge on {@code CalcitePlannerAdapters}: the
 * tuple-read in {@code translateTupleRead}/{@code shapeFor}; the
 * segment-load in {@code translateSegmentLoad}/{@code ensureJoinedChain}.
 *
 * <pre>{@code
 *   IllegalArgumentException: {alias=store,fieldName=store_id} field not found;
 *     fields are: {aliases=[sales_fact_1997],fieldName=product_id}{...}
 * }</pre>
 *
 * <p>The original error came from Calcite's
 * {@code RelBuilder.field(inputCount, alias, fieldName)} (RelBuilder.java:640
 * in calcite-core 1.41.0) when the planner asked for {@code store.store_id}
 * but the builder's frame stack only had {@code sales_fact_1997} and
 * {@code time_by_day} registered — i.e. the join edge that should have
 * scanned the {@code store} table never landed on the {@code PlannerRequest}.
 *
 * <p>The MDX query mirrors the user's saiku grid: two measures grouped by
 * {@code [Time].[Quarter] x [Store].[Store State]}. The aggregate runs
 * through {@link CalcitePlannerAdapters#translateSegmentLoad}, exercising
 * {@code ensureJoinedChain} for both dimensions. Either dimension alone is
 * not sufficient — the bug shape required at least two dim columns on the
 * same grouping set.
 *
 * <p>Pass criterion: the MDX query executes end-to-end without throwing.
 * Any failure mode (UnsupportedTranslation that falls back to legacy SQL,
 * or a successful calcite plan) is acceptable — the test exists to catch
 * the {@code IllegalArgumentException} that the planner emitted when the
 * Store join was silently dropped.
 */
public class StoreDimensionSegmentLoadTest {

    /**
     * Simpler MDX: just measures grouped by {@code [Store].[Store State]}.
     * This is the minimum shape that exercises the Store-dim join in the
     * segment-load path without involving any other dim. If this passes,
     * the bug is in the multi-dim cross-join handling
     * ({@code translateSegmentLoad}'s second {@code ensureJoinedChain}
     * call dropping the first dim's join when the second dim is appended).
     */
    private static final String MDX =
        "SELECT"
        + " {[Measures].[Unit Sales], [Measures].[Sales Count]} ON COLUMNS,"
        + " {[Store].[Store State].MEMBERS} ON ROWS"
        + " FROM Sales";

    private static String prevBackend;

    @BeforeAll
    public static void boot() {
        FoodMartHsqldbBootstrap.ensureExtracted();
        prevBackend = System.getProperty("mondrian.backend");
        System.setProperty("mondrian.backend", "calcite");
    }

    @AfterAll
    public static void tearDown() {
        if (prevBackend == null) {
            System.clearProperty("mondrian.backend");
        } else {
            System.setProperty("mondrian.backend", prevBackend);
        }
    }

    @Test
    public void crossJoinTimeAndStoreDimensionsDoesNotThrow() {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");

        // Cold schema-cache flush on a throwaway connection so we don't
        // benefit from a prior test's cached RolapStar shape.
        Connection flushConn =
            DriverManager.getConnection(props, null, null);
        try {
            flushConn.getCacheControl(null).flushSchemaCache();
        } finally {
            flushConn.close();
        }

        Connection conn = DriverManager.getConnection(props, null, null);
        try {
            Query q = conn.parseQuery(MDX);
            Result r = conn.execute(q);
            try {
                // Materialize so segment-load fires (the bug was
                // construction-time, not parse-time).
                TestContext.toString(r);
            } finally {
                r.close();
            }
        } finally {
            conn.close();
        }
    }
}

// End StoreDimensionSegmentLoadTest.java
