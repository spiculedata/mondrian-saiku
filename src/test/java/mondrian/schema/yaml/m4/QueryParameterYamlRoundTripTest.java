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
 * #105 (TDD #5): a top-level {@code <QueryParameter>} block survives the
 * {@code XML → YAML → XML} round-trip with its type, default, and closed
 * allowed-value enumeration intact, in both directions.
 */
public class QueryParameterYamlRoundTripTest {

    private static final String XML =
        "<Schema name='P' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='store'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='region' type='String'"
        + " defaultValue='EAST'>\n"
        + "    <QueryParameterValue>EAST</QueryParameterValue>\n"
        + "    <QueryParameterValue>WEST</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "</Schema>\n";

    @Test
    public void yamlEmitsParameterBlock() {
        String yaml = M4XmlToYaml.toYaml(XML);
        assertTrue(yaml.contains("parameters"), "yaml has parameters block: " + yaml);
        assertTrue(yaml.contains("region"), "yaml has name: " + yaml);
        assertTrue(yaml.contains("EAST"), "yaml has default: " + yaml);
        assertTrue(yaml.contains("WEST"), "yaml has allowed WEST: " + yaml);
    }

    @Test
    public void xmlYamlXmlPreservesParameter() {
        String roundTripped = M4YamlToXml.toXml(M4XmlToYaml.toYaml(XML));
        assertTrue(roundTripped.contains("QueryParameter"), "round-trip keeps QueryParameter: " + roundTripped);
        assertTrue(roundTripped.contains("region"), "round-trip keeps name region: " + roundTripped);
        assertTrue(roundTripped.contains("EAST"), "round-trip keeps defaultValue EAST: " + roundTripped);
        assertTrue(roundTripped.contains("WEST"), "round-trip keeps allowed value WEST: " + roundTripped);
    }
}
