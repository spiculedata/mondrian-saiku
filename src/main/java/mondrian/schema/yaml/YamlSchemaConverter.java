/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * #34 schema-as-code: convert a YAML schema document into the
 * equivalent Mondrian XML schema string, which the standard Mondrian
 * schema loader then ingests unchanged.
 *
 * <p>Strategy: parse YAML to a tree of generic Maps/Lists via Jackson
 * YAML, walk the tree, emit XML as a StringBuilder. The XML output
 * is byte-fed into Mondrian's existing
 * {@code RolapSchemaLoader}/{@code mondrian.olap.MondrianDef} pipeline
 * — no changes to the runtime schema-loading code required.
 *
 * <h3>Element coverage</h3>
 *
 * <p>Phase 0 (spike): {@code Schema}, {@code Cube}, {@code Table},
 * {@code Dimension}, {@code Hierarchy}, {@code Level}, {@code Measure}.
 *
 * <p>Phase 1 (this revision): adds {@code Annotations} (schema- and
 * cube-scoped), {@code CalculatedMember} (with both inline
 * {@code formula} attribute and {@code <Formula>} child-element forms)
 * including {@code CalculatedMemberProperty} children, plus the
 * agg-table refs that nest inside {@code <Table>}: {@code AggExclude},
 * {@code AggName} (with {@code AggFactCount}, {@code AggIgnoreColumn},
 * {@code AggForeignKey}, {@code AggMeasure}, {@code AggLevel}).
 *
 * <p>Out of scope: {@code DimensionUsage}, {@code MeasureGroup},
 * {@code NamedSet}, {@code Role}/{@code SchemaGrant}/{@code CubeGrant},
 * {@code $ref} includes, the XML→YAML reverse converter, and the
 * mondrian schema lint CLI.
 *
 * <h3>Naming convention</h3>
 *
 * <p>YAML uses snake_case ({@code foreign_key}, {@code unique_members},
 * {@code has_all}); the converter maps to Mondrian's XML camelCase
 * ({@code foreignKey}, {@code uniqueMembers}, {@code hasAll}).
 * {@code CalculatedMemberProperty} property names are passed through
 * verbatim (they're MDX-standard names like {@code FORMAT_STRING},
 * {@code SOLVE_ORDER}, {@code MEMBER_ORDINAL}) — not snake_case-mapped.
 */
public final class YamlSchemaConverter {

    private static final ObjectMapper YAML =
        new ObjectMapper(new YAMLFactory());

    private YamlSchemaConverter() {}

    /**
     * Parses a YAML schema string and returns the equivalent Mondrian
     * XML schema string. The returned XML is suitable for direct
     * ingestion via {@code TestContext.withSchema(String)} or
     * {@code DriverManager.getConnection("Jdbc=...; Catalog=...")}
     * with the XML inlined.
     */
    public static String toXml(String yamlText) {
        try {
            Map<?, ?> root = YAML.readValue(yamlText, Map.class);
            StringBuilder buf = new StringBuilder(1024);
            buf.append("<?xml version=\"1.0\"?>\n");
            buf.append("<Schema name=\"")
                .append(escape(strRequired(root, "schema")))
                .append("\">\n");
            emitAnnotations(buf, mapOrNull(root, "annotations"), "  ");

            Object cubes = root.get("cubes");
            if (cubes instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) cubes).entrySet()) {
                    emitCube(buf,
                        (String) e.getKey(), (Map<?, ?>) e.getValue());
                }
            }
            buf.append("</Schema>\n");
            return buf.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to convert YAML schema to XML: " + e.getMessage(), e);
        }
    }

    private static void emitCube(StringBuilder buf, String name, Map<?, ?> c) {
        buf.append("  <Cube name=\"").append(escape(name)).append("\"");
        String defaultMeasure = strOpt(c, "default_measure");
        if (defaultMeasure != null) {
            buf.append(" defaultMeasure=\"")
                .append(escape(defaultMeasure)).append("\"");
        }
        buf.append(">\n");
        emitAnnotations(buf, mapOrNull(c, "annotations"), "    ");

        emitFactTable(buf, c.get("fact_table"));

        for (Object dim : listOrEmpty(c, "dimensions")) {
            emitDimension(buf, (Map<?, ?>) dim);
        }
        for (Object m : listOrEmpty(c, "measures")) {
            emitMeasure(buf, (Map<?, ?>) m);
        }
        for (Object cm : listOrEmpty(c, "calculated_members")) {
            emitCalculatedMember(buf, (Map<?, ?>) cm);
        }
        buf.append("  </Cube>\n");
    }

    /**
     * The {@code fact_table} key accepts two shapes:
     * <ul>
     *   <li>a bare string — emits a self-closing
     *       {@code <Table name="..."/>}</li>
     *   <li>a map with {@code name} + optional {@code agg_exclude} (list)
     *       + {@code agg_names} (list) — emits an open
     *       {@code <Table>} element with nested agg-table refs</li>
     * </ul>
     */
    private static void emitFactTable(StringBuilder buf, Object factTable) {
        if (factTable == null) {
            throw new IllegalArgumentException(
                "Missing required key: fact_table");
        }
        if (factTable instanceof String) {
            buf.append("    <Table name=\"")
                .append(escape((String) factTable)).append("\"/>\n");
            return;
        }
        if (!(factTable instanceof Map)) {
            throw new IllegalArgumentException(
                "fact_table must be string or map, got: "
                    + factTable.getClass().getName());
        }
        Map<?, ?> ft = (Map<?, ?>) factTable;
        String name = strRequired(ft, "name");
        List<?> excludes = listOrEmpty(ft, "agg_exclude");
        List<?> aggNames = listOrEmpty(ft, "agg_names");
        if (excludes.isEmpty() && aggNames.isEmpty()) {
            buf.append("    <Table name=\"")
                .append(escape(name)).append("\"/>\n");
            return;
        }
        buf.append("    <Table name=\"").append(escape(name)).append("\">\n");
        for (Object x : excludes) {
            buf.append("      <AggExclude name=\"")
                .append(escape(String.valueOf(x))).append("\"/>\n");
        }
        for (Object a : aggNames) {
            emitAggName(buf, (Map<?, ?>) a);
        }
        buf.append("    </Table>\n");
    }

    private static void emitAggName(StringBuilder buf, Map<?, ?> a) {
        buf.append("      <AggName name=\"")
            .append(escape(strRequired(a, "name"))).append("\">\n");
        String fc = strOpt(a, "fact_count_column");
        if (fc != null) {
            buf.append("        <AggFactCount column=\"")
                .append(escape(fc)).append("\"/>\n");
        }
        for (Object ic : listOrEmpty(a, "ignore_columns")) {
            buf.append("        <AggIgnoreColumn column=\"")
                .append(escape(String.valueOf(ic))).append("\"/>\n");
        }
        for (Object fk : listOrEmpty(a, "foreign_keys")) {
            Map<?, ?> fkm = (Map<?, ?>) fk;
            buf.append("        <AggForeignKey factColumn=\"")
                .append(escape(strRequired(fkm, "fact_column")))
                .append("\" aggColumn=\"")
                .append(escape(strRequired(fkm, "agg_column")))
                .append("\"/>\n");
        }
        for (Object m : listOrEmpty(a, "measures")) {
            Map<?, ?> mm = (Map<?, ?>) m;
            buf.append("        <AggMeasure name=\"")
                .append(escape(strRequired(mm, "name")))
                .append("\" column=\"")
                .append(escape(strRequired(mm, "column")))
                .append("\"/>\n");
        }
        for (Object l : listOrEmpty(a, "levels")) {
            Map<?, ?> lm = (Map<?, ?>) l;
            buf.append("        <AggLevel name=\"")
                .append(escape(strRequired(lm, "name")))
                .append("\" column=\"")
                .append(escape(strRequired(lm, "column")))
                .append("\"/>\n");
        }
        buf.append("      </AggName>\n");
    }

    private static void emitDimension(StringBuilder buf, Map<?, ?> d) {
        buf.append("    <Dimension name=\"")
            .append(escape(strRequired(d, "name"))).append("\"");
        attrIfPresent(buf, d, "foreign_key", "foreignKey");
        attrIfPresent(buf, d, "type", "type");
        buf.append(">\n");
        Map<?, ?> h = (Map<?, ?>) d.get("hierarchy");
        if (h != null) {
            emitHierarchy(buf, h);
        }
        buf.append("    </Dimension>\n");
    }

    private static void emitHierarchy(StringBuilder buf, Map<?, ?> h) {
        buf.append("      <Hierarchy");
        attrIfPresent(buf, h, "has_all", "hasAll");
        attrIfPresent(buf, h, "primary_key", "primaryKey");
        attrIfPresent(buf, h, "all_member_name", "allMemberName");
        buf.append(">\n");
        String table = strOpt(h, "table");
        if (table != null) {
            buf.append("        <Table name=\"")
                .append(escape(table)).append("\"/>\n");
        }
        for (Object lvl : listOrEmpty(h, "levels")) {
            emitLevel(buf, (Map<?, ?>) lvl);
        }
        buf.append("      </Hierarchy>\n");
    }

    private static void emitLevel(StringBuilder buf, Map<?, ?> l) {
        buf.append("        <Level");
        attrIfPresent(buf, l, "name", "name");
        attrIfPresent(buf, l, "column", "column");
        attrIfPresent(buf, l, "type", "type");
        attrIfPresent(buf, l, "unique_members", "uniqueMembers");
        attrIfPresent(buf, l, "level_type", "levelType");
        buf.append("/>\n");
    }

    private static void emitMeasure(StringBuilder buf, Map<?, ?> m) {
        buf.append("    <Measure");
        attrIfPresent(buf, m, "name", "name");
        attrIfPresent(buf, m, "column", "column");
        attrIfPresent(buf, m, "aggregator", "aggregator");
        attrIfPresent(buf, m, "format_string", "formatString");
        attrIfPresent(buf, m, "datatype", "datatype");
        attrIfPresent(buf, m, "visible", "visible");
        buf.append("/>\n");
    }

    /**
     * Emits a {@code <CalculatedMember>}. The YAML supports two ways
     * of supplying the MDX expression:
     * <ul>
     *   <li>{@code formula: ...} — emitted as an XML attribute (compact;
     *       suits one-line expressions).</li>
     *   <li>{@code formula_body: ...} — emitted as a {@code <Formula>}
     *       child element (suits multi-line MDX preserving whitespace).</li>
     * </ul>
     * Both forms are valid Mondrian XML — the schema parser handles
     * either shape.
     */
    private static void emitCalculatedMember(StringBuilder buf, Map<?, ?> cm) {
        buf.append("    <CalculatedMember");
        attrIfPresent(buf, cm, "name", "name");
        attrIfPresent(buf, cm, "dimension", "dimension");
        attrIfPresent(buf, cm, "caption", "caption");
        attrIfPresent(buf, cm, "visible", "visible");
        attrIfPresent(buf, cm, "formula", "formula");
        Map<?, ?> props = mapOrNull(cm, "properties");
        String formulaBody = strOpt(cm, "formula_body");
        boolean hasChildren = props != null || formulaBody != null;
        if (!hasChildren) {
            buf.append("/>\n");
            return;
        }
        buf.append(">\n");
        if (formulaBody != null) {
            buf.append("      <Formula>")
                .append(escape(formulaBody.trim()))
                .append("</Formula>\n");
        }
        if (props != null) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                buf.append("      <CalculatedMemberProperty name=\"")
                    .append(escape(String.valueOf(e.getKey())))
                    .append("\" value=\"")
                    .append(escape(String.valueOf(e.getValue())))
                    .append("\"/>\n");
            }
        }
        buf.append("    </CalculatedMember>\n");
    }

    /**
     * Emits an {@code <Annotations>} block from a YAML map of
     * {@code name → value}. The Mondrian schema accepts annotations
     * at the {@code Schema}, {@code Cube}, {@code Dimension},
     * {@code Hierarchy}, {@code Level}, and {@code Measure} levels;
     * this method handles all of them via the {@code indent} arg.
     */
    private static void emitAnnotations(
        StringBuilder buf, Map<?, ?> ann, String indent)
    {
        if (ann == null || ann.isEmpty()) {
            return;
        }
        buf.append(indent).append("<Annotations>\n");
        for (Map.Entry<?, ?> e : ann.entrySet()) {
            buf.append(indent).append("  <Annotation name=\"")
                .append(escape(String.valueOf(e.getKey())))
                .append("\">")
                .append(escape(String.valueOf(e.getValue())))
                .append("</Annotation>\n");
        }
        buf.append(indent).append("</Annotations>\n");
    }

    // ---------- helpers ----------

    private static String strRequired(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required key: " + key);
        }
        return String.valueOf(v);
    }

    private static String strOpt(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static List<?> listOrEmpty(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<?>) v : Collections.emptyList();
    }

    private static Map<?, ?> mapOrNull(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<?, ?>) v : null;
    }

    /**
     * Append {@code xmlAttr="value"} to {@code buf} iff {@code yamlKey}
     * is present in {@code m}. Booleans/numbers are rendered as their
     * string form.
     */
    private static void attrIfPresent(
        StringBuilder buf, Map<?, ?> m, String yamlKey, String xmlAttr)
    {
        Object v = m.get(yamlKey);
        if (v == null) {
            return;
        }
        buf.append(' ').append(xmlAttr).append("=\"")
            .append(escape(String.valueOf(v))).append("\"");
    }

    /** Minimal XML attribute escaping. */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
            case '&':  out.append("&amp;"); break;
            case '<':  out.append("&lt;"); break;
            case '>':  out.append("&gt;"); break;
            case '"':  out.append("&quot;"); break;
            case '\'': out.append("&apos;"); break;
            default:   out.append(c);
            }
        }
        return out.toString();
    }
}

// End YamlSchemaConverter.java
