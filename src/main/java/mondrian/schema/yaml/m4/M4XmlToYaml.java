/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml.m4;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import mondrian.olap.MondrianDef;

import org.eigenbase.xom.DOMWrapper;
import org.eigenbase.xom.NodeDef;
import org.eigenbase.xom.Parser;
import org.eigenbase.xom.TextDef;
import org.eigenbase.xom.XOMUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #34 M4: Mondrian-4 XML -> YAML. Parses the XML into a typed
 * {@link MondrianDef.Schema} via XOM, walks the object graph, and emits
 * the equivalent YAML map (Jackson). Inverse of {@link M4YamlToXml}.
 *
 * <p>Phase 1 covers the schema header + {@code physical_schema}
 * (tables, keys, links, calculated columns). Later phases add
 * dimensions, measure groups, roles.
 */
public final class M4XmlToYaml {

    private static final ObjectMapper YAML;
    static {
        YAMLFactory f = new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        YAML = new ObjectMapper(f);
    }

    private M4XmlToYaml() {}

    public static String toYaml(String xmlText) {
        final MondrianDef.Schema schema;
        try {
            Parser parser = XOMUtil.createDefaultParser();
            DOMWrapper def = parser.parse(xmlText);
            schema = new MondrianDef.Schema(def);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to parse M4 XML: " + e.getMessage(), e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", schema.name);
        if (schema.metamodelVersion != null) {
            header.put("metamodel_version", schema.metamodelVersion);
        }
        root.put("schema", header);

        if (schema.childArray != null) {
            for (MondrianDef.SchemaElement el : schema.childArray) {
                if (el instanceof MondrianDef.PhysicalSchema) {
                    root.put("physical_schema",
                        physicalSchema((MondrianDef.PhysicalSchema) el));
                }
            }
        }
        try {
            return YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to serialize YAML: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> physicalSchema(
        MondrianDef.PhysicalSchema ps)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> tables = new ArrayList<>();
        List<Object> links = new ArrayList<>();
        if (ps.childArray != null) {
            for (Object child : ps.childArray) {
                if (child instanceof MondrianDef.Table) {
                    tables.add(table((MondrianDef.Table) child));
                } else if (child instanceof MondrianDef.Link) {
                    links.add(link((MondrianDef.Link) child));
                }
            }
        }
        if (!tables.isEmpty()) {
            out.put("tables", tables);
        }
        if (!links.isEmpty()) {
            out.put("links", links);
        }
        return out;
    }

    private static Map<String, Object> table(MondrianDef.Table t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", t.name);
        if (t.alias != null) {
            out.put("alias", t.alias);
        }
        if (t.keyColumn != null) {
            out.put("key_column", t.keyColumn);
        }
        if (t.childArray != null) {
            for (Object child : t.childArray) {
                if (child instanceof MondrianDef.Key) {
                    List<String> keyCols =
                        columnNames(((MondrianDef.Key) child).array);
                    if (!keyCols.isEmpty()) {
                        out.put("key", keyCols);
                    }
                } else if (child instanceof MondrianDef.ColumnDefs) {
                    List<Object> ccs =
                        calculatedColumns((MondrianDef.ColumnDefs) child);
                    if (!ccs.isEmpty()) {
                        out.put("calculated_columns", ccs);
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Object> link(MondrianDef.Link l) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", l.source);
        out.put("target", l.target);
        if (l.foreignKey != null && l.foreignKey.array != null) {
            out.put("foreign_key", columnNames(l.foreignKey.array));
        } else if (l.foreignKeyColumn != null) {
            out.put("foreign_key_column", l.foreignKeyColumn);
        }
        return out;
    }

    private static List<Object> calculatedColumns(
        MondrianDef.ColumnDefs defs)
    {
        List<Object> out = new ArrayList<>();
        if (defs.array != null) {
            for (MondrianDef.RealOrCalcColumnDef d : defs.array) {
                if (d instanceof MondrianDef.CalculatedColumnDef) {
                    out.add(calculatedColumn(
                        (MondrianDef.CalculatedColumnDef) d));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> calculatedColumn(
        MondrianDef.CalculatedColumnDef d)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.name);
        if (d.type != null) {
            out.put("type", d.type);
        }
        if (d.expression instanceof MondrianDef.ExpressionView) {
            MondrianDef.ExpressionView view =
                (MondrianDef.ExpressionView) d.expression;
            if (view.expressions != null) {
                Map<String, Object> expr = new LinkedHashMap<>();
                for (MondrianDef.SQL sql : view.expressions) {
                    expr.put(sql.dialect, sqlText(sql));
                }
                out.put("expression", expr);
            }
        }
        return out;
    }

    /**
     * Read the text body of a {@code <SQL>} element. {@code SQL.getCData()}
     * in the generated model is a broken stub (emits "x" per child); the
     * canonical approach (mirrors {@code RolapSchemaLoader.getText}) walks
     * the child nodes and concatenates the {@link TextDef} text.
     *
     * <p>Limitation: inline element markup inside a SQL body (e.g.
     * {@code <Column name='fname'/>} references used by real FoodMart
     * expressions) is NOT captured here — only text nodes. Full
     * inline-markup fidelity is deferred to the capstone phase.
     */
    private static String sqlText(MondrianDef.SQL sql) {
        StringBuilder buf = new StringBuilder();
        if (sql.children != null) {
            for (NodeDef child : sql.children) {
                if (child instanceof TextDef) {
                    buf.append(((TextDef) child).s);
                }
            }
        }
        return buf.toString();
    }

    private static List<String> columnNames(MondrianDef.Column[] cols) {
        List<String> names = new ArrayList<>();
        if (cols != null) {
            for (MondrianDef.Column c : cols) {
                names.add(c.name);
            }
        }
        return names;
    }
}
