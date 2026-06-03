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

import mondrian.olap.MondrianDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * #34 M4: package-private helper that builds a {@link MondrianDef.Cube}
 * from a parsed YAML map. Extracted from {@link M4YamlToXml} to keep that
 * class under the 800-line cap.
 *
 * <p>The public entry point is {@link #build(String, Map)}, which is
 * called by {@code M4YamlToXml.buildSchema} for each cube in the YAML.
 * Shared dimension helpers (buildDimension, str, etc.) remain in
 * {@link M4YamlToXml} as package-private statics and are called directly.
 */
final class M4CubeBuilder {

    private M4CubeBuilder() {}

    // ---- public entry point ----

    static MondrianDef.Cube build(String name, Map<?, ?> body) {
        MondrianDef.Cube cube = new MondrianDef.Cube();
        cube.name = name;
        cube.defaultMeasure = M4YamlToXml.str(body.get("default_measure"));
        List<MondrianDef.CubeElement> cubeKids = new ArrayList<>();
        // Annotations go first (cube-level)
        Object annObj = body.get("annotations");
        if (annObj instanceof Map && !((Map<?, ?>) annObj).isEmpty()) {
            cubeKids.add(M4YamlToXml.buildAnnotations((Map<?, ?>) annObj));
        }
        Object dims = body.get("dimensions");
        if (dims instanceof List && !((List<?>) dims).isEmpty()) {
            cubeKids.add(buildCubeDimensions((List<?>) dims));
        }
        Object mgs = body.get("measure_groups");
        if (mgs instanceof List && !((List<?>) mgs).isEmpty()) {
            cubeKids.add(buildMeasureGroups((List<?>) mgs));
        }
        Object cms = body.get("calculated_members");
        if (cms instanceof List && !((List<?>) cms).isEmpty()) {
            cubeKids.add(buildCalculatedMembers((List<?>) cms));
        }
        Object nss = body.get("named_sets");
        if (nss instanceof List && !((List<?>) nss).isEmpty()) {
            cubeKids.add(buildNamedSets((List<?>) nss));
        }
        if (!cubeKids.isEmpty()) {
            cube.childArray = cubeKids.toArray(new MondrianDef.CubeElement[0]);
        }
        return cube;
    }

    // ---- calculated members ----

    private static MondrianDef.CalculatedMembers buildCalculatedMembers(List<?> list) {
        MondrianDef.CalculatedMembers wrapper = new MondrianDef.CalculatedMembers();
        List<MondrianDef.CalculatedMember> cms = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                cms.add(buildCalculatedMember((Map<?, ?>) item));
            }
        }
        wrapper.array = cms.toArray(new MondrianDef.CalculatedMember[0]);
        return wrapper;
    }

    private static MondrianDef.CalculatedMember buildCalculatedMember(Map<?, ?> m) {
        MondrianDef.CalculatedMember cm = new MondrianDef.CalculatedMember();
        cm.name = M4YamlToXml.str(m.get("name"));
        cm.dimension = M4YamlToXml.str(m.get("dimension"));
        cm.hierarchy = M4YamlToXml.str(m.get("hierarchy"));
        cm.parent = M4YamlToXml.str(m.get("parent"));
        cm.formula = M4YamlToXml.str(m.get("formula"));
        cm.formatString = M4YamlToXml.str(m.get("format_string"));
        Object propsObj = m.get("properties");
        if (propsObj instanceof List && !((List<?>) propsObj).isEmpty()) {
            List<MondrianDef.CalculatedMemberElement> kids = new ArrayList<>();
            for (Object p : (List<?>) propsObj) {
                if (p instanceof Map) {
                    kids.add(buildCalcMemberProperty((Map<?, ?>) p));
                }
            }
            cm.childArray = kids.toArray(new MondrianDef.CalculatedMemberElement[0]);
        }
        return cm;
    }

    private static MondrianDef.CalculatedMemberProperty buildCalcMemberProperty(Map<?, ?> m) {
        MondrianDef.CalculatedMemberProperty prop = new MondrianDef.CalculatedMemberProperty();
        prop.name = M4YamlToXml.str(m.get("name"));
        prop.value = M4YamlToXml.str(m.get("value"));
        prop.expression = M4YamlToXml.str(m.get("expression"));
        return prop;
    }

    // ---- named sets ----

    private static MondrianDef.NamedSets buildNamedSets(List<?> list) {
        MondrianDef.NamedSets wrapper = new MondrianDef.NamedSets();
        List<MondrianDef.NamedSet> nss = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                nss.add(buildNamedSet((Map<?, ?>) item));
            }
        }
        wrapper.array = nss.toArray(new MondrianDef.NamedSet[0]);
        return wrapper;
    }

    private static MondrianDef.NamedSet buildNamedSet(Map<?, ?> m) {
        MondrianDef.NamedSet ns = new MondrianDef.NamedSet();
        ns.name = M4YamlToXml.str(m.get("name"));
        ns.formula = M4YamlToXml.str(m.get("formula"));
        return ns;
    }

    // ---- private cube helpers ----

    private static MondrianDef.Dimensions buildCubeDimensions(List<?> list) {
        MondrianDef.Dimensions wrapper = new MondrianDef.Dimensions();
        List<MondrianDef.Dimension> dims = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                dims.add(buildCubeDimension((Map<?, ?>) item));
            }
        }
        wrapper.array = dims.toArray(new MondrianDef.Dimension[0]);
        return wrapper;
    }

    private static MondrianDef.Dimension buildCubeDimension(Map<?, ?> m) {
        Object source = m.get("source");
        if (source != null) {
            // Usage reference to a shared dimension
            MondrianDef.Dimension d = new MondrianDef.Dimension();
            d.source = M4YamlToXml.str(source);
            return d;
        }
        // Local dimension definition — delegate to shared buildDimension helper
        return M4YamlToXml.buildDimension(M4YamlToXml.str(m.get("name")), m);
    }

    private static MondrianDef.MeasureGroups buildMeasureGroups(List<?> list) {
        MondrianDef.MeasureGroups wrapper = new MondrianDef.MeasureGroups();
        List<MondrianDef.MeasureGroup> mgs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                mgs.add(buildMeasureGroup((Map<?, ?>) item));
            }
        }
        wrapper.array = mgs.toArray(new MondrianDef.MeasureGroup[0]);
        return wrapper;
    }

    private static MondrianDef.MeasureGroup buildMeasureGroup(Map<?, ?> m) {
        MondrianDef.MeasureGroup mg = new MondrianDef.MeasureGroup();
        mg.name = M4YamlToXml.str(m.get("name"));
        mg.table = M4YamlToXml.str(m.get("table"));
        String type = M4YamlToXml.str(m.get("type"));
        if (type != null) {
            mg.type = type;
        }
        List<MondrianDef.MeasureGroupElement> kids = new ArrayList<>();
        Object measures = m.get("measures");
        if (measures instanceof List && !((List<?>) measures).isEmpty()) {
            kids.add(buildMeasures((List<?>) measures));
        }
        Object dimLinks = m.get("dimension_links");
        if (dimLinks instanceof List && !((List<?>) dimLinks).isEmpty()) {
            kids.add(buildDimensionLinks((List<?>) dimLinks));
        }
        if (!kids.isEmpty()) {
            mg.childArray = kids.toArray(new MondrianDef.MeasureGroupElement[0]);
        }
        return mg;
    }

    private static MondrianDef.Measures buildMeasures(List<?> list) {
        MondrianDef.Measures wrapper = new MondrianDef.Measures();
        List<MondrianDef.MeasureOrRef> items = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                items.add(buildMeasure((Map<?, ?>) item));
            }
        }
        wrapper.array = items.toArray(new MondrianDef.MeasureOrRef[0]);
        return wrapper;
    }

    private static MondrianDef.MeasureOrRef buildMeasure(Map<?, ?> m) {
        Object ref = m.get("ref");
        if (ref != null) {
            MondrianDef.MeasureRef mr = new MondrianDef.MeasureRef();
            mr.name = M4YamlToXml.str(ref);
            mr.aggColumn = M4YamlToXml.str(m.get("agg_column"));
            return mr;
        }
        MondrianDef.Measure measure = new MondrianDef.Measure();
        measure.name = M4YamlToXml.str(m.get("name"));
        measure.column = M4YamlToXml.str(m.get("column"));
        measure.table = M4YamlToXml.str(m.get("table"));
        measure.aggregator = M4YamlToXml.str(m.get("aggregator"));
        measure.formatString = M4YamlToXml.str(m.get("format_string"));
        measure.datatype = M4YamlToXml.str(m.get("datatype"));
        return measure;
    }

    private static MondrianDef.DimensionLinks buildDimensionLinks(List<?> list) {
        MondrianDef.DimensionLinks wrapper = new MondrianDef.DimensionLinks();
        List<MondrianDef.DimensionLink> links = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                links.add(buildDimensionLink((Map<?, ?>) item));
            }
        }
        wrapper.array = links.toArray(new MondrianDef.DimensionLink[0]);
        return wrapper;
    }

    private static MondrianDef.DimensionLink buildDimensionLink(Map<?, ?> m) {
        String type = M4YamlToXml.str(m.get("type"));
        String dimension = M4YamlToXml.str(m.get("dimension"));
        if ("foreign_key".equals(type)) {
            MondrianDef.ForeignKeyLink link = new MondrianDef.ForeignKeyLink();
            link.dimension = dimension;
            link.foreignKeyColumn = M4YamlToXml.str(m.get("foreign_key_column"));
            link.attribute = M4YamlToXml.str(m.get("attribute"));
            return link;
        } else if ("copy".equals(type)) {
            MondrianDef.CopyLink link = new MondrianDef.CopyLink();
            link.dimension = dimension;
            // Note: CopyLink has no 'attribute' field; YAML attribute is ignored.
            // column_refs list → columnRefs array if provided
            Object colRefs = m.get("column_refs");
            if (colRefs instanceof List && !((List<?>) colRefs).isEmpty()) {
                List<MondrianDef.Column> cols = new ArrayList<>();
                for (Object c : (List<?>) colRefs) {
                    cols.add(new MondrianDef.Column(null, M4YamlToXml.str(c)));
                }
                link.columnRefs = cols.toArray(new MondrianDef.Column[0]);
            }
            return link;
        } else if ("no_link".equals(type)) {
            MondrianDef.NoLink link = new MondrianDef.NoLink();
            link.dimension = dimension;
            return link;
        } else if ("fact".equals(type)) {
            MondrianDef.FactLink link = new MondrianDef.FactLink();
            link.dimension = dimension;
            return link;
        } else {
            // Unknown type: fall back to NoLink to avoid NPE
            MondrianDef.NoLink link = new MondrianDef.NoLink();
            link.dimension = dimension;
            return link;
        }
    }
}
