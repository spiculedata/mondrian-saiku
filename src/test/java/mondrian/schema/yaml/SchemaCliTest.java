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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 1 Session 5: end-user tool that wraps the YAML ↔ XML
 * converters with three subcommands:
 *
 * <ul>
 *   <li>{@code to-yaml <input.xml>} — emit YAML to stdout (or
 *       {@code -o file}).</li>
 *   <li>{@code to-xml <input.yaml>} — emit XML to stdout (or
 *       {@code -o file}).</li>
 *   <li>{@code lint <input.{yaml,xml}>} — parse, forward-convert if
 *       YAML, structurally validate via {@code MondrianDef.Schema}'s
 *       XOM constructor; exit non-zero with diagnostic on failure.</li>
 * </ul>
 *
 * <p>Exit codes: 0 = success, 1 = bad args, 2 = lint failure / parse
 * error. Tests invoke {@code SchemaCli.run(argv, stdout, stderr)} to
 * capture streams in-process without forking.
 */
public class SchemaCliTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream outP;
    private PrintStream errP;

    @Before
    public void setUp() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        outP = new PrintStream(out, true, StandardCharsets.UTF_8);
        errP = new PrintStream(err, true, StandardCharsets.UTF_8);
    }

    @After
    public void tearDown() {
        outP.close();
        errP.close();
    }

    @Test
    public void noArgsPrintsUsageAndExitsNonZero() {
        int rc = SchemaCli.run(new String[0], outP, errP);
        assertNotEquals("expected non-zero exit for no args", 0, rc);
        assertTrue("usage missing from stderr:\n" + err,
            err.toString(StandardCharsets.UTF_8).contains("usage:")
                || err.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    public void toYamlConvertsXmlFileToYamlOnStdout() throws Exception {
        Path xmlFile = tmp.newFile("in.xml").toPath();
        Files.writeString(xmlFile, MINIMAL_XML, StandardCharsets.UTF_8);

        int rc = SchemaCli.run(
            new String[] { "to-yaml", xmlFile.toString() }, outP, errP);

        assertEquals(
            "rc != 0; stderr=" + err.toString(StandardCharsets.UTF_8),
            0, rc);
        String yaml = out.toString(StandardCharsets.UTF_8);
        assertTrue("missing schema key:\n" + yaml,
            yaml.contains("schema:"));
        assertTrue("missing cubes key:\n" + yaml,
            yaml.contains("cubes:"));
    }

    @Test
    public void toYamlWritesToOutputFileWhenDashOSpecified()
        throws Exception
    {
        Path xmlFile = tmp.newFile("in.xml").toPath();
        Files.writeString(xmlFile, MINIMAL_XML, StandardCharsets.UTF_8);
        Path yamlFile = tmp.getRoot().toPath().resolve("out.yaml");

        int rc = SchemaCli.run(
            new String[] {
                "to-yaml", xmlFile.toString(), "-o", yamlFile.toString()
            }, outP, errP);

        assertEquals(
            "rc != 0; stderr=" + err.toString(StandardCharsets.UTF_8),
            0, rc);
        assertTrue("stdout should be empty when -o used:\n"
                + out.toString(StandardCharsets.UTF_8),
            out.size() == 0);
        String written = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue("file missing schema key:\n" + written,
            written.contains("schema:"));
    }

    @Test
    public void toXmlConvertsYamlFileToXmlOnStdout() throws Exception {
        Path yamlFile = tmp.newFile("in.yaml").toPath();
        Files.writeString(yamlFile, MINIMAL_YAML, StandardCharsets.UTF_8);

        int rc = SchemaCli.run(
            new String[] { "to-xml", yamlFile.toString() }, outP, errP);

        assertEquals(
            "rc != 0; stderr=" + err.toString(StandardCharsets.UTF_8),
            0, rc);
        String xml = out.toString(StandardCharsets.UTF_8);
        assertTrue("missing <Schema>:\n" + xml,
            xml.contains("<Schema name="));
        assertTrue("missing <Cube>:\n" + xml,
            xml.contains("<Cube name="));
    }

    @Test
    public void lintAcceptsWellFormedYaml() throws Exception {
        Path yamlFile = tmp.newFile("ok.yaml").toPath();
        Files.writeString(yamlFile, MINIMAL_YAML, StandardCharsets.UTF_8);

        int rc = SchemaCli.run(
            new String[] { "lint", yamlFile.toString() }, outP, errP);

        assertEquals(
            "rc != 0; stderr=" + err.toString(StandardCharsets.UTF_8),
            0, rc);
        assertTrue("expected OK line on stdout:\n"
                + out.toString(StandardCharsets.UTF_8),
            out.toString(StandardCharsets.UTF_8)
                .toLowerCase().contains("ok"));
    }

    @Test
    public void lintAcceptsWellFormedXml() throws Exception {
        Path xmlFile = tmp.newFile("ok.xml").toPath();
        Files.writeString(xmlFile, MINIMAL_XML, StandardCharsets.UTF_8);

        int rc = SchemaCli.run(
            new String[] { "lint", xmlFile.toString() }, outP, errP);

        assertEquals(
            "rc != 0; stderr=" + err.toString(StandardCharsets.UTF_8),
            0, rc);
    }

    @Test
    public void lintRejectsMalformedYaml() throws Exception {
        Path yamlFile = tmp.newFile("bad.yaml").toPath();
        // Missing required schema: key.
        Files.writeString(
            yamlFile,
            "cubes:\n  Sales:\n    fact_table: x\n",
            StandardCharsets.UTF_8);

        int rc = SchemaCli.run(
            new String[] { "lint", yamlFile.toString() }, outP, errP);

        assertNotEquals(
            "expected non-zero rc for malformed YAML; stdout="
                + out.toString(StandardCharsets.UTF_8)
                + ", stderr=" + err.toString(StandardCharsets.UTF_8),
            0, rc);
        assertTrue("expected error diagnostic on stderr:\n"
                + err.toString(StandardCharsets.UTF_8),
            !err.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    public void unknownSubcommandPrintsUsageAndExitsNonZero() {
        int rc = SchemaCli.run(
            new String[] { "bogus" }, outP, errP);
        assertNotEquals(0, rc);
        assertTrue("usage missing from stderr:\n" + err,
            err.toString(StandardCharsets.UTF_8)
                .toLowerCase().contains("usage"));
    }

    @Test
    public void missingInputFilePrintsErrorAndExitsNonZero() {
        int rc = SchemaCli.run(
            new String[] { "to-yaml", "/nonexistent/path/x.xml" },
            outP, errP);
        assertNotEquals(0, rc);
        assertTrue("expected error message:\n" + err,
            !err.toString(StandardCharsets.UTF_8).isBlank());
    }

    private static final String MINIMAL_XML =
        "<?xml version=\"1.0\"?>\n"
        + "<Schema name=\"Lint\">\n"
        + "  <Cube name=\"UnitSalesByYear\">\n"
        + "    <Table name=\"sales_fact_1997\"/>\n"
        + "    <Dimension name=\"Time\" foreignKey=\"time_id\">\n"
        + "      <Hierarchy hasAll=\"true\" primaryKey=\"time_id\">\n"
        + "        <Table name=\"time_by_day\"/>\n"
        + "        <Level name=\"Year\" column=\"the_year\""
        +              " type=\"Numeric\" uniqueMembers=\"true\"/>\n"
        + "      </Hierarchy>\n"
        + "    </Dimension>\n"
        + "    <Measure name=\"Unit Sales\" column=\"unit_sales\""
        +            " aggregator=\"sum\"/>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    private static final String MINIMAL_YAML =
        "schema: Lint\n"
        + "cubes:\n"
        + "  UnitSalesByYear:\n"
        + "    fact_table: sales_fact_1997\n"
        + "    dimensions:\n"
        + "      - name: Time\n"
        + "        foreign_key: time_id\n"
        + "        hierarchy:\n"
        + "          has_all: true\n"
        + "          primary_key: time_id\n"
        + "          table: time_by_day\n"
        + "          levels:\n"
        + "            - name: Year\n"
        + "              column: the_year\n"
        + "              type: Numeric\n"
        + "              unique_members: true\n"
        + "    measures:\n"
        + "      - name: Unit Sales\n"
        + "        column: unit_sales\n"
        + "        aggregator: sum\n";
}

// End SchemaCliTest.java
