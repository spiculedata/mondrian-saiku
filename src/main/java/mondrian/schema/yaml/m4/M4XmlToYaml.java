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
        // #110 display attributes.
        if (schema.caption != null) {
            header.put("caption", schema.caption);
        }
        if (schema.description != null) {
            header.put("description", schema.description);
        }
        if (schema.measuresCaption != null) {
            header.put("measures_caption", schema.measuresCaption);
        }
        root.put("schema", header);

        if (schema.childArray != null) {
            Map<String, Object> sharedDims = null;
            Map<String, Object> cubes = null;
            List<Object> roles = null;
            for (MondrianDef.SchemaElement el : schema.childArray) {
                if (el instanceof MondrianDef.Annotations) {
                    Map<String, Object> ann =
                        annotations((MondrianDef.Annotations) el);
                    if (ann != null && !ann.isEmpty()) {
                        // Place annotations first, right after schema header
                        root.put("annotations", ann);
                    }
                } else if (el instanceof MondrianDef.PhysicalSchema) {
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
                    cubes.put(c.name, M4CubeIngester.cube(c));
                } else if (el instanceof MondrianDef.Role) {
                    if (roles == null) {
                        roles = new ArrayList<>();
                    }
                    roles.add(role((MondrianDef.Role) el));
                }
            }
            if (sharedDims != null && !sharedDims.isEmpty()) {
                root.put("shared_dimensions", sharedDims);
            }
            if (cubes != null && !cubes.isEmpty()) {
                root.put("cubes", cubes);
            }
            if (roles != null && !roles.isEmpty()) {
                root.put("roles", roles);
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
        List<Object> queries = new ArrayList<>();
        List<Object> links = new ArrayList<>();
        if (ps.childArray != null) {
            for (Object child : ps.childArray) {
                if (child instanceof MondrianDef.Table) {
                    tables.add(table((MondrianDef.Table) child));
                } else if (child instanceof MondrianDef.Query) {
                    queries.add(query((MondrianDef.Query) child));
                } else if (child instanceof MondrianDef.Link) {
                    links.add(link((MondrianDef.Link) child));
                }
            }
        }
        if (!tables.isEmpty()) {
            out.put("tables", tables);
        }
        if (!queries.isEmpty()) {
            out.put("queries", queries);
        }
        if (!links.isEmpty()) {
            out.put("links", links);
        }
        return out;
    }

    /**
     * #111: a {@code <Query>} SQL-backed physical table (an
     * {@code <ExpressionView>} "view" in the physical schema). Emits alias,
     * keyColumn, and the per-dialect SQL so the view round-trips instead of
     * vanishing (leaving dimensions referencing a dropped table).
     */
    private static Map<String, Object> query(MondrianDef.Query q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alias", q.alias);
        if (q.keyColumn != null) {
            out.put("key_column", q.keyColumn);
        }
        if (q.childArray != null) {
            for (Object child : q.childArray) {
                if (child instanceof MondrianDef.ExpressionView) {
                    MondrianDef.ExpressionView view =
                        (MondrianDef.ExpressionView) child;
                    if (view.expressions != null) {
                        Map<String, Object> expr = new LinkedHashMap<>();
                        for (MondrianDef.SQL sql : view.expressions) {
                            expr.put(sql.dialect, sqlText(sql));
                        }
                        out.put("expression", expr);
                    }
                }
            }
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
     * Read the mixed-content body of a {@code <SQL>} element, encoding
     * any inline {@code <Column>} references as the token
     * {@code {col:table.name}} (or {@code {col:name}} when no table is
     * given). Text nodes are appended verbatim.
     *
     * <p>This encoding is round-trip-safe: {@code M4YamlToXml} parses
     * the same tokens and rebuilds the original {@code Column} + text
     * mixed-content node array. The {@code col:} prefix and curly-brace
     * delimiters are chosen to be unambiguous in SQL; plain SQL text
     * cannot contain literal {@code {col:} } in practice.
     *
     * <p><b>Known limitations:</b> the token scheme assumes that (1) plain
     * SQL text does not contain the literal sequence {@code {col:} } and
     * (2) column and table identifiers do not contain a literal {@code .}
     * character (the decoder splits on the first dot to separate table from
     * column name). FoodMart satisfies both constraints; arbitrary dialects
     * or quoted identifiers containing dots may not.
     */
    static String sqlText(MondrianDef.SQL sql) {
        StringBuilder buf = new StringBuilder();
        if (sql.children != null) {
            for (NodeDef child : sql.children) {
                if (child instanceof TextDef) {
                    buf.append(((TextDef) child).s);
                } else if (child instanceof MondrianDef.Column) {
                    MondrianDef.Column col = (MondrianDef.Column) child;
                    buf.append("{col:");
                    if (col.table != null && !col.table.isEmpty()) {
                        buf.append(col.table).append('.');
                    }
                    buf.append(col.name == null ? "" : col.name);
                    buf.append('}');
                }
                // other node types (rare) are silently skipped
            }
        }
        // Trim leading/trailing whitespace. XOM's toXML() pretty-prints the
        // <SQL> body with indentation; without trimming, that indentation is
        // re-captured on each XML→YAML pass and ACCUMULATES (#111 /
        // calc-column round-trip), so the conversion never reaches a fixed
        // point. SQL is insensitive to surrounding whitespace, and interior
        // text (incl. {col:...} tokens) is preserved.
        return buf.toString().trim();
    }

    // ---- shared dimension helpers ----

    /**
     * #110: emit the optional display attributes — caption, description, and
     * visible (only when explicitly false; true is the XOM default) — shared
     * by every element type that carries them.
     */
    static void putDisplay(
        Map<String, Object> out, String caption, String description,
        Boolean visible)
    {
        if (caption != null) {
            out.put("caption", caption);
        }
        if (description != null) {
            out.put("description", description);
        }
        if (visible != null && !visible) {
            out.put("visible", false);
        }
    }

    static Map<String, Object> dimension(MondrianDef.Dimension d) {
        Map<String, Object> out = new LinkedHashMap<>();
        // Annotations first (before table/key)
        if (d.childArray != null) {
            for (MondrianDef.DimensionElement de : d.childArray) {
                if (de instanceof MondrianDef.Annotations) {
                    Map<String, Object> ann =
                        annotations((MondrianDef.Annotations) de);
                    if (ann != null && !ann.isEmpty()) {
                        out.put("annotations", ann);
                    }
                }
            }
        }
        if (d.table != null) {
            out.put("table", d.table);
        }
        if (d.key != null) {
            out.put("key", d.key);
        }
        putDisplay(out, d.caption, d.description, d.visible);
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
        // Annotations second (after name, before key_column)
        if (a.childArray != null) {
            for (MondrianDef.AttributeElement ae : a.childArray) {
                if (ae instanceof MondrianDef.Annotations) {
                    Map<String, Object> ann =
                        annotations((MondrianDef.Annotations) ae);
                    if (ann != null && !ann.isEmpty()) {
                        out.put("annotations", ann);
                    }
                }
            }
        }
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
        putDisplay(out, a.caption, a.description, a.visible);
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
        // Annotations before levels
        if (h.childArray != null) {
            for (MondrianDef.HierarchyElement he : h.childArray) {
                if (he instanceof MondrianDef.Annotations) {
                    Map<String, Object> ann =
                        annotations((MondrianDef.Annotations) he);
                    if (ann != null && !ann.isEmpty()) {
                        out.put("annotations", ann);
                    }
                }
            }
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
        // Check for annotations in childArray
        Map<String, Object> ann = null;
        if (l.childArray != null) {
            for (MondrianDef.LevelElement le : l.childArray) {
                if (le instanceof MondrianDef.Annotations) {
                    ann = annotations((MondrianDef.Annotations) le);
                    break;
                }
            }
        }
        // If level has a name that differs from its attribute ref, or has
        // annotations, emit a map; otherwise emit just the attribute string.
        if ((l.name != null && !l.name.equals(l.attribute))
                || (ann != null && !ann.isEmpty())) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (l.name != null && !l.name.equals(l.attribute)) {
                m.put("name", l.name);
            }
            m.put("attribute", l.attribute);
            if (ann != null && !ann.isEmpty()) {
                m.put("annotations", ann);
            }
            return m;
        }
        return l.attribute;
    }

    /**
     * Serialize an array of {@link MondrianDef.Column} references to a list
     * of strings. Each string is {@code "table.colname"} when the column has
     * an explicit {@code table} qualifier, or just {@code "colname"} otherwise.
     *
     * <p>This encoding is round-trip-safe with {@link M4YamlToXml#parseColumnRef}.
     *
     * <p><b>Known limitation:</b> the {@code table.colname} representation
     * splits on the first {@code .} character, so table or column names that
     * themselves contain a literal {@code .} (e.g. quoted identifiers) will
     * not round-trip correctly. FoodMart identifiers are all plain alphanumeric
     * names and are not affected.
     */
    static List<String> columnNames(MondrianDef.Column[] cols) {
        List<String> names = new ArrayList<>();
        if (cols != null) {
            for (MondrianDef.Column c : cols) {
                if (c.table != null && !c.table.isEmpty()) {
                    names.add(c.table + "." + c.name);
                } else {
                    names.add(c.name);
                }
            }
        }
        return names;
    }

    // ---- shared annotations helper (used by schema and cube ingest) ----

    /**
     * Converts a {@link MondrianDef.Annotations} to an ordered name→text map.
     * Package-private so {@link M4CubeIngester} can reuse it.
     * Returns {@code null} if the element or its array is null/empty.
     */
    static Map<String, Object> annotations(MondrianDef.Annotations ann) {
        if (ann == null || ann.array == null || ann.array.length == 0) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (MondrianDef.Annotation a : ann.array) {
            out.put(a.name, a.cdata == null ? "" : a.cdata);
        }
        return out.isEmpty() ? null : out;
    }

    // ---- role ingest helpers ----

    private static Map<String, Object> role(MondrianDef.Role r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", r.name);
        if (r.className != null) {
            out.put("class_name", r.className);
        }
        // Annotations before schema_grant
        if (r.childArray != null) {
            for (MondrianDef.RoleElement re : r.childArray) {
                if (re instanceof MondrianDef.Annotations) {
                    Map<String, Object> ann =
                        annotations((MondrianDef.Annotations) re);
                    if (ann != null && !ann.isEmpty()) {
                        out.put("annotations", ann);
                    }
                }
            }
        }
        if (r.childArray != null) {
            for (MondrianDef.RoleElement re : r.childArray) {
                if (re instanceof MondrianDef.SchemaGrant) {
                    out.put("schema_grant", schemaGrant((MondrianDef.SchemaGrant) re));
                    break; // only one schema grant per role
                }
            }
        }
        return out;
    }

    private static Map<String, Object> schemaGrant(MondrianDef.SchemaGrant sg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sg.access != null) {
            out.put("access", sg.access);
        }
        if (sg.cubeGrants != null && sg.cubeGrants.length > 0) {
            List<Object> cubes = new ArrayList<>();
            for (MondrianDef.CubeGrant cg : sg.cubeGrants) {
                cubes.add(cubeGrant(cg));
            }
            out.put("cubes", cubes);
        }
        return out;
    }

    private static Map<String, Object> cubeGrant(MondrianDef.CubeGrant cg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (cg.cube != null) {
            out.put("cube", cg.cube);
        }
        if (cg.access != null) {
            out.put("access", cg.access);
        }
        if (cg.dimensionGrants != null && cg.dimensionGrants.length > 0) {
            List<Object> dims = new ArrayList<>();
            for (MondrianDef.DimensionGrant dg : cg.dimensionGrants) {
                dims.add(dimensionGrant(dg));
            }
            out.put("dimensions", dims);
        }
        if (cg.hierarchyGrants != null && cg.hierarchyGrants.length > 0) {
            List<Object> hiers = new ArrayList<>();
            for (MondrianDef.HierarchyGrant hg : cg.hierarchyGrants) {
                hiers.add(hierarchyGrant(hg));
            }
            out.put("hierarchies", hiers);
        }
        return out;
    }

    private static Map<String, Object> dimensionGrant(MondrianDef.DimensionGrant dg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dg.dimension != null) {
            out.put("dimension", dg.dimension);
        }
        if (dg.access != null) {
            out.put("access", dg.access);
        }
        return out;
    }

    private static Map<String, Object> hierarchyGrant(MondrianDef.HierarchyGrant hg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (hg.hierarchy != null) {
            out.put("hierarchy", hg.hierarchy);
        }
        if (hg.access != null) {
            out.put("access", hg.access);
        }
        if (hg.topLevel != null) {
            out.put("top_level", hg.topLevel);
        }
        if (hg.bottomLevel != null) {
            out.put("bottom_level", hg.bottomLevel);
        }
        if (hg.rollupPolicy != null) {
            out.put("rollup_policy", hg.rollupPolicy);
        }
        if (hg.memberGrants != null && hg.memberGrants.length > 0) {
            List<Object> members = new ArrayList<>();
            for (MondrianDef.MemberGrant mg : hg.memberGrants) {
                members.add(memberGrant(mg));
            }
            out.put("members", members);
        }
        return out;
    }

    private static Map<String, Object> memberGrant(MondrianDef.MemberGrant mg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (mg.member != null) {
            out.put("member", mg.member);
        }
        if (mg.access != null) {
            out.put("access", mg.access);
        }
        return out;
    }
}
