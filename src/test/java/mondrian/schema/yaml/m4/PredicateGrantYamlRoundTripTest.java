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
 * #106 (TDD #7): a {@code <PredicateGrant>} inside a cube-grant block survives
 * the {@code XML → YAML → XML} round-trip with its measureGroup, column,
 * operator, and bound parameter intact, in both directions.
 */
public class PredicateGrantYamlRoundTripTest {

    private static final String XML =
        "<Schema name='P' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='pg_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='tenant' type='Numeric'/>\n"
        + "  <Cube name='Sales'>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='pg_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "  <Role name='Tenant'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <PredicateGrant measureGroup='S' column='tenant'"
        + " operator='in' parameter='tenant'/>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    @Test
    public void yamlEmitsPredicateGrantBlock() {
        String yaml = M4XmlToYaml.toYaml(XML);
        assertTrue(yaml.contains("predicate_grants"),
            "yaml has predicate_grants block: " + yaml);
        assertTrue(yaml.contains("measure_group"),
            "yaml has measure_group: " + yaml);
        assertTrue(yaml.contains("tenant"), "yaml has column/param: " + yaml);
        assertTrue(yaml.contains("in"), "yaml has operator: " + yaml);
    }

    @Test
    public void xmlYamlXmlPreservesPredicateGrant() {
        String rt = M4YamlToXml.toXml(M4XmlToYaml.toYaml(XML));
        assertTrue(rt.contains("PredicateGrant"),
            "round-trip keeps PredicateGrant: " + rt);
        assertTrue(rt.contains("measureGroup=\"S\"")
                || rt.contains("measureGroup='S'"),
            "round-trip keeps measureGroup: " + rt);
        assertTrue(rt.contains("column=\"tenant\"")
                || rt.contains("column='tenant'"),
            "round-trip keeps column: " + rt);
        assertTrue(rt.contains("operator=\"in\"")
                || rt.contains("operator='in'"),
            "round-trip keeps operator: " + rt);
        assertTrue(rt.contains("parameter=\"tenant\"")
                || rt.contains("parameter='tenant'"),
            "round-trip keeps parameter: " + rt);
    }
}
