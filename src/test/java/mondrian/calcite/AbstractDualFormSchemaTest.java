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
import mondrian.olap.Position;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.schema.yaml.m4.M4XmlToYaml;
import mondrian.schema.yaml.m4.M4YamlToXml;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Base for tests that must produce <b>identical query results</b> whether the
 * schema is loaded from its M4 <b>XML</b> or from the <b>YAML</b> equivalent.
 *
 * <p>Each subclass supplies one M4 XML schema + its DDL; this harness boots
 * two HSQLDB-backed Mondrian connections from it — one from the XML verbatim,
 * one from the YAML round-trip ({@code XML → YAML → XML}) — and exposes them
 * by form name. Subclass tests are {@code @ParameterizedTest}s over
 * {@link #forms()} ({@code "xml"} / {@code "yaml"}) that run the same MDX
 * against {@link #conn(String)} and assert the same numbers, so any converter
 * data-loss that changes a cell value is caught (not just structural drift).
 *
 * <p>The connection map is held per-subclass (not in this base) so concurrent
 * subclasses in one JVM do not clobber each other.
 */
abstract class AbstractDualFormSchemaTest {

    /** The two schema forms each parameterized test runs against. */
    static Stream<String> forms() {
        return Stream.of("xml", "yaml");
    }

    /** Connection for the given form ({@code "xml"} / {@code "yaml"}). */
    protected abstract Connection conn(String form);

    /**
     * Run the DDL once, then build an xml-form and a yaml-form connection
     * from {@code xmlSchema}. The yaml form is {@code XML → YAML → XML} — so
     * loading it exercises the YAML representation end to end.
     */
    protected static Map<String, Connection> bootForms(
        String[] ddl, String xmlSchema) throws Exception
    {
        FoodMartHsqldbBootstrap.ensureExtracted();
        Util.PropertyList base =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(
                     base.get("Jdbc"), base.get("JdbcUser"),
                     base.get("JdbcPassword"));
             Statement st = c.createStatement())
        {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        Map<String, Connection> conns = new LinkedHashMap<>();
        conns.put("xml", connect(xmlSchema));
        conns.put("yaml",
            connect(M4YamlToXml.toXml(M4XmlToYaml.toYaml(xmlSchema))));
        return conns;
    }

    private static Connection connect(String catalog) {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    protected static void closeForms(Map<String, Connection> conns) {
        if (conns != null) {
            for (Connection c : conns.values()) {
                if (c != null) {
                    c.close();
                }
            }
            conns.clear();
        }
    }

    // ---- shared query helpers (all take the form, resolve via conn) ----

    /** Row caption (axis 1, member 0) → cell value (measure on axis 0). */
    protected Map<String, Double> rowMap(String form, String mdx) {
        Connection conn = conn(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Map<String, Double> out = new LinkedHashMap<>();
        Axis rows = r.getAxes()[1];
        int i = 0;
        for (Position pos : rows.getPositions()) {
            Object v = r.getCell(new int[]{0, i}).getValue();
            out.put(
                pos.get(0).getName(),
                v == null ? null : ((Number) v).doubleValue());
            i++;
        }
        r.close();
        return out;
    }

    /** Single cell from a COLUMNS-only query ({@code getCell([0])}). */
    protected Double scalar(String form, String mdx) {
        Connection conn = conn(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    /** {@code getCell([0,0])} of a two-axis query. */
    protected Double cell00(String form, String mdx) {
        Connection conn = conn(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0, 0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    /** "rowCaption|colCaption" → value for a 2-axis grid. */
    protected Map<String, Double> grid(String form, String mdx) {
        Connection conn = conn(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Map<String, Double> out = new LinkedHashMap<>();
        Axis cols = r.getAxes()[0];
        Axis rows = r.getAxes()[1];
        int ri = 0;
        for (Position rp : rows.getPositions()) {
            int ci = 0;
            for (Position cp : cols.getPositions()) {
                Object v = r.getCell(new int[]{ci, ri}).getValue();
                out.put(
                    rp.get(0).getName() + "|" + cp.get(0).getName(),
                    v == null ? null : ((Number) v).doubleValue());
                ci++;
            }
            ri++;
        }
        r.close();
        return out;
    }

    /** Pipe-joined caption of every member in each ROW tuple → value (for a
     *  crossjoin on the row axis). */
    protected Map<String, Double> rowTupleMap(String form, String mdx) {
        Connection conn = conn(form);
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Map<String, Double> out = new LinkedHashMap<>();
        Axis rows = r.getAxes()[1];
        int i = 0;
        for (Position pos : rows.getPositions()) {
            StringBuilder key = new StringBuilder();
            for (int p = 0; p < pos.size(); p++) {
                if (p > 0) {
                    key.append("|");
                }
                key.append(pos.get(p).getName());
            }
            Object v = r.getCell(new int[]{0, i}).getValue();
            out.put(
                key.toString(),
                v == null ? null : ((Number) v).doubleValue());
            i++;
        }
        r.close();
        return out;
    }
}
