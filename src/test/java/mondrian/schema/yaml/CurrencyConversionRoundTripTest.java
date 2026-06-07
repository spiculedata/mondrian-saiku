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

public class CurrencyConversionRoundTripTest {

    private static final String XML =
        "<Schema name='CC' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='f'/><Table name='fx'/></PhysicalSchema>\n"
        + "  <Cube name='C'>\n    <Dimensions/>\n"
        + "    <MeasureGroups><MeasureGroup name='M' table='f'>\n"
        + "      <Measures><Measure name='Amount' column='amt'"
        + " aggregator='sum'/></Measures>\n"
        + "      <CurrencyConversions>\n"
        + "        <CurrencyConversion name='Amount (USD)' measure='Amount'\n"
        + "          rateTable='fx' rateColumn='rate' rateType='ECB'"
        + " rateTypeColumn='rate_type'\n"
        + "          factCurrencyColumn='ccy' rateCurrencyColumn='ccy'\n"
        + "          factDateColumn='dt' rateValidFromColumn='vf'"
        + " rateValidToColumn='vt'/>\n"
        + "      </CurrencyConversions>\n"
        + "      <DimensionLinks/>\n"
        + "    </MeasureGroup></MeasureGroups>\n  </Cube>\n</Schema>\n";

    @Test public void roundTrips() {
        String yaml = M4XmlToYaml.toYaml(XML);
        assertTrue(yaml.contains("currency_conversions:"), yaml);
        assertTrue(yaml.contains("rate_table: \"fx\""), yaml);
        assertTrue(yaml.contains("rate_valid_from_column: \"vf\""), yaml);
        String xml2 = M4YamlToXml.toXml(yaml);
        assertTrue(xml2.contains("<CurrencyConversion"), xml2);
        assertTrue(xml2.contains("rateValidFromColumn=\"vf\""), xml2);
        assertTrue(xml2.contains("rateType=\"ECB\""), xml2);
    }
}
