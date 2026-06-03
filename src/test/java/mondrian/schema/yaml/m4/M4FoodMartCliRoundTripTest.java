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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/**
 * #34 M4: the full demo/FoodMart.mondrian.xml round-trips through the
 * path-based CLI converter (XmlSchemaToYaml.toYaml then
 * YamlSchemaConverter.toXmlFromPath), exercising the M4 dispatch on the
 * toXmlFromPath code path used by SchemaCli to-xml / lint.
 */
public class M4FoodMartCliRoundTripTest {

    @Test
    public void foodMartYamlConvertsBackToXmlViaPathApi() throws Exception {
        Path xmlFixture = Paths.get("demo/FoodMart.mondrian.xml");
        assertTrue("fixture missing", Files.exists(xmlFixture));
        String xml = Files.readString(xmlFixture, StandardCharsets.UTF_8);
        String yaml = XmlSchemaToYaml.toYaml(xml);

        Path tmp = Files.createTempFile("foodmart-m4-", ".yaml");
        try {
            Files.write(tmp, yaml.getBytes(StandardCharsets.UTF_8));
            String backToXml = YamlSchemaConverter.toXmlFromPath(tmp);
            assertTrue("expected M4 <Schema>", backToXml.contains("<Schema"));
            assertTrue("expected PhysicalSchema",
                backToXml.contains("<PhysicalSchema"));
            assertTrue("expected a Cube", backToXml.contains("<Cube"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
