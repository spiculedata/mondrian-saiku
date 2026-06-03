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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 1 Session 8: regression test that round-trips the
 * canonical {@code demo/FoodMart3.mondrian.xml} fixture (754 lines,
 * legacy Mondrian-3 format) through {@link XmlSchemaToYaml} then back
 * through {@link YamlSchemaConverter}.
 *
 * <p>Plus an assertion that {@link SchemaCli#run} accepts both the
 * intermediate YAML and the round-tripped XML — this catches the
 * regression where lint was over-strict and rejected legacy schemas
 * (e.g. {@code type="TimeDimension"} which the modern XSD enum
 * doesn't list).
 */
public class FoodMart3RoundtripTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void foodMart3RoundTripsCleanly() throws Exception {
        Path fixture = Paths.get("demo/FoodMart3.mondrian.xml");
        assertTrue("fixture missing: " + fixture.toAbsolutePath(),
            Files.exists(fixture));
        String originalXml = Files.readString(fixture, StandardCharsets.UTF_8);

        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        assertTrue("yaml output suspiciously small (< 5kB): "
                + yaml.length(),
            yaml.length() > 5000);
        assertTrue("yaml output missing schema:", yaml.contains("schema:"));
        assertTrue("yaml output missing cubes:", yaml.contains("cubes:"));
        assertTrue("yaml output missing shared_dimensions:",
            yaml.contains("shared_dimensions:"));

        String roundTripped = YamlSchemaConverter.toXml(yaml);
        assertTrue("round-tripped XML suspiciously small (< 5kB): "
                + roundTripped.length(),
            roundTripped.length() > 5000);
        assertTrue("round-tripped XML missing schema:",
            roundTripped.contains("<Schema name=\"FoodMart\""));
        // The Sales cube is the canonical FoodMart hello-world.
        assertTrue("round-tripped XML missing Sales cube:",
            roundTripped.contains("<Cube name=\"Sales\""));
        assertTrue("round-tripped XML missing Warehouse cube:",
            roundTripped.contains("<Cube name=\"Warehouse\""));
    }

    @Test
    public void schemaCliLintAcceptsLegacyFoodMart3() throws Exception {
        // The acid test for the lint over-strictness fix: lint must
        // accept FoodMart3.mondrian.xml even though it's Mondrian-3
        // legacy and uses enum values (TimeDimension etc.) that the
        // modern MondrianDef.Schema constructor rejects.
        Path fixture = Paths.get("demo/FoodMart3.mondrian.xml");
        assertTrue("fixture missing", Files.exists(fixture));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = SchemaCli.run(
            new String[] { "lint", fixture.toString() },
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(
            "rc != 0 for legacy schema lint; stderr="
                + err.toString(StandardCharsets.UTF_8),
            0, rc);
    }

    @Test
    public void schemaCliLintAcceptsRoundTrippedFoodMart3() throws Exception {
        Path fixture = Paths.get("demo/FoodMart3.mondrian.xml");
        String originalXml = Files.readString(fixture, StandardCharsets.UTF_8);
        String yaml = XmlSchemaToYaml.toYaml(originalXml);

        Path yamlPath = tmp.newFile("foodmart3.yaml").toPath();
        Files.writeString(yamlPath, yaml, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = SchemaCli.run(
            new String[] { "lint", yamlPath.toString() },
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(
            "lint should accept round-tripped FoodMart3 YAML; stderr="
                + err.toString(StandardCharsets.UTF_8),
            0, rc);
    }
}

// End FoodMart3RoundtripTest.java
