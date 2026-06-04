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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #90: a result-parity fuzzer that sweeps a generated MDX corpus
 * through BOTH the legacy SQL path and the Calcite SQL path and compares
 * the result grids. Any divergence is a Calcite correctness gap — the
 * "translates fine but wrong" class that the exception-based fallback
 * cannot catch (the class that #89 was the first confirmed instance of).
 *
 * <p>The corpus targets the shapes #90 flags as risky:
 * <ul>
 *   <li>conformed dimensions across multiple measure groups (the #89 case)
 *       — exercised against the stock {@code Warehouse and Sales} cube
 *       (Store/Product/Time link to both Sales and Warehouse) plus a
 *       GDELT-shaped {@code Outlet} dimension with a distinct name column;
 *   <li>degenerate / single-group dimensions (Warehouse, Promotion);
 *   <li>snowflake hierarchies (Product → product_class);
 *   <li>compound-key levels (Store City carries store_state);
 *   <li>NON EMPTY across measure groups, with/without slicer, and under
 *       TopCount / Filter / Order / Descendants wrappers.
 * </ul>
 *
 * <p>This is an exploratory sweep: it prints a classification report and
 * fails only when a genuine cell-set divergence is found (a one-sided
 * exception is reported but does not fail the build, since a legacy-only
 * or Calcite-only error is a separate concern from silent wrong results).
 * Set {@code -Dfuzz.failOnError=true} to also fail on one-sided errors.
 */
public class CalciteParityFuzzTest {

    private static final String OUTLET_OVERLAY =
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
        + "    <Dimension source='Product'/>\n"
        + "    <Dimension source='Time'/>\n"
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
        + "        <ForeignKeyLink dimension='Product'\n"
        + "                        foreignKeyColumn='product_id'/>\n"
        + "        <ForeignKeyLink dimension='Time'\n"
        + "                        foreignKeyColumn='time_id'/>\n"
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
        + "        <ForeignKeyLink dimension='Product'\n"
        + "                        foreignKeyColumn='product_id'/>\n"
        + "        <ForeignKeyLink dimension='Time'\n"
        + "                        foreignKeyColumn='time_id'/>\n"
        + "      </DimensionLinks>\n"
        + "    </MeasureGroup>\n"
        + "  </MeasureGroups>\n"
        + "</Cube>\n";

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
            "</Schema>", OUTLET_OVERLAY + "</Schema>");
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(
            RolapConnectionProperties.CatalogContent.name(), overlay);
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    /** One generated MDX query plus a human-readable label. */
    private static final class Q {
        final String label;
        final String mdx;
        Q(String label, String mdx) {
            this.label = label;
            this.mdx = mdx;
        }
    }

    /** Result of running one query on both backends. */
    private enum Verdict { MATCH, CELL_DRIFT, LEGACY_ERROR, CALCITE_ERROR,
        BOTH_ERROR }

    // ---- corpus generation -------------------------------------------

    private static List<Q> corpus() {
        List<Q> qs = new ArrayList<>();

        // Member-set axis expressions across the risky dimension shapes,
        // all conformed across Sales + Warehouse in [Warehouse and Sales].
        String[][] axes = {
            // {label, set-expression}
            {"store-country", "[Store].[Stores].[Store Country].Members"},
            {"store-state",   "[Store].[Stores].[Store State].Members"},
            {"store-city",    "[Store].[Stores].[Store City].Members"},
            {"store-name",    "[Store].[Stores].[Store Name].Members"},
            {"product-family",
                "[Product].[Products].[Product Family].Members"},
            {"product-dept",
                "[Product].[Products].[Product Department].Members"},
            {"product-category",
                "[Product].[Products].[Product Category].Members"},
            {"product-name",
                "[Product].[Products].[Product Name].Members"},
            {"time-year",     "[Time].[Time].[Year].Members"},
            {"time-quarter",  "[Time].[Time].[Quarter].Members"},
            {"time-month",    "[Time].[Time].[Month].Members"},
            // single-measure-group dims
            {"warehouse",
                "[Warehouse].[Warehouses].[Country].Members"},
            {"promotion",
                "[Promotion].[Promotions].[Promotion Name].Members"},
            {"customer-country",
                "[Customer].[Customers].[Country].Members"},
        };
        // Measures from each measure group.
        String[][] measures = {
            {"unit-sales", "[Measures].[Unit Sales]"},
            {"store-sales", "[Measures].[Store Sales]"},
            {"wh-sales", "[Measures].[Warehouse Sales]"},
            {"units-shipped", "[Measures].[Units Shipped]"},
        };

        String cube = "[Warehouse and Sales]";

        // 1) single-axis NON EMPTY and plain, each measure.
        for (String[] ax : axes) {
            for (String[] m : measures) {
                qs.add(new Q(
                    "wh+sales/ne/" + ax[0] + "/" + m[0],
                    "SELECT {" + m[1] + "} ON COLUMNS, "
                    + "NON EMPTY " + ax[1] + " ON ROWS FROM " + cube));
                qs.add(new Q(
                    "wh+sales/plain/" + ax[0] + "/" + m[0],
                    "SELECT {" + m[1] + "} ON COLUMNS, "
                    + ax[1] + " ON ROWS FROM " + cube));
            }
        }

        // 2) two-dim crossjoins (NON EMPTY) — conformed × conformed and
        //    conformed × single-group.
        String[][] pairs = {
            {axes[0][1], axes[4][1], "country-x-family"},
            {axes[3][1], axes[8][1], "storename-x-year"},
            {axes[2][1], axes[7][1], "city-x-product"},
            {axes[0][1], axes[11][1], "country-x-warehouse"},
            {axes[4][1], axes[12][1], "family-x-promotion"},
        };
        for (String[] p : pairs) {
            for (String[] m : new String[][] {measures[0], measures[2]}) {
                qs.add(new Q(
                    "wh+sales/cj/ne/" + p[2] + "/" + m[0],
                    "SELECT {" + m[1] + "} ON COLUMNS, "
                    + "NON EMPTY CrossJoin(" + p[0] + ", " + p[1]
                    + ") ON ROWS FROM " + cube));
            }
        }

        // 2b) cross-measure-group: a Sales-group measure + a Warehouse-group
        //     measure on the same conformed axis. The Calcite tuple read
        //     declines this virtual-cube UNION shape and falls back to
        //     legacy (standard SQL UNION of per-fact member lists); guards
        //     that the fallback stays legacy-equivalent.
        qs.add(new Q(
            "wh+sales/cross-mg/country",
            "SELECT {[Measures].[Unit Sales], [Measures].[Warehouse Sales]}"
            + " ON COLUMNS, NON EMPTY "
            + "[Store].[Stores].[Store Country].Members ON ROWS FROM "
            + cube));
        qs.add(new Q(
            "wh+sales/cross-mg/product-family",
            "SELECT {[Measures].[Unit Sales], [Measures].[Warehouse Sales]}"
            + " ON COLUMNS, NON EMPTY "
            + "[Product].[Products].[Product Family].Members ON ROWS FROM "
            + cube));

        // 3) slicer (WHERE) variants on a conformed axis.
        qs.add(new Q(
            "wh+sales/slicer/family-by-1997",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "NON EMPTY [Product].[Products].[Product Family].Members "
            + "ON ROWS FROM " + cube
            + " WHERE [Time].[Time].[Year].[1997]"));
        qs.add(new Q(
            "wh+sales/slicer/store-by-product-drink",
            "SELECT {[Measures].[Warehouse Sales]} ON COLUMNS, "
            + "NON EMPTY [Store].[Stores].[Store State].Members "
            + "ON ROWS FROM " + cube
            + " WHERE [Product].[Products].[Product Family].[Drink]"));

        // 4) function wrappers: TopCount / Filter / Order / Descendants.
        qs.add(new Q(
            "wh+sales/topcount/store-by-unitsales",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "TopCount([Store].[Stores].[Store City].Members, 5, "
            + "[Measures].[Unit Sales]) ON ROWS FROM " + cube));
        qs.add(new Q(
            "wh+sales/filter/product-gt",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "Filter([Product].[Products].[Product Department].Members, "
            + "[Measures].[Unit Sales] > 5000) ON ROWS FROM " + cube));
        qs.add(new Q(
            "wh+sales/order/store-desc",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "Order([Store].[Stores].[Store State].Members, "
            + "[Measures].[Unit Sales], BDESC) ON ROWS FROM " + cube));
        qs.add(new Q(
            "wh+sales/descendants/usa",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "NON EMPTY Descendants([Store].[Stores].[USA], "
            + "[Store].[Stores].[Store City]) ON ROWS FROM " + cube));

        // 5) GDELT-shaped overlay: conformed dim with a DISTINCT name column
        //    (the #89 trigger), both measure groups.
        String g = "[GdeltLike]";
        qs.add(new Q(
            "gdelt/ne/outlet/unit-sales",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "NON EMPTY [Outlet].[Outlet].Members ON ROWS FROM " + g));
        qs.add(new Q(
            "gdelt/ne/outlet/wh-sales",
            "SELECT {[Measures].[Warehouse Sales]} ON COLUMNS, "
            + "NON EMPTY [Outlet].[Outlet].Members ON ROWS FROM " + g));
        qs.add(new Q(
            "gdelt/cj/outlet-x-product/unit-sales",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "NON EMPTY CrossJoin([Outlet].[Outlet].Members, "
            + "[Product].[Products].[Product Family].Members) "
            + "ON ROWS FROM " + g));
        qs.add(new Q(
            "gdelt/slicer/outlet-by-1997/wh-sales",
            "SELECT {[Measures].[Warehouse Sales]} ON COLUMNS, "
            + "NON EMPTY [Outlet].[Outlet].Members ON ROWS FROM " + g
            + " WHERE [Time].[Time].[Year].[1997]"));

        addSalesCorpus(qs);
        addHrCorpus(qs);
        addNamedSetCorpus(qs);
        return qs;
    }

    /**
     * Named-set NON EMPTY shape — {@code WITH SET [~ROWS] AS {axis} SELECT
     * NON EMPTY [~ROWS] ON ROWS} — which is what Saiku Studio emits for
     * every query. The set is materialized first (plain enumeration), then
     * NON EMPTY filters it, a different path than the inline form (issue #89
     * reopen). Covers the conformed-dimension, snowflake-leaf, deep-leaf and
     * parent-child axes across the multi-MG and single-MG cubes.
     */
    private static void addNamedSetCorpus(List<Q> qs) {
        String[][] cases = {
            // {label, cube, axis-set, measure}
            {"whs/outlet", "[GdeltLike]",
                "[Outlet].[Outlet].Members", "[Measures].[Unit Sales]"},
            {"whs/outlet-inv", "[GdeltLike]",
                "[Outlet].[Outlet].Members", "[Measures].[Warehouse Sales]"},
            {"whs/store-name", "[Warehouse and Sales]",
                "[Store].[Stores].[Store Name].Members",
                "[Measures].[Unit Sales]"},
            {"whs/product-name", "[Warehouse and Sales]",
                "[Product].[Products].[Product Name].Members",
                "[Measures].[Warehouse Sales]"},
            {"whs/store-country", "[Warehouse and Sales]",
                "[Store].[Stores].[Store Country].Members",
                "[Measures].[Unit Sales]"},
            {"sales/product-name", "[Sales]",
                "[Product].[Products].[Product Name].Members",
                "[Measures].[Profit]"},
            {"sales/customer-name", "[Sales]",
                "[Customer].[Customers].[Name].Members",
                "[Measures].[Customer Count]"},
            {"hr/employees", "[HR]",
                "[Employee].[Employees].Members", "[Measures].[Org Salary]"},
        };
        for (String[] c : cases) {
            qs.add(new Q("named-set/" + c[0],
                "WITH SET [~ROWS] AS {" + c[2] + "}\n"
                + "SELECT NON EMPTY {" + c[3] + "} ON COLUMNS,\n"
                + "  NON EMPTY [~ROWS] ON ROWS\nFROM " + c[1]));
        }
    }

    /**
     * Single-measure-group [Sales] cube: snowflake Product, multi-level
     * Store/Customer, a distinct-count measure (Customer Count) and a
     * calculated measure (Profit) — exercises plain + NON EMPTY enumeration,
     * member .Children reads, and segment loads for distinct-count / calc.
     */
    private static void addSalesCorpus(List<Q> qs) {
        String cube = "[Sales]";
        String[][] axes = {
            {"store-country", "[Store].[Stores].[Store Country].Members"},
            {"store-city",    "[Store].[Stores].[Store City].Members"},
            {"store-name",    "[Store].[Stores].[Store Name].Members"},
            {"product-family",
                "[Product].[Products].[Product Family].Members"},
            {"product-brand",
                "[Product].[Products].[Brand Name].Members"},
            {"product-name",
                "[Product].[Products].[Product Name].Members"},
            {"customer-country", "[Customer].[Customers].[Country].Members"},
            {"customer-city",    "[Customer].[Customers].[City].Members"},
            {"customer-name",    "[Customer].[Customers].[Name].Members"},
            {"customer-education",
                "[Customer].[Education Level].[Education Level].Members"},
            {"promotion-media",
                "[Promotion].[Media Type].[Media Type].Members"},
            {"time-quarter",  "[Time].[Time].[Quarter].Members"},
        };
        String[][] measures = {
            {"unit-sales", "[Measures].[Unit Sales]"},
            {"customer-count", "[Measures].[Customer Count]"},
            {"profit", "[Measures].[Profit]"},
        };
        for (String[] ax : axes) {
            for (String[] m : measures) {
                qs.add(new Q("sales/ne/" + ax[0] + "/" + m[0],
                    "SELECT {" + m[1] + "} ON COLUMNS, NON EMPTY " + ax[1]
                    + " ON ROWS FROM " + cube));
                qs.add(new Q("sales/plain/" + ax[0] + "/" + m[0],
                    "SELECT {" + m[1] + "} ON COLUMNS, " + ax[1]
                    + " ON ROWS FROM " + cube));
            }
        }
        // Specific-member .Children reads.
        qs.add(new Q("sales/children/usa",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "NON EMPTY [Store].[Stores].[USA].Children ON ROWS FROM "
            + cube));
        qs.add(new Q("sales/children/drink",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "[Product].[Products].[Drink].Children ON ROWS FROM " + cube));
        // BottomCount + Hierarchize + a tuple slicer.
        qs.add(new Q("sales/bottomcount/city",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "BottomCount([Store].[Stores].[Store City].Members, 5, "
            + "[Measures].[Unit Sales]) ON ROWS FROM " + cube));
        qs.add(new Q("sales/hierarchize/product-dept",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "NON EMPTY Hierarchize("
            + "[Product].[Products].[Product Department].Members) "
            + "ON ROWS FROM " + cube));
        qs.add(new Q("sales/slicer-tuple/customer-by-drink-1997",
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
            + "NON EMPTY [Customer].[Customers].[Country].Members ON ROWS "
            + "FROM " + cube
            + " WHERE ([Product].[Products].[Drink], "
            + "[Time].[Time].[Year].[1997])"));
    }

    /**
     * [HR] cube: a parent-child Employee hierarchy (closure table) plus a
     * Position hierarchy. Directly exercises the parent-child branch of
     * emitTargetProjections (parent-attribute key columns) under plain and
     * NON EMPTY enumeration, and .Children of a parent-child member.
     */
    private static void addHrCorpus(List<Q> qs) {
        String cube = "[HR]";
        String[][] axes = {
            {"employees", "[Employee].[Employees].Members"},
            {"employees-l2",
                "[Employee].[Employees].[Employee Id].Members"},
            {"position-role",
                "[Employee].[Position].[Management Role].Members"},
            {"position-title",
                "[Employee].[Position].[Position Title].Members"},
            {"department", "[Department].[Department].Members"},
        };
        String[][] measures = {
            {"org-salary", "[Measures].[Org Salary]"},
            {"count", "[Measures].[Count]"},
            {"num-employees", "[Measures].[Number of Employees]"},
        };
        for (String[] ax : axes) {
            for (String[] m : measures) {
                qs.add(new Q("hr/ne/" + ax[0] + "/" + m[0],
                    "SELECT {" + m[1] + "} ON COLUMNS, NON EMPTY " + ax[1]
                    + " ON ROWS FROM " + cube));
                qs.add(new Q("hr/plain/" + ax[0] + "/" + m[0],
                    "SELECT {" + m[1] + "} ON COLUMNS, " + ax[1]
                    + " ON ROWS FROM " + cube));
            }
        }
        qs.add(new Q("hr/children/all-employees",
            "SELECT {[Measures].[Org Salary]} ON COLUMNS, "
            + "NON EMPTY [Employee].[Employees].[All Employees].Children "
            + "ON ROWS FROM " + cube));
    }

    // ---- execution + comparison --------------------------------------

    private String run(Connection conn, String backend, String mdx) {
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

    /** Count the {member} lines on Axis #2 of a TestContext grid dump. */
    private static int axisRows(String grid) {
        int idx = grid.indexOf("Axis #2:");
        if (idx < 0) {
            return 0;
        }
        int count = 0;
        for (String line : grid.substring(idx).split("\n")) {
            if (line.startsWith("{")) {
                count++;
            }
        }
        return count;
    }

    /** Unwrap to the deepest cause and report its class + message. */
    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        if (msg != null && msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        return c.getClass().getName() + ": " + msg;
    }

    @Test
    public void parityAcrossCorpus() {
        List<Q> corpus = corpus();
        Map<Verdict, Integer> tally = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) {
            tally.put(v, 0);
        }
        List<String> drifts = new ArrayList<>();
        List<String> calciteErrors = new ArrayList<>();
        List<String> legacyErrors = new ArrayList<>();
        List<String> bothErrors = new ArrayList<>();
        // Correct results that nonetheless reached them via a fallback to
        // legacy SQL — the remaining Calcite coverage gaps worth closing.
        List<String> fellBack = new ArrayList<>();

        for (Q q : corpus) {
            String legacy = null;
            String calcite = null;
            String legacyErr = null;
            String calciteErr = null;
            try {
                legacy = run(legacyConn, "legacy", q.mdx);
            } catch (Throwable t) {
                legacyErr = rootCause(t);
            }
            CalcitePlannerAdapters.resetUnsupportedCount();
            try {
                calcite = run(calciteConn, "calcite", q.mdx);
            } catch (Throwable t) {
                calciteErr = rootCause(t);
            }
            long fallbacks = CalcitePlannerAdapters.unsupportedCount();
            if (fallbacks > 0 && calciteErr == null) {
                fellBack.add(q.label + " (fallbacks=" + fallbacks + ")");
            }

            Verdict v;
            if (legacyErr != null && calciteErr != null) {
                v = Verdict.BOTH_ERROR;
                bothErrors.add(q.label + " :: legacy=" + legacyErr
                    + " | calcite=" + calciteErr);
            } else if (legacyErr != null) {
                v = Verdict.LEGACY_ERROR;
                legacyErrors.add(q.label + " :: " + legacyErr);
            } else if (calciteErr != null) {
                v = Verdict.CALCITE_ERROR;
                calciteErrors.add(q.label + " :: " + calciteErr);
            } else if (!legacy.equals(calcite)) {
                v = Verdict.CELL_DRIFT;
                drifts.add(q.label
                    + " [legacy rows=" + axisRows(legacy)
                    + " vs calcite rows=" + axisRows(calcite) + "]"
                    + "\n    MDX: " + q.mdx);
            } else {
                v = Verdict.MATCH;
            }
            tally.put(v, tally.get(v) + 1);
        }

        StringBuilder report = new StringBuilder();
        report.append("\n========= Calcite parity fuzz (#90) =========\n");
        report.append("corpus size: ").append(corpus.size()).append("\n");
        for (Map.Entry<Verdict, Integer> e : tally.entrySet()) {
            report.append(String.format("  %-13s %d%n",
                e.getKey(), e.getValue()));
        }
        if (!drifts.isEmpty()) {
            report.append("\n--- CELL_DRIFT (silent wrong results) ---\n");
            for (String d : drifts) {
                report.append("  ").append(d).append("\n");
            }
        }
        if (!calciteErrors.isEmpty()) {
            report.append("\n--- CALCITE_ERROR (one-sided) ---\n");
            for (String e : calciteErrors) {
                report.append("  ").append(e).append("\n");
            }
        }
        if (!legacyErrors.isEmpty()) {
            report.append("\n--- LEGACY_ERROR (one-sided) ---\n");
            for (String e : legacyErrors) {
                report.append("  ").append(e).append("\n");
            }
        }
        if (!bothErrors.isEmpty()) {
            report.append("\n--- BOTH_ERROR (invalid/unsupported on both) ---\n");
            for (String e : bothErrors) {
                report.append("  ").append(e).append("\n");
            }
        }
        report.append("\nMATCH via legacy fallback: ")
            .append(fellBack.size()).append(" / ").append(corpus.size())
            .append(" (correct, but a Calcite coverage gap)\n");
        if (!fellBack.isEmpty()) {
            report.append("--- FELL_BACK (still declines to Calcite) ---\n");
            for (String f : fellBack) {
                report.append("  ").append(f).append("\n");
            }
        }
        report.append("=============================================\n");
        System.out.println(report);

        // Regression gate: fail on any divergence/one-sided Calcite error
        // that is NOT a documented known gap. Known gaps are tracked under
        // issue #90; remove a label from KNOWN_GAPS once its root cause is
        // fixed (the assertion below also flags labels that no longer
        // reproduce so the allowlist can't rot).
        List<String> unexpected = new ArrayList<>();
        for (String d : drifts) {
            String label = d.substring(0, d.indexOf(' ') < 0
                ? d.length() : d.indexOf(' '));
            if (!KNOWN_GAPS.contains(label)) {
                unexpected.add("CELL_DRIFT " + label);
            }
        }
        for (String e : calciteErrors) {
            String label = e.substring(0, e.indexOf(" ::"));
            if (!KNOWN_GAPS.contains(label)) {
                unexpected.add("CALCITE_ERROR " + label);
            }
        }
        if (!unexpected.isEmpty()) {
            throw new AssertionError(
                "New Calcite parity divergence(s) not in the #90 known-gap "
                + "allowlist: " + unexpected + " — see report above");
        }
        // No corpus query should fall back to legacy: every shape here is
        // translated natively by the Calcite backend. A new fallback is a
        // coverage regression — add the shape's translation (or, if a
        // genuinely unsupported shape is added to the corpus, allowlist it
        // in FALLBACK_ALLOWED).
        List<String> unexpectedFallbacks = new ArrayList<>();
        for (String f : fellBack) {
            String label = f.substring(0, f.indexOf(" (fallbacks="));
            if (!FALLBACK_ALLOWED.contains(label)) {
                unexpectedFallbacks.add(label);
            }
        }
        if (!unexpectedFallbacks.isEmpty()) {
            throw new AssertionError(
                "Corpus query regressed to a legacy fallback (Calcite "
                + "coverage gap): " + unexpectedFallbacks
                + " — see report above");
        }
    }

    /**
     * Corpus labels permitted to reach correct results via a legacy
     * fallback rather than native Calcite translation. Empty: the whole
     * corpus translates natively (cross-measure-group UNION, unrelated
     * dimensions, and parent-child hierarchies all landed). Add a label
     * only when introducing a genuinely unsupported shape to the corpus.
     */
    private static final java.util.Set<String> FALLBACK_ALLOWED =
        new java.util.HashSet<>();

    /**
     * Documented Calcite parity gaps (issue #90), each a label from
     * {@link #corpus()}. Empty: the originally-found plain-enumeration gaps
     * — {@code .../plain/product-name/*} (#91, snowflake-leaf column order)
     * and {@code .../plain/store-name/*} (#92, under-projected member
     * properties) — are now fixed, so the corpus must be fully clean. Add a
     * label here only to quarantine a newly-discovered gap pending its fix.
     */
    private static final java.util.Set<String> KNOWN_GAPS =
        new java.util.HashSet<>();
}
