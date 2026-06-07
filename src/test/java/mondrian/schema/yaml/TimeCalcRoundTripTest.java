/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml;

import mondrian.schema.yaml.m4.M4XmlToYaml;
import mondrian.schema.yaml.m4.M4YamlToXml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeCalcRoundTripTest {

    private static final String XML =
        "<Schema name='TC' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='f'/></PhysicalSchema>\n"
        + "  <Cube name='C'>\n"
        + "    <Dimensions/>\n"
        + "    <MeasureGroups><MeasureGroup name='M' table='f'>\n"
        + "      <Measures><Measure name='Revenue' column='rev'"
        + " aggregator='sum'/></Measures>\n"
        + "      <DimensionLinks/>\n"
        + "    </MeasureGroup></MeasureGroups>\n"
        + "    <TimeCalcs>\n"
        + "      <TimeCalc name='Revenue YoY' type='yoy' measure='Revenue'"
        + " timeDimension='Calendar' formatString='0.0%'/>\n"
        + "      <TimeCalc name='Revenue R3' type='rolling' measure='Revenue'"
        + " timeDimension='Calendar' window='3' function='avg'/>\n"
        + "    </TimeCalcs>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    @Test public void cliPairRoundTrips() {
        String yaml = XmlSchemaToYaml.toYaml(XML);
        assertTrue(yaml.contains("time_calcs:"), yaml);
        assertTrue(yaml.contains("type: \"yoy\""), yaml);
        assertTrue(yaml.contains("window: 3") || yaml.contains("window: \"3\""),
            yaml);
        String xml2 = YamlSchemaConverter.toXml(yaml);
        assertTrue(xml2.contains("<TimeCalc"), xml2);
        assertTrue(xml2.contains("type=\"yoy\""), xml2);
        assertTrue(xml2.contains("function=\"avg\""), xml2);
    }

    @Test public void m4PairRoundTrips() {
        String yaml = M4XmlToYaml.toYaml(XML);
        assertTrue(yaml.contains("time_calcs:"), yaml);
        assertTrue(yaml.contains("type: \"rolling\""), yaml);
        String xml2 = M4YamlToXml.toXml(yaml);
        assertTrue(xml2.contains("<TimeCalc"), xml2);
        assertTrue(xml2.contains("measure=\"Revenue\""), xml2);
    }
}
