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
            Map<String, Object> sharedDims = null;
            Map<String, Object> cubes = null;
            for (MondrianDef.SchemaElement el : schema.childArray) {
                if (el instanceof MondrianDef.PhysicalSchema) {
                    root.put("physical_schema",
                        physicalSchema((MondrianDef.PhysicalSchema) el));
                } else if (el instanceof MondrianDef.Dimension) {
                    MondrianDef.Dimension dim = (MondrianDef.Dimension) el;
                    // Skip dimension usages (source != null) — though top-level
                    // schema dims are always definitions, not usages.
                    if (dim.source != null) {
                        continue;
                    }
                    if (sharedDims == null) {
                        sharedDims = new LinkedHashMap<>();
                    }
                    sharedDims.put(dim.name, dimension(dim));
                } else if (el instanceof MondrianDef.Cube) {
                    if (cubes == null) {
                        cubes = new LinkedHashMap<>();
                    }
                    MondrianDef.Cube c = (MondrianDef.Cube) el;
                    cubes.put(c.name, cube(c));
                }
            }
            if (sharedDims != null && !sharedDims.isEmpty()) {
                root.put("shared_dimensions", sharedDims);
            }
            if (cubes != null && !cubes.isEmpty()) {
                root.put("cubes", cubes);
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
        if (t.schema != null) {
            out.put("schema", t.schema);
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

    // ---- cube helpers ----

    private static Map<String, Object> cube(MondrianDef.Cube c) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (c.defaultMeasure != null) {
            out.put("default_measure", c.defaultMeasure);
        }
        if (c.childArray != null) {
            List<Object> dimList = null;
            List<Object> mgList = null;
            for (MondrianDef.CubeElement ce : c.childArray) {
                if (ce instanceof MondrianDef.Dimensions) {
                    dimList = cubeDimensions((MondrianDef.Dimensions) ce);
                } else if (ce instanceof MondrianDef.MeasureGroups) {
                    mgList = measureGroups((MondrianDef.MeasureGroups) ce);
                }
            }
            if (dimList != null && !dimList.isEmpty()) {
                out.put("dimensions", dimList);
            }
            if (mgList != null && !mgList.isEmpty()) {
                out.put("measure_groups", mgList);
            }
        }
        return out;
    }

    private static List<Object> cubeDimensions(MondrianDef.Dimensions wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.Dimension d : wrapper.array) {
                out.add(cubeDimension(d));
            }
        }
        return out;
    }

    private static Map<String, Object> cubeDimension(MondrianDef.Dimension d) {
        if (d.source != null) {
            // Dimension usage — reference to a shared dimension
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", d.source);
            return out;
        }
        // Local dimension definition — name goes first, then dimension body
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.name);
        out.putAll(dimension(d));
        return out;
    }

    private static List<Object> measureGroups(MondrianDef.MeasureGroups wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.MeasureGroup mg : wrapper.array) {
                out.add(measureGroup(mg));
            }
        }
        return out;
    }

    private static Map<String, Object> measureGroup(MondrianDef.MeasureGroup mg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (mg.name != null) {
            out.put("name", mg.name);
        }
        if (mg.table != null) {
            out.put("table", mg.table);
        }
        // Only emit type when non-default (default is "fact")
        if (mg.type != null && !"fact".equals(mg.type)) {
            out.put("type", mg.type);
        }
        if (mg.childArray != null) {
            List<Object> measureList = null;
            List<Object> linkList = null;
            for (MondrianDef.MeasureGroupElement mge : mg.childArray) {
                if (mge instanceof MondrianDef.Measures) {
                    measureList = measures((MondrianDef.Measures) mge);
                } else if (mge instanceof MondrianDef.DimensionLinks) {
                    linkList = dimensionLinks((MondrianDef.DimensionLinks) mge);
                }
            }
            if (measureList != null && !measureList.isEmpty()) {
                out.put("measures", measureList);
            }
            if (linkList != null && !linkList.isEmpty()) {
                out.put("dimension_links", linkList);
            }
        }
        return out;
    }

    private static List<Object> measures(MondrianDef.Measures wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.MeasureOrRef mor : wrapper.array) {
                out.add(measure(mor));
            }
        }
        return out;
    }

    private static Map<String, Object> measure(MondrianDef.MeasureOrRef mor) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (mor instanceof MondrianDef.MeasureRef) {
            MondrianDef.MeasureRef ref = (MondrianDef.MeasureRef) mor;
            out.put("ref", ref.name);
            out.put("agg_column", ref.aggColumn);
        } else if (mor instanceof MondrianDef.Measure) {
            MondrianDef.Measure m = (MondrianDef.Measure) mor;
            out.put("name", m.name);
            if (m.column != null) {
                out.put("column", m.column);
            }
            if (m.table != null) {
                out.put("table", m.table);
            }
            if (m.aggregator != null) {
                out.put("aggregator", m.aggregator);
            }
            if (m.formatString != null) {
                out.put("format_string", m.formatString);
            }
            if (m.datatype != null) {
                out.put("datatype", m.datatype);
            }
        }
        return out;
    }

    private static List<Object> dimensionLinks(MondrianDef.DimensionLinks wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.DimensionLink dl : wrapper.array) {
                out.add(dimensionLink(dl));
            }
        }
        return out;
    }

    private static Map<String, Object> dimensionLink(MondrianDef.DimensionLink dl) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dl instanceof MondrianDef.ForeignKeyLink) {
            MondrianDef.ForeignKeyLink fkl = (MondrianDef.ForeignKeyLink) dl;
            out.put("type", "foreign_key");
            out.put("dimension", fkl.dimension);
            if (fkl.foreignKeyColumn != null) {
                out.put("foreign_key_column", fkl.foreignKeyColumn);
            }
            if (fkl.attribute != null) {
                out.put("attribute", fkl.attribute);
            }
        } else if (dl instanceof MondrianDef.CopyLink) {
            out.put("type", "copy");
            out.put("dimension", dl.dimension);
        } else if (dl instanceof MondrianDef.NoLink) {
            out.put("type", "no_link");
            out.put("dimension", dl.dimension);
        } else if (dl instanceof MondrianDef.FactLink) {
            out.put("type", "fact");
            out.put("dimension", dl.dimension);
        }
        return out;
    }

    // ---- shared dimension helpers ----

    private static Map<String, Object> dimension(MondrianDef.Dimension d) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (d.table != null) {
            out.put("table", d.table);
        }
        if (d.key != null) {
            out.put("key", d.key);
        }
        // Skip "OTHER" — it is the XOM default and not meaningful
        if (d.type != null && !"OTHER".equals(d.type)) {
            out.put("type", d.type);
        }
        if (d.childArray != null) {
            for (MondrianDef.DimensionElement de : d.childArray) {
                if (de instanceof MondrianDef.Attributes) {
                    List<Object> attrList = new ArrayList<>();
                    for (MondrianDef.Attribute a
                            : ((MondrianDef.Attributes) de).array) {
                        attrList.add(attribute(a));
                    }
                    if (!attrList.isEmpty()) {
                        out.put("attributes", attrList);
                    }
                } else if (de instanceof MondrianDef.Hierarchies) {
                    List<Object> hierList = new ArrayList<>();
                    for (MondrianDef.Hierarchy h
                            : ((MondrianDef.Hierarchies) de).array) {
                        hierList.add(hierarchy(h));
                    }
                    if (!hierList.isEmpty()) {
                        out.put("hierarchies", hierList);
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Object> attribute(MondrianDef.Attribute a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", a.name);
        if (a.table != null) {
            out.put("table", a.table);
        }
        // Key resolution: prefer keyColumn attribute; fall back to <Key> child
        if (a.keyColumn != null) {
            out.put("key_column", a.keyColumn);
        } else {
            MondrianDef.Key keyChild = findKeyChild(a.childArray);
            if (keyChild != null && keyChild.array != null
                    && keyChild.array.length > 0) {
                out.put("key", columnNames(keyChild.array));
            }
        }
        // Name resolution: prefer nameColumn attribute; fall back to <Name> child
        if (a.nameColumn != null) {
            out.put("name_column", a.nameColumn);
        } else {
            MondrianDef.Name nameChild = findNameChild(a.childArray);
            if (nameChild != null && nameChild.array != null
                    && nameChild.array.length > 0) {
                List<String> nameCols = columnNames(nameChild.array);
                if (nameCols.size() == 1) {
                    out.put("name_column", nameCols.get(0));
                } else {
                    out.put("name_columns", nameCols);
                }
            }
        }
        if (a.orderByColumn != null) {
            out.put("order_by_column", a.orderByColumn);
        }
        // Skip XOM default "Regular" for levelType
        if (a.levelType != null && !"Regular".equals(a.levelType)) {
            out.put("level_type", a.levelType);
        }
        // Skip XOM default "String" for datatype
        if (a.datatype != null && !"String".equals(a.datatype)) {
            out.put("datatype", a.datatype);
        }
        if (a.captionColumn != null) {
            out.put("caption_column", a.captionColumn);
        }
        if (a.hierarchyAllMemberName != null) {
            out.put("hierarchy_all_member_name", a.hierarchyAllMemberName);
        }
        if (a.hierarchyAllMemberCaption != null) {
            out.put("hierarchy_all_member_caption", a.hierarchyAllMemberCaption);
        }
        if (a.hierarchyDefaultMember != null) {
            out.put("hierarchy_default_member", a.hierarchyDefaultMember);
        }
        // Only emit hierarchy_has_all when explicitly FALSE (true is the default)
        if (Boolean.FALSE.equals(a.hierarchyHasAll)) {
            out.put("hierarchy_has_all", false);
        }
        // Only emit has_hierarchy when explicitly FALSE (true is the default)
        if (Boolean.FALSE.equals(a.hasHierarchy)) {
            out.put("has_hierarchy", false);
        }
        // Property children → properties list of attribute refs
        List<String> props = new ArrayList<>();
        if (a.childArray != null) {
            for (MondrianDef.AttributeElement ae : a.childArray) {
                if (ae instanceof MondrianDef.Property) {
                    props.add(((MondrianDef.Property) ae).attribute);
                }
            }
        }
        if (!props.isEmpty()) {
            out.put("properties", props);
        }
        return out;
    }

    private static MondrianDef.Key findKeyChild(
        MondrianDef.AttributeElement[] kids)
    {
        if (kids == null) {
            return null;
        }
        for (MondrianDef.AttributeElement ae : kids) {
            if (ae instanceof MondrianDef.Key) {
                return (MondrianDef.Key) ae;
            }
        }
        return null;
    }

    private static MondrianDef.Name findNameChild(
        MondrianDef.AttributeElement[] kids)
    {
        if (kids == null) {
            return null;
        }
        for (MondrianDef.AttributeElement ae : kids) {
            if (ae instanceof MondrianDef.Name) {
                return (MondrianDef.Name) ae;
            }
        }
        return null;
    }

    private static Map<String, Object> hierarchy(MondrianDef.Hierarchy h) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", h.name);
        if (h.allMemberName != null) {
            out.put("all_member_name", h.allMemberName);
        }
        if (h.defaultMember != null) {
            out.put("default_member", h.defaultMember);
        }
        if (h.hasAll != null) {
            out.put("has_all", h.hasAll);
        }
        // Collect levels
        List<Object> levels = new ArrayList<>();
        if (h.childArray != null) {
            for (MondrianDef.HierarchyElement he : h.childArray) {
                if (he instanceof MondrianDef.Level) {
                    levels.add(level((MondrianDef.Level) he));
                }
            }
        }
        if (!levels.isEmpty()) {
            out.put("levels", levels);
        }
        return out;
    }

    private static Object level(MondrianDef.Level l) {
        // If level has a name that differs from its attribute ref, emit a map;
        // otherwise emit just the attribute string.
        if (l.name != null && !l.name.equals(l.attribute)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", l.name);
            m.put("attribute", l.attribute);
            return m;
        }
        return l.attribute;
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
