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

            // Shared (schema-scoped) dimensions land before cubes so the
            // cubes' DimensionUsage refs can resolve them — order matters
            // to MondrianDef.
            Map<?, ?> sharedDims = mapOrNull(root, "shared_dimensions");
            if (sharedDims != null) {
                for (Map.Entry<?, ?> e : sharedDims.entrySet()) {
                    emitSharedDimension(buf,
                        (String) e.getKey(), (Map<?, ?>) e.getValue());
                }
            }

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

        for (Object du : listOrEmpty(c, "dimension_usages")) {
            emitDimensionUsage(buf, (Map<?, ?>) du);
        }
        for (Object dim : listOrEmpty(c, "dimensions")) {
            emitDimension(buf, (Map<?, ?>) dim, "    ");
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

    /**
     * Schema-scoped shared dimension. Identical in body to the
     * cube-scoped form except: (a) two-space outer indent, (b) takes
     * its name from the YAML map key (not a {@code name:} field), and
     * (c) MUST NOT carry {@code foreignKey} (that belongs on the
     * {@code DimensionUsage}, not the shared dim).
     */
    private static void emitSharedDimension(
        StringBuilder buf, String name, Map<?, ?> d)
    {
        buf.append("  <Dimension name=\"").append(escape(name)).append("\"");
        attrIfPresent(buf, d, "type", "type");
        buf.append(">\n");
        emitDimensionBody(buf, d, "    ");
        buf.append("  </Dimension>\n");
    }

    /**
     * Cube-scoped private dimension. Differs from a shared dimension
     * in carrying {@code foreignKey} and indenting one level deeper.
     */
    private static void emitDimension(
        StringBuilder buf, Map<?, ?> d, String indent)
    {
        buf.append(indent).append("<Dimension name=\"")
            .append(escape(strRequired(d, "name"))).append("\"");
        attrIfPresent(buf, d, "foreign_key", "foreignKey");
        attrIfPresent(buf, d, "type", "type");
        buf.append(">\n");
        emitDimensionBody(buf, d, indent + "  ");
        buf.append(indent).append("</Dimension>\n");
    }

    /**
     * Common dimension body (one or more hierarchies). Accepts either
     * a single {@code hierarchy:} map or a {@code hierarchies:} list
     * (multi-hierarchy dimensions like FoodMart {@code Time}).
     */
    private static void emitDimensionBody(
        StringBuilder buf, Map<?, ?> d, String indent)
    {
        Map<?, ?> single = mapOrNull(d, "hierarchy");
        if (single != null) {
            emitHierarchy(buf, single, indent);
        }
        for (Object h : listOrEmpty(d, "hierarchies")) {
            emitHierarchy(buf, (Map<?, ?>) h, indent);
        }
    }

    private static void emitHierarchy(
        StringBuilder buf, Map<?, ?> h, String indent)
    {
        buf.append(indent).append("<Hierarchy");
        attrIfPresent(buf, h, "name", "name");
        attrIfPresent(buf, h, "has_all", "hasAll");
        attrIfPresent(buf, h, "primary_key", "primaryKey");
        attrIfPresent(buf, h, "primary_key_table", "primaryKeyTable");
        attrIfPresent(buf, h, "all_member_name", "allMemberName");
        attrIfPresent(buf, h, "default_member", "defaultMember");
        buf.append(">\n");
        String inner = indent + "  ";
        String table = strOpt(h, "table");
        Map<?, ?> join = mapOrNull(h, "join");
        if (join != null) {
            emitJoin(buf, join, inner);
        } else if (table != null) {
            buf.append(inner).append("<Table name=\"")
                .append(escape(table)).append("\"/>\n");
        }
        for (Object lvl : listOrEmpty(h, "levels")) {
            emitLevel(buf, (Map<?, ?>) lvl, inner);
        }
        buf.append(indent).append("</Hierarchy>\n");
    }

    /**
     * Two-way {@code <Join>} (FoodMart's standard shape — product +
     * product_class). The YAML {@code tables} list MUST contain
     * exactly two entries, each either a bare string (table name) or
     * a map with at least {@code name}.
     */
    private static void emitJoin(
        StringBuilder buf, Map<?, ?> join, String indent)
    {
        buf.append(indent).append("<Join");
        attrIfPresent(buf, join, "left_alias", "leftAlias");
        attrIfPresent(buf, join, "left_key", "leftKey");
        attrIfPresent(buf, join, "right_alias", "rightAlias");
        attrIfPresent(buf, join, "right_key", "rightKey");
        buf.append(">\n");
        String inner = indent + "  ";
        List<?> tables = listOrEmpty(join, "tables");
        if (tables.size() != 2) {
            throw new IllegalArgumentException(
                "join.tables must have exactly 2 entries, got "
                    + tables.size());
        }
        for (Object t : tables) {
            if (t instanceof String) {
                buf.append(inner).append("<Table name=\"")
                    .append(escape((String) t)).append("\"/>\n");
            } else if (t instanceof Map) {
                Map<?, ?> tm = (Map<?, ?>) t;
                buf.append(inner).append("<Table name=\"")
                    .append(escape(strRequired(tm, "name"))).append("\"");
                attrIfPresent(buf, tm, "schema", "schema");
                attrIfPresent(buf, tm, "alias", "alias");
                buf.append("/>\n");
            } else {
                throw new IllegalArgumentException(
                    "join.tables entries must be string or map");
            }
        }
        buf.append(indent).append("</Join>\n");
    }

    private static void emitLevel(
        StringBuilder buf, Map<?, ?> l, String indent)
    {
        buf.append(indent).append("<Level");
        attrIfPresent(buf, l, "name", "name");
        attrIfPresent(buf, l, "table", "table");
        attrIfPresent(buf, l, "column", "column");
        attrIfPresent(buf, l, "type", "type");
        attrIfPresent(buf, l, "unique_members", "uniqueMembers");
        attrIfPresent(buf, l, "level_type", "levelType");
        attrIfPresent(buf, l, "approx_row_count", "approxRowCount");
        List<?> props = listOrEmpty(l, "properties");
        if (props.isEmpty()) {
            buf.append("/>\n");
            return;
        }
        buf.append(">\n");
        String inner = indent + "  ";
        for (Object p : props) {
            Map<?, ?> pm = (Map<?, ?>) p;
            buf.append(inner).append("<Property");
            attrIfPresent(buf, pm, "name", "name");
            attrIfPresent(buf, pm, "column", "column");
            attrIfPresent(buf, pm, "type", "type");
            buf.append("/>\n");
        }
        buf.append(indent).append("</Level>\n");
    }

    /**
     * Cube-scoped {@code <DimensionUsage>} — a reference to a shared
     * dimension declared at schema scope. The optional {@code name}
     * key lets the cube alias the shared dimension (useful when the
     * same shared dim is used for multiple roles in one cube).
     */
    private static void emitDimensionUsage(StringBuilder buf, Map<?, ?> du) {
        buf.append("    <DimensionUsage");
        attrIfPresent(buf, du, "name", "name");
        attrIfPresent(buf, du, "source", "source");
        attrIfPresent(buf, du, "foreign_key", "foreignKey");
        attrIfPresent(buf, du, "level", "level");
        attrIfPresent(buf, du, "usage_prefix", "usagePrefix");
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
