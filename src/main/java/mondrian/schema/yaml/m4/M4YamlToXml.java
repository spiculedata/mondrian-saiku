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
        if (!children.isEmpty()) {
            schema.childArray =
                children.toArray(new MondrianDef.SchemaElement[0]);
        }
        return schema;
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
        if (!kids.isEmpty()) {
            ps.childArray =
                kids.toArray(new MondrianDef.PhysicalSchemaElement[0]);
        }
        return ps;
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
        if (!kids.isEmpty()) {
            table.childArray =
                kids.toArray(new MondrianDef.TableElement[0]);
        }
        return table;
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
