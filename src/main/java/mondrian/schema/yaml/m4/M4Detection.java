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

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.Map;

/**
 * #34 M4: vocabulary detection so the YAML converters can dispatch
 * between the legacy (Mondrian-3) and modern (Mondrian-4) paths.
 */
public final class M4Detection {

    private M4Detection() {}

    /**
     * True if the parsed YAML root describes a Mondrian-4 schema.
     * M4 uses a {@code schema:} mapping carrying {@code metamodel_version}
     * (and/or a top-level {@code physical_schema} / {@code measure_groups}
     * inside cubes); M3 uses a scalar {@code schema: "Name"} top key.
     */
    public static boolean isM4Yaml(Map<?, ?> root) {
        Object schema = root.get("schema");
        if (schema instanceof Map) {
            Object v = ((Map<?, ?>) schema).get("metamodel_version");
            if (v != null && String.valueOf(v).startsWith("4")) {
                return true;
            }
        }
        return root.containsKey("physical_schema");
    }

    /**
     * True if the XML {@code <Schema>} element uses Mondrian-4 vocabulary:
     * a {@code <PhysicalSchema>} child or any {@code <Cube><MeasureGroups>}
     * grandchild. Mirrors {@code RolapSchemaLoader.hasMondrian4Elements}.
     */
    public static boolean isM4Xml(Element schema) {
        NodeList children = schema.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) child;
            if ("PhysicalSchema".equals(el.getTagName())) {
                return true;
            }
            if ("Cube".equals(el.getTagName())) {
                NodeList grandchildren = el.getChildNodes();
                for (int j = 0; j < grandchildren.getLength(); j++) {
                    Node gc = grandchildren.item(j);
                    if (gc.getNodeType() == Node.ELEMENT_NODE
                        && "MeasureGroups".equals(
                            ((Element) gc).getTagName()))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
