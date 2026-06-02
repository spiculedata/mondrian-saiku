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

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * #34 M4: unit tests for the Mondrian-4 YAML converter pipeline —
 * starting with {@link M4Detection} vocabulary dispatch and growing
 * through Phase 1 (physical layer) as later tasks append tests.
 */
public class M4PhysicalLayerTest {

    @Test
    public void detectsM4YamlByMetamodelVersion() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "FoodMart");
        schema.put("metamodel_version", "4.0");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", schema);
        assertTrue(M4Detection.isM4Yaml(root));
    }

    @Test
    public void detectsM3YamlScalarSchemaAsNotM4() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "FoodMart");          // M3 scalar form
        assertFalse(M4Detection.isM4Yaml(root));
    }

    @Test
    public void detectsM4YamlByPhysicalSchemaKey() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "FoodMart");
        root.put("physical_schema", new LinkedHashMap<>());
        assertTrue(M4Detection.isM4Yaml(root));
    }

    @Test
    public void emptyRootIsNotM4() {
        assertFalse(M4Detection.isM4Yaml(new LinkedHashMap<>()));
    }

    @Test
    public void rootWithoutSchemaOrPhysicalSchemaIsNotM4() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("other_key", "value");
        assertFalse(M4Detection.isM4Yaml(root));
    }

    // ---- emit (YAML -> M4 XML) ----

    private static final String PHYS_YAML =
        "schema:\n"
        + "  name: FoodMart\n"
        + "  metamodel_version: \"4.0\"\n"
        + "physical_schema:\n"
        + "  tables:\n"
        + "    - name: salary\n"
        + "    - name: salary\n"
        + "      alias: salary2\n"
        + "    - name: store\n"
        + "      key: [store_id]\n"
        + "    - name: product\n"
        + "      key_column: product_id\n";

    @Test
    public void emitsSchemaHeaderAndPhysicalTables() {
        String xml = M4YamlToXml.toXml(PHYS_YAML);
        assertTrue(xml, xml.contains("<Schema"));
        assertTrue(xml, xml.contains("name=\"FoodMart\""));
        assertTrue(xml, xml.contains("metamodelVersion=\"4.0\""));
        assertTrue(xml, xml.contains("<PhysicalSchema"));
        assertTrue(xml, xml.contains("<Table name=\"salary\""));
        assertTrue(xml, xml.contains("alias=\"salary2\""));
        assertTrue(xml, xml.contains("<Key"));
        assertTrue(xml, xml.contains("name=\"store_id\""));
        assertTrue(xml, xml.contains("keyColumn=\"product_id\""));
    }
}
