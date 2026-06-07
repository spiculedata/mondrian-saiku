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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #34 M4: package-private helper that ingests a {@link MondrianDef.Cube}
 * into a YAML map. Extracted from {@link M4XmlToYaml} to keep that class
 * under the 800-line cap.
 *
 * <p>The public entry point is {@link #cube(MondrianDef.Cube)}, called by
 * {@code M4XmlToYaml.toYaml} for each {@code <Cube>} in the schema.
 * Shared dimension helpers ({@code dimension}, {@code columnNames}) remain in
 * {@link M4XmlToYaml} as package-private statics and are called directly.
 */
final class M4CubeIngester {

    private M4CubeIngester() {}

    // ---- public entry point ----

    static Map<String, Object> cube(MondrianDef.Cube c) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (c.defaultMeasure != null) {
            out.put("default_measure", c.defaultMeasure);
        }
        // #110 display attributes.
        M4XmlToYaml.putDisplay(out, c.caption, c.description, c.visible);
        if (c.childArray != null) {
            Map<String, Object> annMap = null;
            List<Object> dimList = null;
            List<Object> mgList = null;
            List<Object> cmList = null;
            List<Object> nsList = null;
            List<Object> tcList = null;
            for (MondrianDef.CubeElement ce : c.childArray) {
                if (ce instanceof MondrianDef.Annotations) {
                    annMap = M4XmlToYaml.annotations((MondrianDef.Annotations) ce);
                } else if (ce instanceof MondrianDef.Dimensions) {
                    dimList = cubeDimensions((MondrianDef.Dimensions) ce);
                } else if (ce instanceof MondrianDef.MeasureGroups) {
                    mgList = measureGroups((MondrianDef.MeasureGroups) ce);
                } else if (ce instanceof MondrianDef.CalculatedMembers) {
                    cmList = calculatedMembers((MondrianDef.CalculatedMembers) ce);
                } else if (ce instanceof MondrianDef.NamedSets) {
                    nsList = namedSets((MondrianDef.NamedSets) ce);
                } else if (ce instanceof MondrianDef.TimeCalcs) {
                    tcList = timeCalcs((MondrianDef.TimeCalcs) ce);
                }
            }
            // Annotations placed first (after default_measure, before dimensions)
            if (annMap != null && !annMap.isEmpty()) {
                out.put("annotations", annMap);
            }
            if (dimList != null && !dimList.isEmpty()) {
                out.put("dimensions", dimList);
            }
            if (mgList != null && !mgList.isEmpty()) {
                out.put("measure_groups", mgList);
            }
            if (cmList != null && !cmList.isEmpty()) {
                out.put("calculated_members", cmList);
            }
            if (nsList != null && !nsList.isEmpty()) {
                out.put("named_sets", nsList);
            }
            if (tcList != null && !tcList.isEmpty()) {
                out.put("time_calcs", tcList);
            }
        }
        return out;
    }

    // ---- calculated members ----

    private static List<Object> calculatedMembers(MondrianDef.CalculatedMembers wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.CalculatedMember cm : wrapper.array) {
                out.add(calculatedMember(cm));
            }
        }
        return out;
    }

    private static Map<String, Object> calculatedMember(MondrianDef.CalculatedMember cm) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", cm.name);
        // Annotations second (after name, before dimension)
        if (cm.childArray != null) {
            for (MondrianDef.CalculatedMemberElement child : cm.childArray) {
                if (child instanceof MondrianDef.Annotations) {
                    Map<String, Object> ann =
                        M4XmlToYaml.annotations((MondrianDef.Annotations) child);
                    if (ann != null && !ann.isEmpty()) {
                        out.put("annotations", ann);
                    }
                }
            }
        }
        if (cm.dimension != null) {
            out.put("dimension", cm.dimension);
        }
        if (cm.hierarchy != null) {
            out.put("hierarchy", cm.hierarchy);
        }
        if (cm.parent != null) {
            out.put("parent", cm.parent);
        }
        if (cm.caption != null) {
            out.put("caption", cm.caption);
        }
        if (cm.description != null) {
            out.put("description", cm.description);
        }
        if (cm.visible != null) {
            out.put("visible", cm.visible);
        }
        // Prefer formula attribute; fall back to <Formula> child cdata
        String formula = cm.formula;
        if (formula == null && cm.childArray != null) {
            for (MondrianDef.CalculatedMemberElement child : cm.childArray) {
                if (child instanceof MondrianDef.Formula) {
                    formula = ((MondrianDef.Formula) child).cdata;
                    break;
                }
            }
        }
        if (formula != null) {
            out.put("formula", formula);
        }
        if (cm.formatString != null) {
            out.put("format_string", cm.formatString);
        }
        // Scan children for CellFormatter and CalculatedMemberProperty
        if (cm.childArray != null) {
            MondrianDef.CellFormatter cellFmt = null;
            List<Object> props = new ArrayList<>();
            for (MondrianDef.CalculatedMemberElement child : cm.childArray) {
                if (child instanceof MondrianDef.CellFormatter) {
                    cellFmt = (MondrianDef.CellFormatter) child;
                } else if (child instanceof MondrianDef.CalculatedMemberProperty) {
                    props.add(calcMemberProperty((MondrianDef.CalculatedMemberProperty) child));
                }
            }
            if (cellFmt != null) {
                out.put("cell_formatter", cellFormatter(cellFmt));
            }
            if (!props.isEmpty()) {
                out.put("properties", props);
            }
        }
        return out;
    }

    private static Map<String, Object> cellFormatter(MondrianDef.CellFormatter cf) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (cf.className != null) {
            out.put("class_name", cf.className);
        }
        if (cf.script != null) {
            out.put("script", script(cf.script));
        }
        return out;
    }

    private static Map<String, Object> script(MondrianDef.Script s) {
        Map<String, Object> out = new LinkedHashMap<>();
        // Suppress XOM's default "JavaScript" — it is set even when the XML
        // omitted the attribute, so emitting it would cause yaml != yaml2 for
        // round-trips that start with a <Script> that has no language attribute.
        if (s.language != null && !"JavaScript".equals(s.language)) {
            out.put("language", s.language);
        }
        if (s.cdata != null) {
            out.put("body", s.cdata);
        }
        return out;
    }

    private static Map<String, Object> calcMemberProperty(MondrianDef.CalculatedMemberProperty prop) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", prop.name);
        if (prop.value != null) {
            out.put("value", prop.value);
        }
        if (prop.expression != null) {
            out.put("expression", prop.expression);
        }
        return out;
    }

    // ---- named sets ----

    private static List<Object> namedSets(MondrianDef.NamedSets wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.NamedSet ns : wrapper.array) {
                out.add(namedSet(ns));
            }
        }
        return out;
    }

    private static Map<String, Object> namedSet(MondrianDef.NamedSet ns) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", ns.name);
        // Prefer formula attribute; fall back to <Formula> child cdata
        String formula = ns.formula;
        if (formula == null && ns.childArray != null) {
            for (MondrianDef.CalculatedMemberElement child : ns.childArray) {
                if (child instanceof MondrianDef.Formula) {
                    formula = ((MondrianDef.Formula) child).cdata;
                    break;
                }
            }
        }
        if (formula != null) {
            out.put("formula", formula);
        }
        return out;
    }

    // ---- time calcs ----

    private static List<Object> timeCalcs(MondrianDef.TimeCalcs wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.TimeCalc tc : wrapper.array) {
                out.add(timeCalc(tc));
            }
        }
        return out;
    }

    private static Map<String, Object> timeCalc(MondrianDef.TimeCalc tc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", tc.name);
        out.put("type", tc.type);
        out.put("measure", tc.measure);
        if (tc.timeDimension != null) {
            out.put("time_dimension", tc.timeDimension);
        }
        if (tc.window != null) {
            out.put("window", tc.window);
        }
        if (tc.function != null) {
            out.put("function", tc.function);
        }
        if (tc.formatString != null) {
            out.put("format_string", tc.formatString);
        }
        return out;
    }

    // ---- private cube helpers ----

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
            // Dimension usage — reference to a shared dimension. #109: keep
            // the role-play `name` when it differs from the source, else two
            // distinct role-played usages (Order Date / Ship Date over one
            // Date dimension) collapse to identical anonymous refs and the
            // dimension_links that target the role names dangle.
            Map<String, Object> out = new LinkedHashMap<>();
            if (d.name != null && !d.name.equals(d.source)) {
                out.put("name", d.name);
            }
            out.put("source", d.source);
            return out;
        }
        // Local dimension definition — name goes first, then dimension body
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.name);
        out.putAll(M4XmlToYaml.dimension(d));
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
        if (mg.approxRowCount != null) {
            out.put("approx_row_count", mg.approxRowCount);
        }
        // Only emit when true (default is false — keeps round-trip clean)
        if (mg.ignoreUnrelatedDimensions != null
                && Boolean.TRUE.equals(mg.ignoreUnrelatedDimensions)) {
            out.put("ignore_unrelated_dimensions", true);
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
            if (ref.name != null) {
                out.put("ref", ref.name);
            }
            if (ref.aggColumn != null) {
                out.put("agg_column", ref.aggColumn);
            }
        } else if (mor instanceof MondrianDef.Measure) {
            MondrianDef.Measure m = (MondrianDef.Measure) mor;
            out.put("name", m.name);
            // Annotations second (after name, before column)
            if (m.childArray != null) {
                for (MondrianDef.MeasureElement me : m.childArray) {
                    if (me instanceof MondrianDef.Annotations) {
                        Map<String, Object> ann =
                            M4XmlToYaml.annotations((MondrianDef.Annotations) me);
                        if (ann != null && !ann.isEmpty()) {
                            out.put("annotations", ann);
                        }
                    }
                }
            }
            if (m.column != null) {
                out.put("column", m.column);
            }
            if (m.table != null) {
                out.put("table", m.table);
            }
            if (m.aggregator != null) {
                out.put("aggregator", m.aggregator);
            }
            // #104 percentile parameter (for aggregator: percentile).
            if (m.percentile != null) {
                out.put("percentile", m.percentile);
            }
            // #119 measure-level distinct grain.
            if (m.distinctKeyColumn != null) {
                out.put("distinct_key_column", m.distinctKeyColumn);
            }
            if (m.formatString != null) {
                out.put("format_string", m.formatString);
            }
            if (m.datatype != null) {
                out.put("datatype", m.datatype);
            }
            // #110 display attributes.
            M4XmlToYaml.putDisplay(out, m.caption, m.description, m.visible);
            // Capture CalculatedMemberProperty children (e.g. MEMBER_ORDINAL).
            // Dropping these causes ordinal collisions with calculated members.
            if (m.childArray != null) {
                List<Map<String, Object>> props = new ArrayList<>();
                for (MondrianDef.MeasureElement me : m.childArray) {
                    if (me instanceof MondrianDef.CalculatedMemberProperty) {
                        props.add(calcMemberProperty(
                            (MondrianDef.CalculatedMemberProperty) me));
                    }
                }
                if (!props.isEmpty()) {
                    out.put("properties", props);
                }
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
            if (fkl.foreignKeyColumn != null && !fkl.foreignKeyColumn.isEmpty()) {
                out.put("foreign_key_column", fkl.foreignKeyColumn);
            } else if (fkl.foreignKey != null
                    && fkl.foreignKey.array != null
                    && fkl.foreignKey.array.length > 0) {
                // Compound or explicit <ForeignKey><Column .../></ForeignKey>
                out.put("foreign_key", M4XmlToYaml.columnNames(fkl.foreignKey.array));
            }
            if (fkl.attribute != null) {
                out.put("attribute", fkl.attribute);
            }
        } else if (dl instanceof MondrianDef.CopyLink) {
            MondrianDef.CopyLink cl = (MondrianDef.CopyLink) dl;
            out.put("type", "copy");
            out.put("dimension", cl.dimension);
            if (cl.columnRefs != null && cl.columnRefs.length > 0) {
                List<Object> colRefList = new ArrayList<>();
                for (MondrianDef.Column col : cl.columnRefs) {
                    Map<String, Object> colMap = new LinkedHashMap<>();
                    if (col.table != null) {
                        colMap.put("table", col.table);
                    }
                    colMap.put("name", col.name);
                    if (col.aggColumn != null) {
                        colMap.put("agg_column", col.aggColumn);
                    }
                    colRefList.add(colMap);
                }
                out.put("column_refs", colRefList);
            }
        } else if (dl instanceof MondrianDef.NoLink) {
            out.put("type", "no_link");
            out.put("dimension", dl.dimension);
        } else if (dl instanceof MondrianDef.FactLink) {
            out.put("type", "fact");
            out.put("dimension", dl.dimension);
        } else if (dl instanceof MondrianDef.ReferenceLink) {
            MondrianDef.ReferenceLink rl = (MondrianDef.ReferenceLink) dl;
            out.put("type", "reference");
            out.put("dimension", rl.dimension);
            if (rl.viaDimension != null) {
                out.put("via_dimension", rl.viaDimension);
            }
            if (rl.viaAttribute != null) {
                out.put("via_attribute", rl.viaAttribute);
            }
        } else if (dl instanceof MondrianDef.BridgeLink) {
            // #107 bridge (many-to-many) link. Mirror of the YAML→XML map in
            // M4CubeBuilder.buildDimensionLink. Only non-null attributes are
            // emitted, so an omitted (defaulted) aggregation round-trips
            // without being materialised as 'fullCount'.
            MondrianDef.BridgeLink bl = (MondrianDef.BridgeLink) dl;
            out.put("type", "bridge");
            out.put("dimension", bl.dimension);
            if (bl.bridgeTable != null) {
                out.put("bridge_table", bl.bridgeTable);
            }
            if (bl.factForeignKeyColumn != null) {
                out.put("fact_foreign_key_column", bl.factForeignKeyColumn);
            }
            if (bl.bridgeFactKeyColumn != null) {
                out.put("bridge_fact_key_column", bl.bridgeFactKeyColumn);
            }
            if (bl.bridgeDimensionKeyColumn != null) {
                out.put("bridge_dimension_key_column", bl.bridgeDimensionKeyColumn);
            }
            if (bl.aggregation != null) {
                out.put("aggregation", bl.aggregation);
            }
            if (bl.weightColumn != null) {
                out.put("weight_column", bl.weightColumn);
            }
        }
        return out;
    }
}
