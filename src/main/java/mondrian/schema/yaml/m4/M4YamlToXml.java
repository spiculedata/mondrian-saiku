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
        } else {
            schema.name = str(schemaNode);
        }
        List<MondrianDef.SchemaElement> children = new ArrayList<>();
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
        if (!children.isEmpty()) {
            schema.childArray =
                children.toArray(new MondrianDef.SchemaElement[0]);
        }
        return schema;
    }

    private static MondrianDef.Dimension buildDimension(
        String name, Map<?, ?> dim)
    {
        MondrianDef.Dimension d = new MondrianDef.Dimension();
        d.name = name;
        d.table = str(dim.get("table"));
        d.key = str(dim.get("key"));
        d.type = str(dim.get("type"));
        List<MondrianDef.DimensionElement> dimKids = new ArrayList<>();
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
        Object key = m.get("key");
        if (key instanceof List && !((List<?>) key).isEmpty()) {
            kids.add(buildKey((List<?>) key));
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
        Object levels = m.get("levels");
        if (levels instanceof List && !((List<?>) levels).isEmpty()) {
            List<MondrianDef.HierarchyElement> levelKids = new ArrayList<>();
            for (Object lvl : (List<?>) levels) {
                levelKids.add(buildLevel(lvl));
            }
            h.childArray =
                levelKids.toArray(new MondrianDef.HierarchyElement[0]);
        }
        return h;
    }

    private static MondrianDef.Level buildLevel(Object o) {
        MondrianDef.Level level = new MondrianDef.Level();
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            level.name = str(m.get("name"));
            level.attribute = str(m.get("attribute"));
        } else {
            level.attribute = str(o);
        }
        return level;
    }

    private static Boolean boolToBoolean(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        return Boolean.valueOf(String.valueOf(o));
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
        Object expr = d.get("expression");
        if (expr instanceof Map) {
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
                sql.children =
                    new NodeDef[]{ new TextDef(body == null ? "" : body) };
                sqls.add(sql);
            }
            view.expressions = sqls.toArray(new MondrianDef.SQL[0]);
            ccd.expression = view;
        }
        return ccd;
    }

    private static MondrianDef.Key buildKey(List<?> columnNames) {
        MondrianDef.Key key = new MondrianDef.Key();
        List<MondrianDef.Column> cols = new ArrayList<>();
        for (Object c : columnNames) {
            cols.add(new MondrianDef.Column(null, str(c)));
        }
        key.array = cols.toArray(new MondrianDef.Column[0]);
        return key;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
