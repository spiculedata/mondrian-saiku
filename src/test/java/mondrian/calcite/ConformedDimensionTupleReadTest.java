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

import mondrian.olap.Axis;
import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for issue #89: a NON EMPTY query whose single axis is a
 * <em>conformed dimension</em> — one that links via {@code ForeignKeyLink}
 * into more than one {@code <MeasureGroup>} — silently returns an empty
 * tuple set under the Calcite backend (no exception, so no fallback to the
 * correct legacy SQL).
 *
 * <p>This mirrors the customer GDELT cube structure on FoodMart. The
 * {@code GdeltLike} overlay cube (see {@link #GDELT_LIKE_CUBE}) has two
 * measure groups ({@code Sales} on {@code sales_fact_1997}, {@code Inventory}
 * on {@code inventory_fact_1997}) and a conformed dimension {@code Outlet}
 * keyed on a code column ({@code store_id}) with a <em>distinct name
 * column</em> ({@code store_name}) — exactly the shape of GDELT's
 * {@code Event Root} ({@code event_root_code}/{@code event_root_name}),
 * {@code QuadClass}, and {@code Action Country} dimensions. A plain FoodMart
 * conformed dimension whose level key doubles as its name (e.g.
 * {@code [Store].[Store Country]}) does <em>not</em> reproduce the bug; the
 * distinct {@code <Name>} column is the trigger.
 *
 * <p>Two isolated connections (one per backend, {@code UseSchemaPool=false}
 * so segment caches don't bleed across) execute the same MDX; the result
 * grids must match.
 */
public class ConformedDimensionTupleReadTest {

    /**
     * GDELT-shaped overlay: two measure groups over two fact tables, with a
     * conformed {@code Outlet} dimension (code key + distinct name) linking
     * into both via {@code ForeignKeyLink}.
     */
    private static final String GDELT_LIKE_CUBE =
        "<Dimension name='Outlet' table='store' key='Outlet'>\n"
        + "  <Attributes>\n"
        + "    <Attribute name='Outlet'>\n"
        + "      <Key><Column name='store_id'/></Key>\n"
        + "      <Name><Column name='store_name'/></Name>\n"
        + "    </Attribute>\n"
        + "  </Attributes>\n"
        + "</Dimension>\n"
        + "<Cube name='GdeltLike' defaultMeasure='Unit Sales'>\n"
        + "  <Dimensions>\n"
        + "    <Dimension source='Outlet'/>\n"
        + "  </Dimensions>\n"
        + "  <MeasureGroups>\n"
        + "    <MeasureGroup name='Sales' table='sales_fact_1997'>\n"
        + "      <Measures>\n"
        + "        <Measure name='Unit Sales' column='unit_sales'\n"
        + "                 aggregator='sum'/>\n"
        + "      </Measures>\n"
        + "      <DimensionLinks>\n"
        + "        <ForeignKeyLink dimension='Outlet'\n"
        + "                        foreignKeyColumn='store_id'/>\n"
        + "      </DimensionLinks>\n"
        + "    </MeasureGroup>\n"
        + "    <MeasureGroup name='Inventory' table='inventory_fact_1997'>\n"
        + "      <Measures>\n"
        + "        <Measure name='Warehouse Sales' column='warehouse_sales'\n"
        + "                 aggregator='sum'/>\n"
        + "      </Measures>\n"
        + "      <DimensionLinks>\n"
        + "        <ForeignKeyLink dimension='Outlet'\n"
        + "                        foreignKeyColumn='store_id'/>\n"
        + "      </DimensionLinks>\n"
        + "    </MeasureGroup>\n"
        + "  </MeasureGroups>\n"
        + "</Cube>\n";

    /** Single conformed dimension on rows, Sales-group measure on columns. */
    private static final String SALES_GROUP_MDX =
        "SELECT {[Measures].[Unit Sales]} ON COLUMNS,\n"
        + "       NON EMPTY [Outlet].[Outlet].Members ON ROWS\n"
        + "FROM [GdeltLike]";

    /**
     * Same conformed dimension, but a measure from the <em>other</em>
     * measure group — the read must join the Inventory fact, not Sales.
     * Guards the "wrong fact chosen" concern from issue #89.
     */
    private static final String INVENTORY_GROUP_MDX =
        "SELECT {[Measures].[Warehouse Sales]} ON COLUMNS,\n"
        + "       NON EMPTY [Outlet].[Outlet].Members ON ROWS\n"
        + "FROM [GdeltLike]";

    /**
     * The Saiku Studio shape (issue #89 reopen): the rows axis is a
     * <em>named set</em> referenced with NON EMPTY, rather than an inline
     * set. The set is evaluated first, then NON EMPTY filters it — a
     * different path than the inline form, which still diverged after the
     * 4.8.1.18 fix.
     */
    private static final String NAMED_SET_MDX =
        "WITH SET [~ROWS] AS {[Outlet].[Outlet].Members}\n"
        + "SELECT NON EMPTY {[Measures].[Unit Sales]} ON COLUMNS,\n"
        + "       NON EMPTY [~ROWS] ON ROWS\n"
        + "FROM [GdeltLike]";

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

    private static Connection newConnection() throws Exception {
        String catalog = new String(
            Files.readAllBytes(Paths.get("demo/FoodMart.mondrian.xml")));
        String overlay = catalog.replace(
            "</Schema>", GDELT_LIKE_CUBE + "</Schema>");
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(
            RolapConnectionProperties.CatalogContent.name(), overlay);
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    private static Result executeOn(
        Connection conn, String backend, String mdx)
    {
        System.setProperty("mondrian.backend", backend);
        try {
            Query q = conn.parseQuery(mdx);
            return conn.execute(q);
        } finally {
            System.clearProperty("mondrian.backend");
        }
    }

    private static int rowCount(Result result) {
        Axis[] axes = result.getAxes();
        if (axes.length < 2) {
            return 0;
        }
        return axes[1].getPositions().size();
    }

    private static void assertCalciteMatchesLegacy(String mdx) {
        Result legacy = executeOn(legacyConn, "legacy", mdx);
        Result calcite = executeOn(calciteConn, "calcite", mdx);

        int legacyRows = rowCount(legacy);
        // Sanity: the legacy run must actually produce member rows (more
        // than just the All member), otherwise the fixture isn't exercising
        // the conformed-dimension NON EMPTY read at all.
        assertTrue(
            legacyRows > 1,
            "legacy must return Outlet members, not just the All member");

        assertEquals(
            legacyRows,
            rowCount(calcite),
            "Calcite must return the same number of conformed-dimension "
            + "rows as legacy (issue #89: Calcite silently dropped members)");

        assertEquals(
            TestContext.toString(legacy),
            TestContext.toString(calcite),
            "Calcite result grid must match legacy for a conformed-"
            + "dimension single-axis NON EMPTY read");
    }

    /**
     * The reproduction: a Sales-group measure over the conformed
     * {@code Outlet} dimension. Before the fix, Calcite collapsed this to a
     * join-less dimension enumeration and returned only the All member.
     */
    @Test
    public void conformedDimensionMatchesLegacy_salesGroup() {
        assertCalciteMatchesLegacy(SALES_GROUP_MDX);
    }

    /**
     * The same conformed dimension read against the <em>other</em> measure
     * group's measure must join the Inventory fact and still match legacy.
     */
    @Test
    public void conformedDimensionMatchesLegacy_inventoryGroup() {
        assertCalciteMatchesLegacy(INVENTORY_GROUP_MDX);
    }

    /**
     * Issue #89 reopen: the named-set NON EMPTY shape Saiku Studio emits
     * must match legacy too (inline-set fix in 4.8.1.18 did not cover it).
     */
    @Test
    public void conformedDimensionMatchesLegacy_namedSet() {
        assertCalciteMatchesLegacy(NAMED_SET_MDX);
    }
}
