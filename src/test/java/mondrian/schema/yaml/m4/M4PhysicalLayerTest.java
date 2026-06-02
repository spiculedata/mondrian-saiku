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

import mondrian.schema.yaml.XmlSchemaToYaml;
import mondrian.schema.yaml.YamlSchemaConverter;

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

    @Test
    public void emitsPhysicalLinksWithForeignKey() {
        String yaml = PHYS_YAML
            + "  links:\n"
            + "    - {source: product_class, target: product,"
            + " foreign_key: [product_class_id]}\n";
        String xml = M4YamlToXml.toXml(yaml);
        assertTrue(xml, xml.contains("<Link"));
        assertTrue(xml, xml.contains("source=\"product_class\""));
        assertTrue(xml, xml.contains("target=\"product\""));
        assertTrue(xml, xml.contains("<ForeignKey"));
        assertTrue(xml, xml.contains("name=\"product_class_id\""));
    }

    @Test
    public void emitsCalculatedColumnWithSqlDialects() {
        String yaml = PHYS_YAML
            + "    - name: customer\n"
            + "      key: [customer_id]\n"
            + "      calculated_columns:\n"
            + "        - name: full_name\n"
            + "          type: String\n"
            + "          expression:\n"
            + "            generic: \"{fullname}\"\n"
            + "            oracle: \"a || b\"\n";
        String xml = M4YamlToXml.toXml(yaml);
        assertTrue(xml, xml.contains("<ColumnDefs"));
        assertTrue(xml, xml.contains("<CalculatedColumnDef"));
        assertTrue(xml, xml.contains("name=\"full_name\""));
        assertTrue(xml, xml.contains("<ExpressionView"));
        assertTrue(xml, xml.contains("dialect=\"oracle\""));
        assertTrue(xml, xml.contains("dialect=\"generic\""));
        assertTrue(xml, xml.contains("name=\"customer\""));
        assertTrue(xml, xml.contains("{fullname}"));
        assertTrue(xml, xml.contains("a || b"));
    }

    // ---- ingest (M4 XML -> YAML) ----

    private static final String PHYS_XML =
        "<Schema name='FoodMart' metamodelVersion='4.0'>"
        + "  <PhysicalSchema>"
        + "    <Table name='salary'/>"
        + "    <Table name='store'><Key><Column name='store_id'/></Key></Table>"
        + "    <Table name='product' keyColumn='product_id'/>"
        + "    <Link source='product_class' target='product'>"
        + "      <ForeignKey><Column name='product_class_id'/></ForeignKey>"
        + "    </Link>"
        + "  </PhysicalSchema>"
        + "</Schema>";

    @Test
    public void ingestsPhysicalSchemaToYaml() {
        String yaml = M4XmlToYaml.toYaml(PHYS_XML);
        assertTrue(yaml, yaml.contains("metamodel_version: \"4.0\"")
            || yaml.contains("metamodel_version: '4.0'"));
        assertTrue(yaml, yaml.contains("physical_schema:"));
        assertTrue(yaml, yaml.contains("name: \"salary\"")
            || yaml.contains("name: salary"));
        assertTrue(yaml, yaml.contains("store_id"));
        assertTrue(yaml, yaml.contains("links:"));
        assertTrue(yaml, yaml.contains("product_class_id"));
        assertTrue(yaml, yaml.contains("key_column: \"product_id\"")
            || yaml.contains("key_column: product_id"));
    }

    @Test
    public void ingestsCalculatedColumns() {
        String xml =
            "<Schema name='FoodMart' metamodelVersion='4.0'>"
            + "<PhysicalSchema><Table name='customer'>"
            + "<Key><Column name='customer_id'/></Key>"
            + "<ColumnDefs><CalculatedColumnDef name='full_name' type='String'>"
            + "<ExpressionView>"
            + "<SQL dialect='generic'>GEN_BODY</SQL>"
            + "<SQL dialect='oracle'>ORA_BODY</SQL>"
            + "</ExpressionView></CalculatedColumnDef></ColumnDefs>"
            + "</Table></PhysicalSchema></Schema>";
        String yaml = M4XmlToYaml.toYaml(xml);
        assertTrue(yaml, yaml.contains("calculated_columns:"));
        assertTrue(yaml, yaml.contains("full_name"));
        assertTrue(yaml, yaml.contains("type: String")
            || yaml.contains("type: \"String\""));
        assertTrue(yaml, yaml.contains("expression:"));
        assertTrue(yaml, yaml.contains("generic"));
        assertTrue(yaml, yaml.contains("oracle"));
        assertTrue(yaml, yaml.contains("GEN_BODY"));
        assertTrue(yaml, yaml.contains("ORA_BODY"));
    }

    // ---- dispatch through public API ----

    @Test
    public void publicToYamlDispatchesToM4ForM4Xml() {
        String yaml = XmlSchemaToYaml.toYaml(PHYS_XML);
        assertTrue(yaml, yaml.contains("physical_schema:"));
    }

    @Test
    public void publicToXmlDispatchesToM4ForM4Yaml() {
        String xml =
            YamlSchemaConverter.toXml(PHYS_YAML);
        assertTrue(xml, xml.contains("<PhysicalSchema"));
        assertTrue(xml, xml.contains("metamodelVersion=\"4.0\""));
    }

    @Test
    public void ingestsTableSchemaAttribute() {
        String xml =
            "<Schema name='FoodMart' metamodelVersion='4.0'>"
            + "<PhysicalSchema>"
            + "<Table name='store' schema='dbo'/>"
            + "</PhysicalSchema></Schema>";
        String yaml = M4XmlToYaml.toYaml(xml);
        assertTrue(yaml, yaml.contains("schema: \"dbo\"")
            || yaml.contains("schema: dbo"));
    }

    @Test
    public void publicToYamlStillHandlesM3Scalar() {
        String m3 = "<Schema name='S'><Dimension name='D'>"
            + "<Hierarchy hasAll='true'><Table name='t'/>"
            + "<Level name='L' column='c'/></Hierarchy></Dimension></Schema>";
        String yaml = XmlSchemaToYaml.toYaml(m3);
        assertTrue(yaml, yaml.contains("schema: \"S\"")
            || yaml.contains("schema: S"));
        assertFalse(yaml, yaml.contains("physical_schema:"));
    }
}
