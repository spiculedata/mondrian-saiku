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

import static org.junit.Assert.assertEquals;
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

    // ---- ingest (M4 XML -> YAML): shared dimensions ----

    private static final String DIM_XML =
        "<Schema name='FoodMart' metamodelVersion='4.0'>"
        + "<Dimension name='Store' table='store' key='Store Id'>"
        + "<Attributes>"
        + "<Attribute name='Store Country' hasHierarchy='false'>"
        + "<Key><Column name='store_country'/></Key></Attribute>"
        + "<Attribute name='Store City' hasHierarchy='false'>"
        + "<Key><Column name='store_state'/><Column name='store_city'/></Key>"
        + "<Name><Column name='store_city'/></Name></Attribute>"
        + "<Attribute name='Store Id' keyColumn='store_id' hasHierarchy='false'/>"
        + "<Attribute name='Store Name' keyColumn='store_name' hasHierarchy='false'>"
        + "<Property attribute='Store Type'/></Attribute>"
        + "<Attribute name='Store Type' keyColumn='store_type'/>"
        + "</Attributes>"
        + "<Hierarchies>"
        + "<Hierarchy name='Stores' allMemberName='All Stores'>"
        + "<Level attribute='Store Country'/><Level attribute='Store Name'/>"
        + "</Hierarchy></Hierarchies>"
        + "</Dimension></Schema>";

    @Test
    public void ingestsSharedDimension() {
        String yaml = M4XmlToYaml.toYaml(DIM_XML);
        assertTrue(yaml, yaml.contains("shared_dimensions:"));
        assertTrue(yaml, yaml.contains("Store:"));
        assertTrue(yaml, yaml.contains("table: \"store\"")
            || yaml.contains("table: store"));
        assertTrue(yaml, yaml.contains("key: \"Store Id\"")
            || yaml.contains("key: Store Id"));
        assertTrue(yaml, yaml.contains("attributes:"));
        assertTrue(yaml, yaml.contains("Store Country"));
        assertTrue(yaml, yaml.contains("store_country"));        // from <Key>
        assertTrue(yaml, yaml.contains("key_column"));           // from keyColumn attr
        assertTrue(yaml, yaml.contains("name_column"));          // from <Name> single col
        assertTrue(yaml, yaml.contains("has_hierarchy: false"));
        assertTrue(yaml, yaml.contains("properties:"));
        assertTrue(yaml, yaml.contains("Store Type"));
        assertTrue(yaml, yaml.contains("hierarchies:"));
        assertTrue(yaml, yaml.contains("all_member_name: \"All Stores\"")
            || yaml.contains("all_member_name: All Stores"));
        assertTrue(yaml, yaml.contains("levels:"));
    }

    @Test
    public void sharedDimensionRoundTripsThroughEmit() {
        String yaml = M4XmlToYaml.toYaml(DIM_XML);
        String xml = M4YamlToXml.toXml(yaml);
        // Re-ingest the emitted XML; the second YAML must equal the first.
        String yaml2 = M4XmlToYaml.toYaml(xml);
        assertEquals(yaml, yaml2);
    }

    // ---- emit (YAML -> M4 XML): shared dimensions ----

    private static final String DIM_YAML =
        "schema:\n"
        + "  name: FoodMart\n"
        + "  metamodel_version: \"4.0\"\n"
        + "shared_dimensions:\n"
        + "  Store:\n"
        + "    table: store\n"
        + "    key: Store Id\n"
        + "    attributes:\n"
        + "      - {name: Store Country, has_hierarchy: false, key: [store_country]}\n"
        + "      - {name: Store Id, key_column: store_id, has_hierarchy: false}\n"
        + "      - name: Store Name\n"
        + "        key_column: store_name\n"
        + "        has_hierarchy: false\n"
        + "        properties: [Store Type]\n"
        + "      - {name: Store Type, key_column: store_type}\n"
        + "    hierarchies:\n"
        + "      - name: Stores\n"
        + "        all_member_name: All Stores\n"
        + "        levels: [Store Country, Store Name]\n";

    @Test
    public void emitsSharedDimensionWithAttributesAndHierarchies() {
        String xml = M4YamlToXml.toXml(DIM_YAML);
        assertTrue(xml, xml.contains("<Dimension"));
        assertTrue(xml, xml.contains("name=\"Store\""));
        assertTrue(xml, xml.contains("table=\"store\""));
        assertTrue(xml, xml.contains("key=\"Store Id\""));
        assertTrue(xml, xml.contains("<Attributes"));
        assertTrue(xml, xml.contains("<Attribute name=\"Store Country\""));
        assertTrue(xml, xml.contains("hasHierarchy=\"false\""));
        assertTrue(xml, xml.contains("<Key"));
        assertTrue(xml, xml.contains("name=\"store_country\""));
        assertTrue(xml, xml.contains("keyColumn=\"store_name\""));
        assertTrue(xml, xml.contains("<Property"));
        assertTrue(xml, xml.contains("attribute=\"Store Type\""));
        assertTrue(xml, xml.contains("<Hierarchies"));
        assertTrue(xml, xml.contains("<Hierarchy name=\"Stores\""));
        assertTrue(xml, xml.contains("allMemberName=\"All Stores\""));
        assertTrue(xml, xml.contains("<Level attribute=\"Store Country\""));
    }

    // ---- emit (YAML -> M4 XML): cubes ----

    private static final String CUBE_YAML =
        "schema:\n"
        + "  name: FoodMart\n"
        + "  metamodel_version: \"4.0\"\n"
        + "cubes:\n"
        + "  Sales:\n"
        + "    default_measure: Unit Sales\n"
        + "    dimensions:\n"
        + "      - {source: Store}\n"
        + "      - name: Promotion\n"
        + "        table: promotion\n"
        + "        key: Promotion Id\n"
        + "        attributes:\n"
        + "          - {name: Promotion Id, key_column: promotion_id, has_hierarchy: false}\n"
        + "        hierarchies:\n"
        + "          - {name: Promotions, levels: [Promotion Id]}\n"
        + "    measure_groups:\n"
        + "      - name: Sales\n"
        + "        table: sales_fact_1997\n"
        + "        measures:\n"
        + "          - {name: Unit Sales, column: unit_sales, aggregator: sum, format_string: Standard}\n"
        + "        dimension_links:\n"
        + "          - {type: foreign_key, dimension: Store, foreign_key_column: store_id}\n"
        + "          - {type: copy, dimension: Time, attribute: Month}\n"
        + "          - {type: no_link, dimension: Promotion}\n";

    @Test
    public void emitsCubeWithMeasureGroupsAndLinks() {
        String xml = M4YamlToXml.toXml(CUBE_YAML);
        assertTrue(xml, xml.contains("<Cube name=\"Sales\""));
        assertTrue(xml, xml.contains("defaultMeasure=\"Unit Sales\""));
        assertTrue(xml, xml.contains("<Dimensions"));
        assertTrue(xml, xml.contains("<Dimension source=\"Store\""));
        assertTrue(xml, xml.contains("<Dimension name=\"Promotion\""));
        assertTrue(xml, xml.contains("<MeasureGroups"));
        assertTrue(xml, xml.contains("<MeasureGroup name=\"Sales\""));
        assertTrue(xml, xml.contains("table=\"sales_fact_1997\""));
        assertTrue(xml, xml.contains("<Measure name=\"Unit Sales\""));
        assertTrue(xml, xml.contains("aggregator=\"sum\""));
        assertTrue(xml, xml.contains("formatString=\"Standard\""));
        assertTrue(xml, xml.contains("<DimensionLinks"));
        // ForeignKeyLink emits foreignKeyColumn before dimension (XOM attr order)
        assertTrue(xml, xml.contains("<ForeignKeyLink"));
        assertTrue(xml, xml.contains("foreignKeyColumn=\"store_id\""));
        assertTrue(xml, xml.contains("dimension=\"Store\""));
        assertTrue(xml, xml.contains("<CopyLink dimension=\"Time\""));
        assertTrue(xml, xml.contains("<NoLink dimension=\"Promotion\""));
    }

    @Test
    public void emitsFactLink() {
        String yaml =
            "schema: {name: S, metamodel_version: \"4.0\"}\n"
            + "cubes:\n  C:\n    measure_groups:\n"
            + "      - {table: f, measures: [{name: M, column: c, aggregator: sum}],"
            + " dimension_links: [{type: fact, dimension: D}]}\n";
        String xml = M4YamlToXml.toXml(yaml);
        assertTrue(xml, xml.contains("<FactLink dimension=\"D\""));
    }

    // ---- ingest (M4 XML -> YAML): cubes ----

    private static final String CUBE_XML =
        "<Schema name='FoodMart' metamodelVersion='4.0'>"
        + "<Cube name='Sales' defaultMeasure='Unit Sales'>"
        + "<Dimensions>"
        + "<Dimension source='Store'/>"
        + "<Dimension name='Promotion' table='promotion' key='Promotion Id'>"
        + "<Attributes>"
        + "<Attribute name='Promotion Id' keyColumn='promotion_id' hasHierarchy='false'/>"
        + "</Attributes>"
        + "<Hierarchies><Hierarchy name='Promotions'>"
        + "<Level attribute='Promotion Id'/></Hierarchy></Hierarchies>"
        + "</Dimension>"
        + "</Dimensions>"
        + "<MeasureGroups>"
        + "<MeasureGroup name='Sales' table='sales_fact_1997'>"
        + "<Measures>"
        + "<Measure name='Unit Sales' column='unit_sales' aggregator='sum' formatString='Standard'/>"
        + "</Measures>"
        + "<DimensionLinks>"
        + "<ForeignKeyLink dimension='Store' foreignKeyColumn='store_id'/>"
        + "<NoLink dimension='Promotion'/>"
        + "</DimensionLinks>"
        + "</MeasureGroup>"
        + "<MeasureGroup table='agg_c' type='aggregate'>"
        + "<Measures><MeasureRef name='Unit Sales' aggColumn='unit_sales_sum'/></Measures>"
        + "<DimensionLinks>"
        + "<ForeignKeyLink dimension='Store' foreignKeyColumn='store_id'/>"
        + "<CopyLink dimension='Time'/>"
        + "<FactLink dimension='Promotion'/>"
        + "</DimensionLinks>"
        + "</MeasureGroup>"
        + "</MeasureGroups>"
        + "</Cube></Schema>";

    @Test
    public void ingestsCube() {
        String yaml = M4XmlToYaml.toYaml(CUBE_XML);
        assertTrue(yaml, yaml.contains("cubes:"));
        assertTrue(yaml, yaml.contains("Sales:"));
        assertTrue(yaml, yaml.contains("default_measure: \"Unit Sales\"")
            || yaml.contains("default_measure: Unit Sales"));
        assertTrue(yaml, yaml.contains("dimensions:"));
        assertTrue(yaml, yaml.contains("source: \"Store\"")
            || yaml.contains("source: Store"));
        assertTrue(yaml, yaml.contains("measure_groups:"));
        assertTrue(yaml, yaml.contains("aggregator: \"sum\"")
            || yaml.contains("aggregator: sum"));
        assertTrue(yaml, yaml.contains("format_string"));
        assertTrue(yaml, yaml.contains("ref:"));
        assertTrue(yaml, yaml.contains("agg_column"));
        assertTrue(yaml, yaml.contains("type: \"aggregate\"")
            || yaml.contains("type: aggregate"));
        assertTrue(yaml, yaml.contains("foreign_key"));
        assertTrue(yaml, yaml.contains("no_link"));
    }

    @Test
    public void cubeRoundTripsThroughEmit() {
        String yaml = M4XmlToYaml.toYaml(CUBE_XML);
        String yaml2 = M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml));
        assertEquals(yaml, yaml2);
    }

    // ---- calc members + named sets ----

    private static final String CALC_XML =
        "<Schema name='FoodMart' metamodelVersion='4.0'>"
        + "<Cube name='Sales' defaultMeasure='Unit Sales'>"
        + "<MeasureGroups><MeasureGroup name='Sales' table='sales_fact_1997'>"
        + "<Measures><Measure name='Unit Sales' column='unit_sales' aggregator='sum'/></Measures>"
        + "</MeasureGroup></MeasureGroups>"
        + "<CalculatedMembers>"
        + "<CalculatedMember name='Profit' dimension='Measures'"
        + " formula='[Measures].[Store Sales] - [Measures].[Store Cost]'"
        + " formatString='$#,##0.00'>"
        + "<CalculatedMemberProperty name='SOLVE_ORDER' value='100'/>"
        + "</CalculatedMember>"
        + "</CalculatedMembers>"
        + "<NamedSets>"
        + "<NamedSet name='Top Sellers'"
        + " formula='TopCount([Product].[Product].Members, 10, [Measures].[Unit Sales])'/>"
        + "</NamedSets>"
        + "</Cube></Schema>";

    @Test
    public void ingestsCalcMembersAndNamedSets() {
        String yaml = M4XmlToYaml.toYaml(CALC_XML);
        assertTrue(yaml, yaml.contains("calculated_members:"));
        assertTrue(yaml, yaml.contains("Profit"));
        assertTrue(yaml, yaml.contains("dimension: \"Measures\"")
            || yaml.contains("dimension: Measures"));
        assertTrue(yaml, yaml.contains("formula:"));
        assertTrue(yaml, yaml.contains("Store Sales"));
        assertTrue(yaml, yaml.contains("format_string"));
        assertTrue(yaml, yaml.contains("properties:"));
        assertTrue(yaml, yaml.contains("SOLVE_ORDER"));
        assertTrue(yaml, yaml.contains("named_sets:"));
        assertTrue(yaml, yaml.contains("Top Sellers"));
    }

    @Test
    public void calcMembersAndNamedSetsRoundTrip() {
        String yaml = M4XmlToYaml.toYaml(CALC_XML);
        String yaml2 = M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml));
        assertEquals(yaml, yaml2);
    }

    @Test
    public void attributeMultiColNameAndCaptionsRoundTrip() {
        String xml =
            "<Schema name='FoodMart' metamodelVersion='4.0'>"
            + "<Dimension name='Customer' table='customer' key='Customer Id'>"
            + "<Attributes>"
            + "<Attribute name='Customer Id' keyColumn='customer_id' hasHierarchy='false'/>"
            + "<Attribute name='Name' captionColumn='fullname'"
            + " hierarchyAllMemberName='All Customers'"
            + " hierarchyDefaultMember='[Customer].[Name].[x]'>"
            + "<Key><Column name='cust_id'/></Key>"
            + "<Name><Column name='lname'/><Column name='fname'/></Name>"
            + "</Attribute>"
            + "</Attributes>"
            + "<Hierarchies><Hierarchy name='Customers'>"
            + "<Level attribute='Name'/></Hierarchy></Hierarchies>"
            + "</Dimension></Schema>";
        String yaml = M4XmlToYaml.toYaml(xml);
        // attribute name preserved, NOT overwritten by the multi-col Name
        assertTrue(yaml, yaml.contains("name: \"Name\"")
            || yaml.contains("name: Name"));
        assertTrue(yaml, yaml.contains("name_columns"));
        assertTrue(yaml, yaml.contains("lname"));
        assertTrue(yaml, yaml.contains("fname"));
        assertTrue(yaml, yaml.contains("caption_column"));
        assertTrue(yaml, yaml.contains("hierarchy_all_member_caption")
            || yaml.contains("hierarchy_all_member_name")); // (all_member_name present)
        assertTrue(yaml, yaml.contains("hierarchy_default_member"));
        // full round-trip stability
        String yaml2 = M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml));
        assertEquals(yaml, yaml2);
    }

    // ---- roles + annotations ----

    private static final String ROLE_ANN_XML =
        "<Schema name='FoodMart' metamodelVersion='4.0'>"
        + "<Annotations><Annotation name='caption.de_DE'>Verkaufen</Annotation></Annotations>"
        + "<Role name='California manager'>"
        + "<SchemaGrant access='none'>"
        + "<CubeGrant cube='Sales' access='all'>"
        + "<HierarchyGrant hierarchy='[Store].[Stores]' access='custom'"
        + " topLevel='[Store].[Stores].[Store Country]'>"
        + "<MemberGrant member='[Store].[Stores].[USA].[CA]' access='all'/>"
        + "</HierarchyGrant>"
        + "</CubeGrant>"
        + "</SchemaGrant>"
        + "</Role>"
        + "</Schema>";

    @Test
    public void ingestsRolesAndAnnotations() {
        String yaml = M4XmlToYaml.toYaml(ROLE_ANN_XML);
        assertTrue(yaml, yaml.contains("annotations:"));
        assertTrue(yaml, yaml.contains("caption.de_DE"));
        assertTrue(yaml, yaml.contains("Verkaufen"));
        assertTrue(yaml, yaml.contains("roles:"));
        assertTrue(yaml, yaml.contains("California manager"));
        assertTrue(yaml, yaml.contains("Sales"));
        assertTrue(yaml, yaml.contains("[Store].[Stores].[USA].[CA]"));
    }

    @Test
    public void rolesAndAnnotationsRoundTrip() {
        String yaml = M4XmlToYaml.toYaml(ROLE_ANN_XML);
        String yaml2 = M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml));
        assertEquals(yaml, yaml2);
    }

    @Test
    public void cubeAnnotationsRoundTrip() {
        String xml =
            "<Schema name='S' metamodelVersion='4.0'>"
            + "<Cube name='Sales' defaultMeasure='Unit Sales'>"
            + "<Annotations><Annotation name='caption.fr_FR'>Ventes</Annotation></Annotations>"
            + "<MeasureGroups><MeasureGroup name='g' table='t'>"
            + "<Measures><Measure name='Unit Sales' column='u' aggregator='sum'/></Measures>"
            + "</MeasureGroup></MeasureGroups>"
            + "</Cube></Schema>";
        String yaml = M4XmlToYaml.toYaml(xml);
        assertTrue(yaml, yaml.contains("annotations:"));
        assertTrue(yaml, yaml.contains("caption.fr_FR"));
        String yaml2 = M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml));
        assertEquals(yaml, yaml2);
    }

    @Test
    public void referenceLinkRoundTrips() {
        String xml =
            "<Schema name='S' metamodelVersion='4.0'>"
            + "<Cube name='HR' defaultMeasure='M'>"
            + "<MeasureGroups><MeasureGroup name='HR' table='salary'>"
            + "<Measures><Measure name='M' column='c' aggregator='sum'/></Measures>"
            + "<DimensionLinks>"
            + "<ForeignKeyLink dimension='Employee' foreignKeyColumn='employee_id'/>"
            + "<ReferenceLink dimension='Store' viaDimension='Employee'"
            + " viaAttribute='Store Id'/>"
            + "</DimensionLinks>"
            + "</MeasureGroup></MeasureGroups>"
            + "</Cube></Schema>";
        String yaml = M4XmlToYaml.toYaml(xml);
        assertTrue(yaml, yaml.contains("type: \"reference\"")
            || yaml.contains("type: reference"));
        assertTrue(yaml, yaml.contains("via_dimension"));
        assertTrue(yaml, yaml.contains("via_attribute"));
        assertTrue(yaml, yaml.contains("Store Id"));
        // round-trip: re-ingest emitted XML, must be stable + no bare NoLink
        String emittedXml = M4YamlToXml.toXml(yaml);
        assertTrue(emittedXml, emittedXml.contains("<ReferenceLink"));
        assertTrue("must not degrade to a dimensionless NoLink",
            !emittedXml.contains("<NoLink>")
            && !emittedXml.contains("<NoLink/>"));
        String yaml2 = M4XmlToYaml.toYaml(emittedXml);
        assertEquals(yaml, yaml2);
    }

    @Test
    public void calcMemberExpressionPropertyAndHierarchyParentRoundTrip() {
        String xml =
            "<Schema name='S' metamodelVersion='4.0'>"
            + "<Cube name='C' defaultMeasure='M'>"
            + "<MeasureGroups><MeasureGroup name='g' table='t'>"
            + "<Measures><Measure name='M' column='c' aggregator='sum'/></Measures>"
            + "</MeasureGroup></MeasureGroups>"
            + "<CalculatedMembers>"
            + "<CalculatedMember name='CM' hierarchy='[Measures]' parent='[Measures]'>"
            + "<Formula>1+1</Formula>"
            + "<CalculatedMemberProperty name='X' expression='[a].[b]'/>"
            + "</CalculatedMember></CalculatedMembers>"
            + "<NamedSets><NamedSet name='NS'><Formula>{[A]}</Formula></NamedSet></NamedSets>"
            + "</Cube></Schema>";
        String yaml = M4XmlToYaml.toYaml(xml);
        assertTrue(yaml, yaml.contains("hierarchy:"));
        assertTrue(yaml, yaml.contains("parent:"));
        assertTrue(yaml, yaml.contains("expression"));
        assertTrue(yaml, yaml.contains("1+1"));   // Formula child cdata read
        assertTrue(yaml, yaml.contains("{[A]}"));  // NamedSet Formula child cdata
        String yaml2 = M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml));
        assertEquals(yaml, yaml2);
    }
}
