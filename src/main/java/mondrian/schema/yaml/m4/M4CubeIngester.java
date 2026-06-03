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
        if (c.childArray != null) {
            List<Object> dimList = null;
            List<Object> mgList = null;
            List<Object> cmList = null;
            List<Object> nsList = null;
            for (MondrianDef.CubeElement ce : c.childArray) {
                if (ce instanceof MondrianDef.Dimensions) {
                    dimList = cubeDimensions((MondrianDef.Dimensions) ce);
                } else if (ce instanceof MondrianDef.MeasureGroups) {
                    mgList = measureGroups((MondrianDef.MeasureGroups) ce);
                } else if (ce instanceof MondrianDef.CalculatedMembers) {
                    cmList = calculatedMembers((MondrianDef.CalculatedMembers) ce);
                } else if (ce instanceof MondrianDef.NamedSets) {
                    nsList = namedSets((MondrianDef.NamedSets) ce);
                }
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
        if (cm.dimension != null) {
            out.put("dimension", cm.dimension);
        }
        if (cm.hierarchy != null) {
            out.put("hierarchy", cm.hierarchy);
        }
        if (cm.parent != null) {
            out.put("parent", cm.parent);
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
        // Collect CalculatedMemberProperty children
        if (cm.childArray != null) {
            List<Object> props = new ArrayList<>();
            for (MondrianDef.CalculatedMemberElement child : cm.childArray) {
                if (child instanceof MondrianDef.CalculatedMemberProperty) {
                    props.add(calcMemberProperty((MondrianDef.CalculatedMemberProperty) child));
                }
            }
            if (!props.isEmpty()) {
                out.put("properties", props);
            }
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
            // Dimension usage — reference to a shared dimension
            Map<String, Object> out = new LinkedHashMap<>();
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
        // TODO Phase 4+: approxRowCount, ignoreUnrelatedDimensions, Cube/MeasureGroup annotations not yet captured
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
            if (ref.name != null) {
                out.put("ref", ref.name);
            }
            if (ref.aggColumn != null) {
                out.put("agg_column", ref.aggColumn);
            }
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
}
