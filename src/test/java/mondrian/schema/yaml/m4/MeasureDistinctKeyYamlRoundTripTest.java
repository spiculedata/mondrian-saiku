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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #119: a {@code <Measure>}'s {@code distinctKeyColumn} attribute (the
 * measure-level distinct grain — sum_distinct / average_distinct without a
 * bridge) survives the {@code XML → YAML → XML} round-trip in both directions.
 */
public class MeasureDistinctKeyYamlRoundTripTest {

    private static final String XML =
        "<Schema name='D' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='sale_line'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions/>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='sale_line'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Distinct Amount' column='amount'"
        + " aggregator='sum' distinctKeyColumn='sale_id'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks/>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    @Test
    public void yamlEmitsDistinctKeyColumn() {
        String yaml = M4XmlToYaml.toYaml(XML);
        assertTrue(yaml.contains("distinct_key_column"),
            "yaml has distinct_key_column key: " + yaml);
        assertTrue(yaml.contains("sale_id"),
            "yaml has the key column value: " + yaml);
    }

    @Test
    public void xmlYamlXmlPreservesDistinctKeyColumn() {
        String roundTripped = M4YamlToXml.toXml(M4XmlToYaml.toYaml(XML));
        assertTrue(roundTripped.contains("distinctKeyColumn"),
            "round-trip keeps distinctKeyColumn attribute: " + roundTripped);
        assertTrue(roundTripped.contains("sale_id"),
            "round-trip keeps the key column value: " + roundTripped);
    }
}
