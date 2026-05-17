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
 * #34 Phase 0 spike: convert a YAML schema document into the
 * equivalent Mondrian XML schema string, which the standard Mondrian
 * schema loader then ingests unchanged.
 *
 * <p>Strategy: parse YAML to a tree of generic Maps/Lists via Jackson
 * YAML, walk the tree, emit XML as a StringBuilder. The XML output
 * is byte-fed into Mondrian's existing
 * {@code RolapSchemaLoader}/{@code mondrian.olap.MondrianDef} pipeline
 * — no changes to the runtime schema-loading code required.
 *
 * <h3>Spike scope</h3>
 *
 * <p>Covers only the element types needed by the minimal one-cube
 * test fixture: {@code Schema}, {@code Cube}, {@code Table},
 * {@code Dimension}, {@code Hierarchy}, {@code Level}, {@code Measure}.
 * Other elements (Annotations, NamedSet, CalculatedMember, MeasureGroup,
 * agg-table refs, etc.) are out of scope until we know the architectural
 * call works.
 *
 * <h3>Naming convention</h3>
 *
 * <p>YAML uses snake_case ({@code foreign_key}, {@code unique_members},
 * {@code has_all}); the converter maps to Mondrian's XML camelCase
 * ({@code foreignKey}, {@code uniqueMembers}, {@code hasAll}). This is
 * one of the call-out points for Phase 1: do we keep snake_case
 * (idiomatic YAML) or mirror XML's camelCase (zero translation needed
 * but un-YAML-ish)? Phase 0 picks snake_case to test that the
 * translation layer actually works.
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

        String factTable = strRequired(c, "fact_table");
        buf.append("    <Table name=\"")
            .append(escape(factTable)).append("\"/>\n");

        for (Object dim : listOrEmpty(c, "dimensions")) {
            emitDimension(buf, (Map<?, ?>) dim);
        }
        for (Object m : listOrEmpty(c, "measures")) {
            emitMeasure(buf, (Map<?, ?>) m);
        }
        buf.append("  </Cube>\n");
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
        buf.append("/>\n");
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
