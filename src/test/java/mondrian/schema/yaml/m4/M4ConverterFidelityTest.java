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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Converter-fidelity regression tests for the M4 YAML↔XML round-trip:
 * issues #109 (role-play dimension name), #110 (display attributes), and
 * #111 (Query SQL-backed physical tables). Each is the minimal repro from
 * its issue, asserting the dropped construct now survives XML → YAML → XML.
 */
public class M4ConverterFidelityTest {

    // ---- #109: role-play `name` on <Dimension source=...> ----

    private static final String ROLEPLAY_XML =
        "<Schema name='RolePlay' metamodelVersion='4.0'>"
        + "<PhysicalSchema>"
        + "<Table name='sales'><Key><Column name='id'/></Key></Table>"
        + "<Table name='date_dim'/>"
        + "</PhysicalSchema>"
        + "<Dimension name='Date' table='date_dim' key='D'>"
        + "<Attributes><Attribute name='D' column='date_id'/></Attributes>"
        + "</Dimension>"
        + "<Cube name='Sales'>"
        + "<Dimensions>"
        + "<Dimension source='Date' name='Order Date'/>"
        + "<Dimension source='Date' name='Ship Date'/>"
        + "</Dimensions>"
        + "<MeasureGroups><MeasureGroup name='Sales' table='sales'>"
        + "<Measures>"
        + "<Measure name='Amt' column='amt' aggregator='sum'/></Measures>"
        + "<DimensionLinks>"
        + "<ForeignKeyLink dimension='Order Date'"
        + " foreignKeyColumn='order_date_id'/>"
        + "<ForeignKeyLink dimension='Ship Date'"
        + " foreignKeyColumn='ship_date_id'/>"
        + "</DimensionLinks>"
        + "</MeasureGroup></MeasureGroups>"
        + "</Cube></Schema>";

    @Test
    public void rolePlayDimensionNameSurvives() {
        String yaml = M4XmlToYaml.toYaml(ROLEPLAY_XML);
        assertTrue(yaml, yaml.contains("Order Date"));
        assertTrue(yaml, yaml.contains("Ship Date"));
        String back = M4YamlToXml.toXml(yaml);
        // Two distinct role-played usages, each keeping its name.
        assertTrue(back, back.contains("name=\"Order Date\""));
        assertTrue(back, back.contains("name=\"Ship Date\""));
        assertEquals(yaml, M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml)));
    }

    // ---- #110: caption / description / visible / measuresCaption ----

    private static final String DISPLAY_XML =
        "<Schema name='Probe' metamodelVersion='4.0' caption='Schema Caption'"
        + " description='Schema Desc' measuresCaption='MeasuresCap'"
        + " missingLink='ignore'>"
        + "<PhysicalSchema>"
        + "<Table name='f'><Key><Column name='id'/></Key></Table>"
        + "<Table name='d'/>"
        + "</PhysicalSchema>"
        + "<Dimension name='Dim' table='d' key='K' caption='Dim Caption'"
        + " description='Dim Desc' visible='false'>"
        + "<Attributes>"
        + "<Attribute name='K' column='k' caption='Attr Caption'"
        + " description='Attr Desc'/>"
        + "</Attributes>"
        + "</Dimension>"
        + "<Cube name='C' caption='Cube Caption' description='Cube Desc'"
        + " visible='false'>"
        + "<Dimensions><Dimension source='Dim'/></Dimensions>"
        + "<MeasureGroups><MeasureGroup name='C' table='f'>"
        + "<Measures>"
        + "<Measure name='M' column='m' aggregator='sum'"
        + " caption='Meas Caption' description='Meas Desc' visible='false'/>"
        + "</Measures>"
        + "<DimensionLinks>"
        + "<ForeignKeyLink dimension='Dim' foreignKeyColumn='dk'/>"
        + "</DimensionLinks>"
        + "</MeasureGroup></MeasureGroups>"
        + "</Cube></Schema>";

    @Test
    public void displayAttributesSurvive() {
        String yaml = M4XmlToYaml.toYaml(DISPLAY_XML);
        String back = M4YamlToXml.toXml(yaml);
        // Schema-level
        assertTrue(back, back.contains("caption=\"Schema Caption\""));
        assertTrue(back, back.contains("description=\"Schema Desc\""));
        assertTrue(back, back.contains("measuresCaption=\"MeasuresCap\""));
        assertTrue(back, back.contains("missingLink=\"ignore\""));
        // Cube
        assertTrue(back, back.contains("caption=\"Cube Caption\""));
        assertTrue(back, back.contains("description=\"Cube Desc\""));
        // Dimension
        assertTrue(back, back.contains("caption=\"Dim Caption\""));
        assertTrue(back, back.contains("visible=\"false\""));
        // Attribute
        assertTrue(back, back.contains("caption=\"Attr Caption\""));
        // Measure
        assertTrue(back, back.contains("caption=\"Meas Caption\""));
        assertTrue(back, back.contains("description=\"Meas Desc\""));
        assertEquals(yaml, M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml)));
    }

    // ---- #111: <Query> SQL-backed physical table ----

    private static final String QUERY_XML =
        "<Schema name='QueryTbl' metamodelVersion='4.0'>"
        + "<PhysicalSchema>"
        + "<Table name='sales'><Key><Column name='id'/></Key></Table>"
        + "<Query alias='cust_geo' keyColumn='CustomerKey'>"
        + "<ExpressionView>"
        + "<SQL dialect='generic'>select c.CustomerKey, g.City from"
        + " dim_customer c join dim_geo g on"
        + " c.GeographyKey = g.GeographyKey</SQL>"
        + "</ExpressionView>"
        + "</Query>"
        + "</PhysicalSchema>"
        + "<Dimension name='Customer Geography' table='cust_geo'"
        + " key='CustomerKey'>"
        + "<Attributes>"
        + "<Attribute name='CustomerKey' column='CustomerKey'/></Attributes>"
        + "</Dimension>"
        + "<Cube name='Sales'>"
        + "<Dimensions><Dimension source='Customer Geography'/></Dimensions>"
        + "<MeasureGroups><MeasureGroup name='Sales' table='sales'>"
        + "<Measures>"
        + "<Measure name='Amt' column='amt' aggregator='sum'/></Measures>"
        + "<DimensionLinks>"
        + "<ForeignKeyLink dimension='Customer Geography'"
        + " foreignKeyColumn='ck'/>"
        + "</DimensionLinks>"
        + "</MeasureGroup></MeasureGroups>"
        + "</Cube></Schema>";

    @Test
    public void queryTableSurvives() {
        String yaml = M4XmlToYaml.toYaml(QUERY_XML);
        assertTrue(yaml, yaml.contains("cust_geo"));
        assertTrue(yaml.toLowerCase(), yaml.toLowerCase().contains("select"));
        String back = M4YamlToXml.toXml(yaml);
        assertTrue(back, back.contains("<Query"));
        assertTrue(back, back.contains("cust_geo"));
        assertTrue(back, back.contains("CustomerKey"));
        assertTrue(back.toLowerCase(),
            back.toLowerCase().contains("select c.customerkey"));
        assertEquals(yaml, M4XmlToYaml.toYaml(M4YamlToXml.toXml(yaml)));
    }
}
