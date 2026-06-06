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

import mondrian.olap.MondrianDef;

import org.eigenbase.xom.NodeDef;
import org.eigenbase.xom.TextDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * #34 M4: YAML -> Mondrian-4 XML. Parses the YAML into a map, builds a
 * typed {@link MondrianDef.Schema} object graph, and serializes it back
 * to XML via {@link org.eigenbase.xom.ElementDef#toXML()}. All XML
 * serialization is delegated to XOM, so the converter only maps
 * YAML structures onto MondrianDef fields.
 *
 * <p>Phase 1 covers the schema header and the {@code physical_schema}
 * (tables, keys, columns, links, calculated columns). Later phases add
 * dimensions, measure groups, roles, and calculated members.
 */
public final class M4YamlToXml {

    private static final ObjectMapper YAML =
        new ObjectMapper(new YAMLFactory());

    private M4YamlToXml() {}

    public static String toXml(String yamlText) {
        final Map<?, ?> root;
        try {
            root = YAML.readValue(yamlText, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to parse YAML: " + e.getMessage(), e);
        }
        return fromRoot(root);
    }

    /**
     * Build M4 XML from an already-parsed YAML root map (used by the
     * CLI path after $ref resolution).
     */
    public static String fromRoot(Map<?, ?> root) {
        MondrianDef.Schema schema = buildSchema(root);
        return schema.toXML();
    }

    private static MondrianDef.Schema buildSchema(Map<?, ?> root) {
        MondrianDef.Schema schema = new MondrianDef.Schema();
        Object schemaNode = root.get("schema");
        if (schemaNode instanceof Map) {
            Map<?, ?> sm = (Map<?, ?>) schemaNode;
            schema.name = str(sm.get("name"));
            schema.metamodelVersion = str(sm.get("metamodel_version"));
            // #110 display attributes.
            schema.caption = str(sm.get("caption"));
            schema.description = str(sm.get("description"));
            schema.measuresCaption = str(sm.get("measures_caption"));
        } else {
            schema.name = str(schemaNode);
        }
        List<MondrianDef.SchemaElement> children = new ArrayList<>();
        // Annotations go first (schema-level)
        Object annObj = root.get("annotations");
        if (annObj instanceof Map && !((Map<?, ?>) annObj).isEmpty()) {
            children.add(buildAnnotations((Map<?, ?>) annObj));
        }
        Object phys = root.get("physical_schema");
        if (phys instanceof Map) {
            children.add(buildPhysicalSchema((Map<?, ?>) phys));
        }
        Object sharedDims = root.get("shared_dimensions");
        if (sharedDims instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) sharedDims).entrySet()) {
                if (e.getValue() instanceof Map) {
                    children.add(buildDimension(
                        str(e.getKey()), (Map<?, ?>) e.getValue()));
                }
            }
        }
        Object cubes = root.get("cubes");
        if (cubes instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) cubes).entrySet()) {
                if (e.getValue() instanceof Map) {
                    children.add(M4CubeBuilder.build(str(e.getKey()), (Map<?, ?>) e.getValue()));
                }
            }
        }
        Object roles = root.get("roles");
        if (roles instanceof List) {
            for (Object r : (List<?>) roles) {
                if (r instanceof Map) {
                    children.add(buildRole((Map<?, ?>) r));
                }
            }
        }
        if (!children.isEmpty()) {
            schema.childArray =
                children.toArray(new MondrianDef.SchemaElement[0]);
        }
        return schema;
    }

    // ---- shared annotations helper (used by schema and cube) ----

    /**
     * Builds a {@link MondrianDef.Annotations} from a name→text map.
     * Package-private so {@link M4CubeBuilder} can reuse it.
     */
    static MondrianDef.Annotations buildAnnotations(Map<?, ?> annMap) {
        MondrianDef.Annotations ann = new MondrianDef.Annotations();
        List<MondrianDef.Annotation> list = new ArrayList<>();
        for (Entry<?, ?> e : annMap.entrySet()) {
            MondrianDef.Annotation a = new MondrianDef.Annotation();
            a.name = str(e.getKey());
            a.cdata = str(e.getValue());
            list.add(a);
        }
        ann.array = list.toArray(new MondrianDef.Annotation[0]);
        return ann;
    }

    // ---- role builders ----

    private static MondrianDef.Role buildRole(Map<?, ?> r) {
        MondrianDef.Role role = new MondrianDef.Role();
        role.name = str(r.get("name"));
        role.className = str(r.get("class_name"));
        List<MondrianDef.RoleElement> kids = new ArrayList<>();
        // Annotations go first
        Object annObj = r.get("annotations");
        if (annObj instanceof Map && !((Map<?, ?>) annObj).isEmpty()) {
            kids.add(buildAnnotations((Map<?, ?>) annObj));
        }
        Object sg = r.get("schema_grant");
        if (sg instanceof Map) {
            kids.add(buildSchemaGrant((Map<?, ?>) sg));
        }
        if (!kids.isEmpty()) {
            role.childArray = kids.toArray(new MondrianDef.RoleElement[0]);
        }
        return role;
    }

    private static MondrianDef.SchemaGrant buildSchemaGrant(Map<?, ?> sg) {
        MondrianDef.SchemaGrant grant = new MondrianDef.SchemaGrant();
        grant.access = str(sg.get("access"));
        Object cubes = sg.get("cubes");
        if (cubes instanceof List && !((List<?>) cubes).isEmpty()) {
            List<MondrianDef.CubeGrant> list = new ArrayList<>();
            for (Object c : (List<?>) cubes) {
                if (c instanceof Map) {
                    list.add(buildCubeGrant((Map<?, ?>) c));
                }
            }
            grant.cubeGrants = list.toArray(new MondrianDef.CubeGrant[0]);
        }
        return grant;
    }

    private static MondrianDef.CubeGrant buildCubeGrant(Map<?, ?> cg) {
        MondrianDef.CubeGrant grant = new MondrianDef.CubeGrant();
        grant.cube = str(cg.get("cube"));
        grant.access = str(cg.get("access"));
        Object dims = cg.get("dimensions");
        if (dims instanceof List && !((List<?>) dims).isEmpty()) {
            List<MondrianDef.DimensionGrant> list = new ArrayList<>();
            for (Object d : (List<?>) dims) {
                if (d instanceof Map) {
                    list.add(buildDimensionGrant((Map<?, ?>) d));
                }
            }
            grant.dimensionGrants = list.toArray(new MondrianDef.DimensionGrant[0]);
        }
        Object hiers = cg.get("hierarchies");
        if (hiers instanceof List && !((List<?>) hiers).isEmpty()) {
            List<MondrianDef.HierarchyGrant> list = new ArrayList<>();
            for (Object h : (List<?>) hiers) {
                if (h instanceof Map) {
                    list.add(buildHierarchyGrant((Map<?, ?>) h));
                }
            }
            grant.hierarchyGrants = list.toArray(new MondrianDef.HierarchyGrant[0]);
        }
        return grant;
    }

    private static MondrianDef.DimensionGrant buildDimensionGrant(Map<?, ?> dg) {
        MondrianDef.DimensionGrant grant = new MondrianDef.DimensionGrant();
        grant.dimension = str(dg.get("dimension"));
        grant.access = str(dg.get("access"));
        return grant;
    }

    private static MondrianDef.HierarchyGrant buildHierarchyGrant(Map<?, ?> hg) {
        MondrianDef.HierarchyGrant grant = new MondrianDef.HierarchyGrant();
        grant.hierarchy = str(hg.get("hierarchy"));
        grant.access = str(hg.get("access"));
        grant.topLevel = str(hg.get("top_level"));
        grant.bottomLevel = str(hg.get("bottom_level"));
        grant.rollupPolicy = str(hg.get("rollup_policy"));
        Object members = hg.get("members");
        if (members instanceof List && !((List<?>) members).isEmpty()) {
            List<MondrianDef.MemberGrant> list = new ArrayList<>();
            for (Object m : (List<?>) members) {
                if (m instanceof Map) {
                    list.add(buildMemberGrant((Map<?, ?>) m));
                }
            }
            grant.memberGrants = list.toArray(new MondrianDef.MemberGrant[0]);
        }
        return grant;
    }

    private static MondrianDef.MemberGrant buildMemberGrant(Map<?, ?> mg) {
        MondrianDef.MemberGrant grant = new MondrianDef.MemberGrant();
        grant.member = str(mg.get("member"));
        grant.access = str(mg.get("access"));
        return grant;
    }

    // ---- Shared dimension builder ----

    static MondrianDef.Dimension buildDimension(
        String name, Map<?, ?> dim)
    {
        MondrianDef.Dimension d = new MondrianDef.Dimension();
        d.name = name;
        d.table = str(dim.get("table"));
        d.key = str(dim.get("key"));
        d.type = str(dim.get("type"));
        // #110 display attributes.
        d.caption = str(dim.get("caption"));
        d.description = str(dim.get("description"));
        d.visible = boolOrNull(dim.get("visible"));
        List<MondrianDef.DimensionElement> dimKids = new ArrayList<>();
        // Annotations go first
        Object annObj = dim.get("annotations");
        if (annObj instanceof Map && !((Map<?, ?>) annObj).isEmpty()) {
            dimKids.add(buildAnnotations((Map<?, ?>) annObj));
        }
        Object attrs = dim.get("attributes");
        if (attrs instanceof List && !((List<?>) attrs).isEmpty()) {
            dimKids.add(buildAttributes((List<?>) attrs));
        }
        Object hiers = dim.get("hierarchies");
        if (hiers instanceof List && !((List<?>) hiers).isEmpty()) {
            dimKids.add(buildHierarchies((List<?>) hiers));
        }
        if (!dimKids.isEmpty()) {
            d.childArray =
                dimKids.toArray(new MondrianDef.DimensionElement[0]);
        }
        return d;
    }

    private static MondrianDef.Attributes buildAttributes(List<?> list) {
        MondrianDef.Attributes wrapper = new MondrianDef.Attributes();
        List<MondrianDef.Attribute> attrs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                attrs.add(buildAttribute((Map<?, ?>) item));
            }
        }
        wrapper.array = attrs.toArray(new MondrianDef.Attribute[0]);
        return wrapper;
    }

    private static MondrianDef.Attribute buildAttribute(Map<?, ?> m) {
        MondrianDef.Attribute a = new MondrianDef.Attribute();
        a.name = str(m.get("name"));
        a.table = str(m.get("table"));
        a.keyColumn = str(m.get("key_column"));
        // #110 display attributes.
        a.caption = str(m.get("caption"));
        a.description = str(m.get("description"));
        a.visible = boolOrNull(m.get("visible"));
        a.nameColumn = str(m.get("name_column"));
        a.orderByColumn = str(m.get("order_by_column"));
        a.captionColumn = str(m.get("caption_column"));
        a.levelType = str(m.get("level_type"));
        a.datatype = str(m.get("datatype"));
        a.hierarchyAllMemberName = str(m.get("hierarchy_all_member_name"));
        a.hierarchyAllMemberCaption = str(m.get("hierarchy_all_member_caption"));
        a.hierarchyDefaultMember = str(m.get("hierarchy_default_member"));
        Object hasHier = m.get("has_hierarchy");
        if (hasHier != null) {
            a.hasHierarchy = boolToBoolean(hasHier);
        }
        Object hierHasAll = m.get("hierarchy_has_all");
        if (hierHasAll != null) {
            a.hierarchyHasAll = boolToBoolean(hierHasAll);
        }
        List<MondrianDef.AttributeElement> kids = new ArrayList<>();
        // Annotations go first
        Object annObj = m.get("annotations");
        if (annObj instanceof Map && !((Map<?, ?>) annObj).isEmpty()) {
            kids.add(buildAnnotations((Map<?, ?>) annObj));
        }
        Object key = m.get("key");
        if (key instanceof List && !((List<?>) key).isEmpty()) {
            kids.add(buildKey((List<?>) key));
        }
        Object nameColumns = m.get("name_columns");
        if (nameColumns instanceof List && !((List<?>) nameColumns).isEmpty()) {
            kids.add(buildName((List<?>) nameColumns));
        }
        Object props = m.get("properties");
        if (props instanceof List) {
            for (Object p : (List<?>) props) {
                MondrianDef.Property prop = new MondrianDef.Property();
                prop.attribute = str(p);
                kids.add(prop);
            }
        }
        if (!kids.isEmpty()) {
            a.childArray = kids.toArray(new MondrianDef.AttributeElement[0]);
        }
        return a;
    }

    private static MondrianDef.Hierarchies buildHierarchies(List<?> list) {
        MondrianDef.Hierarchies wrapper = new MondrianDef.Hierarchies();
        List<MondrianDef.Hierarchy> hiers = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                hiers.add(buildHierarchy((Map<?, ?>) item));
            }
        }
        wrapper.array = hiers.toArray(new MondrianDef.Hierarchy[0]);
        return wrapper;
    }

    private static MondrianDef.Hierarchy buildHierarchy(Map<?, ?> m) {
        MondrianDef.Hierarchy h = new MondrianDef.Hierarchy();
        h.name = str(m.get("name"));
        h.allMemberName = str(m.get("all_member_name"));
        h.defaultMember = str(m.get("default_member"));
        Object hasAll = m.get("has_all");
        if (hasAll != null) {
            h.hasAll = boolToBoolean(hasAll);
        }
        List<MondrianDef.HierarchyElement> hierKids = new ArrayList<>();
        // Annotations go first
        Object annObj = m.get("annotations");
        if (annObj instanceof Map && !((Map<?, ?>) annObj).isEmpty()) {
            hierKids.add(buildAnnotations((Map<?, ?>) annObj));
        }
        Object levels = m.get("levels");
        if (levels instanceof List && !((List<?>) levels).isEmpty()) {
            for (Object lvl : (List<?>) levels) {
                hierKids.add(buildLevel(lvl));
            }
        }
        if (!hierKids.isEmpty()) {
            h.childArray =
                hierKids.toArray(new MondrianDef.HierarchyElement[0]);
        }
        return h;
    }

    private static MondrianDef.Level buildLevel(Object o) {
        MondrianDef.Level level = new MondrianDef.Level();
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            level.name = str(m.get("name"));
            level.attribute = str(m.get("attribute"));
            Object annObj = m.get("annotations");
            if (annObj instanceof Map && !((Map<?, ?>) annObj).isEmpty()) {
                level.childArray = new MondrianDef.LevelElement[] {
                    buildAnnotations((Map<?, ?>) annObj)
                };
            }
        } else {
            level.attribute = str(o);
        }
        return level;
    }

    static Boolean boolToBoolean(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        return Boolean.valueOf(String.valueOf(o));
    }

    /** Null-preserving boolean read: absent (null) stays null rather than
     *  collapsing to false — so an omitted default-true flag (e.g. #110
     *  {@code visible}) is not materialised on round-trip. */
    static Boolean boolOrNull(Object o) {
        return o == null ? null : boolToBoolean(o);
    }

    private static MondrianDef.PhysicalSchema buildPhysicalSchema(
        Map<?, ?> phys)
    {
        MondrianDef.PhysicalSchema ps = new MondrianDef.PhysicalSchema();
        // Table implements Relation, Relation extends PhysicalSchemaElement
        List<MondrianDef.PhysicalSchemaElement> kids = new ArrayList<>();
        Object tables = phys.get("tables");
        if (tables instanceof List) {
            for (Object t : (List<?>) tables) {
                if (t instanceof Map) {
                    kids.add(buildTable((Map<?, ?>) t));
                }
            }
        }
        Object queries = phys.get("queries");
        if (queries instanceof List) {
            for (Object q : (List<?>) queries) {
                if (q instanceof Map) {
                    kids.add(buildQuery((Map<?, ?>) q));
                }
            }
        }
        Object links = phys.get("links");
        if (links instanceof List) {
            for (Object l : (List<?>) links) {
                if (l instanceof Map) {
                    kids.add(buildLink((Map<?, ?>) l));
                }
            }
        }
        if (!kids.isEmpty()) {
            ps.childArray =
                kids.toArray(new MondrianDef.PhysicalSchemaElement[0]);
        }
        return ps;
    }

    /** #111: build a {@code <Query>} SQL-backed physical table. */
    private static MondrianDef.Query buildQuery(Map<?, ?> q) {
        MondrianDef.Query query = new MondrianDef.Query();
        query.alias = str(q.get("alias"));
        query.keyColumn = str(q.get("key_column"));
        MondrianDef.ExpressionView view =
            buildExpressionView(q.get("expression"));
        if (view != null) {
            query.childArray = new MondrianDef.QueryElement[] {view};
        }
        return query;
    }

    private static MondrianDef.Link buildLink(Map<?, ?> l) {
        MondrianDef.Link link = new MondrianDef.Link();
        link.source = str(l.get("source"));
        link.target = str(l.get("target"));
        Object fk = l.get("foreign_key");
        if (fk instanceof List && !((List<?>) fk).isEmpty()) {
            MondrianDef.ForeignKey foreignKey = new MondrianDef.ForeignKey();
            List<MondrianDef.Column> cols = new ArrayList<>();
            for (Object c : (List<?>) fk) {
                cols.add(new MondrianDef.Column(null, str(c)));
            }
            foreignKey.array = cols.toArray(new MondrianDef.Column[0]);
            link.foreignKey = foreignKey;
        } else if (l.get("foreign_key_column") != null) {
            link.foreignKeyColumn = str(l.get("foreign_key_column"));
        }
        return link;
    }

    private static MondrianDef.Table buildTable(Map<?, ?> t) {
        MondrianDef.Table table = new MondrianDef.Table();
        table.name = str(t.get("name"));
        table.alias = str(t.get("alias"));
        table.schema = str(t.get("schema"));
        table.keyColumn = str(t.get("key_column"));
        // Key implements TableElement, so it can go into Table.childArray
        List<MondrianDef.TableElement> kids = new ArrayList<>();
        Object key = t.get("key");
        if (key instanceof List && !((List<?>) key).isEmpty()) {
            kids.add(buildKey((List<?>) key));
        }
        Object calcCols = t.get("calculated_columns");
        if (calcCols instanceof List && !((List<?>) calcCols).isEmpty()) {
            kids.add(buildColumnDefs((List<?>) calcCols));
        }
        if (!kids.isEmpty()) {
            table.childArray =
                kids.toArray(new MondrianDef.TableElement[0]);
        }
        return table;
    }

    private static MondrianDef.ColumnDefs buildColumnDefs(List<?> defs) {
        MondrianDef.ColumnDefs columnDefs = new MondrianDef.ColumnDefs();
        List<MondrianDef.RealOrCalcColumnDef> list = new ArrayList<>();
        for (Object d : defs) {
            if (d instanceof Map) {
                list.add(buildCalculatedColumnDef((Map<?, ?>) d));
            }
        }
        columnDefs.array =
            list.toArray(new MondrianDef.RealOrCalcColumnDef[0]);
        return columnDefs;
    }

    private static MondrianDef.CalculatedColumnDef buildCalculatedColumnDef(
        Map<?, ?> d)
    {
        MondrianDef.CalculatedColumnDef ccd =
            new MondrianDef.CalculatedColumnDef();
        ccd.name = str(d.get("name"));
        ccd.type = str(d.get("type"));
        ccd.expression = buildExpressionView(d.get("expression"));
        return ccd;
    }

    /**
     * Build a {@link MondrianDef.ExpressionView} from a YAML
     * {@code expression: {dialect: sql, ...}} map. Shared by calculated
     * columns and #111 {@code <Query>} physical tables. Returns null when
     * the value is not a map.
     */
    static MondrianDef.ExpressionView buildExpressionView(Object expr) {
        if (!(expr instanceof Map)) {
            return null;
        }
        MondrianDef.ExpressionView view = new MondrianDef.ExpressionView();
        List<MondrianDef.SQL> sqls = new ArrayList<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) expr).entrySet()) {
            String dialect = str(e.getKey());
            if (dialect == null) {
                continue;
            }
            String body = str(e.getValue());
            MondrianDef.SQL sql = new MondrianDef.SQL();
            sql.dialect = dialect;
            sql.children = parseSqlMixedContent(body == null ? "" : body);
            sqls.add(sql);
        }
        view.expressions = sqls.toArray(new MondrianDef.SQL[0]);
        return view;
    }

    /**
     * Parse a SQL body string that may contain {@code {col:table.name}} or
     * {@code {col:name}} tokens (as emitted by {@link M4XmlToYaml#sqlText})
     * and rebuild the mixed-content {@link NodeDef} array suitable for
     * {@link MondrianDef.SQL#children}.
     *
     * <p>Each {@code {col:...}} token becomes a {@link MondrianDef.Column}
     * node; surrounding text becomes {@link TextDef} nodes. Empty text
     * segments are included to preserve whitespace.
     */
    static NodeDef[] parseSqlMixedContent(String body) {
        List<NodeDef> nodes = new ArrayList<>();
        int pos = 0;
        while (pos < body.length()) {
            int start = body.indexOf("{col:", pos);
            if (start < 0) {
                // rest is plain text
                nodes.add(new TextDef(body.substring(pos)));
                break;
            }
            if (start > pos) {
                nodes.add(new TextDef(body.substring(pos, start)));
            }
            int end = body.indexOf('}', start + 5);
            if (end < 0) {
                // malformed token — treat remainder as text
                nodes.add(new TextDef(body.substring(start)));
                break;
            }
            String ref = body.substring(start + 5, end); // content after "col:"
            MondrianDef.Column col = new MondrianDef.Column();
            int dot = ref.indexOf('.');
            if (dot >= 0) {
                col.table = ref.substring(0, dot);
                col.name = ref.substring(dot + 1);
            } else {
                col.name = ref;
            }
            nodes.add(col);
            pos = end + 1;
        }
        return nodes.toArray(new NodeDef[0]);
    }

    private static MondrianDef.Key buildKey(List<?> columnNames) {
        MondrianDef.Key key = new MondrianDef.Key();
        List<MondrianDef.Column> cols = new ArrayList<>();
        for (Object c : columnNames) {
            cols.add(parseColumnRef(str(c)));
        }
        key.array = cols.toArray(new MondrianDef.Column[0]);
        return key;
    }

    private static MondrianDef.Name buildName(List<?> columnNames) {
        MondrianDef.Name name = new MondrianDef.Name();
        List<MondrianDef.Column> cols = new ArrayList<>();
        for (Object c : columnNames) {
            cols.add(parseColumnRef(str(c)));
        }
        name.array = cols.toArray(new MondrianDef.Column[0]);
        return name;
    }

    /**
     * Parse a column reference string produced by
     * {@link M4XmlToYaml#columnNames}. The string is either
     * {@code "table.colname"} (dot-qualified) or just {@code "colname"}.
     * Returns a {@link MondrianDef.Column} with {@code table} set when
     * present, otherwise null.
     */
    static MondrianDef.Column parseColumnRef(String ref) {
        MondrianDef.Column col = new MondrianDef.Column();
        if (ref != null) {
            int dot = ref.indexOf('.');
            if (dot >= 0) {
                col.table = ref.substring(0, dot);
                col.name = ref.substring(dot + 1);
            } else {
                col.name = ref;
            }
        }
        return col;
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
