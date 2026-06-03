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

import mondrian.olap.Connection;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.schema.yaml.XmlSchemaToYaml;
import mondrian.schema.yaml.YamlSchemaConverter;
import mondrian.test.TestContext;
import mondrian.test.calcite.corpus.SmokeCorpus;
import mondrian.test.calcite.corpus.SmokeCorpus.NamedMdx;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 5 capstone: proves semantic equivalence of the committed
 * {@code demo/FoodMart.yaml} against the original
 * {@code demo/FoodMart.mondrian.xml} across the full SmokeCorpus.
 *
 * <p>The test loads the original XML and the committed YAML (converted
 * to XML via {@link YamlSchemaConverter#toXml(String)}) into two
 * {@link TestContext} instances and runs every query in
 * {@link SmokeCorpus#queries()} against both. For each query:
 * <ul>
 *   <li>if the XML baseline throws, skip (not a YAML regression);</li>
 *   <li>else run against the YAML context;</li>
 *   <li>compare cell arrays and count divergent queries.</li>
 * </ul>
 *
 * <p>Asserts 0 divergent queries.
 *
 * <p>Also contains a drift guard test ({@link #committedYamlMatchesRegenerated})
 * that ensures {@code demo/FoodMart.yaml} stays in sync with
 * {@code demo/FoodMart.mondrian.xml}.
 */
public class FoodMartYamlSmokeCorpusEquivalenceTest {

    private static final Path XML_FIXTURE = Paths.get("demo/FoodMart.mondrian.xml");
    private static final Path YAML_FIXTURE = Paths.get("demo/FoodMart.yaml");

    @Test
    public void allSmokeCorpusQueriesMatchAcrossYamlAndXml()
        throws Exception
    {
        assertTrue("XML fixture missing: " + XML_FIXTURE.toAbsolutePath(),
            Files.exists(XML_FIXTURE));
        assertTrue("YAML fixture missing: " + YAML_FIXTURE.toAbsolutePath(),
            Files.exists(YAML_FIXTURE));

        String originalXml =
            Files.readString(XML_FIXTURE, StandardCharsets.UTF_8);
        String committedYaml =
            Files.readString(YAML_FIXTURE, StandardCharsets.UTF_8);

        // Convert committed YAML to XML for loading into TestContext.
        // YamlSchemaConverter.toXml() auto-detects M4 YAML (metamodel_version)
        // and delegates to M4YamlToXml, producing the XML that Mondrian loads.
        String yamlDerivedXml = YamlSchemaConverter.toXml(committedYaml);

        TestContext ctxXml = TestContext.instance().withSchema(originalXml);
        TestContext ctxYaml = TestContext.instance().withSchema(yamlDerivedXml);

        List<NamedMdx> corpus = SmokeCorpus.queries();
        assertTrue("SmokeCorpus must have queries", !corpus.isEmpty());

        int ran = 0;
        int skipped = 0;
        int divergent = 0;
        StringBuilder diagnostics = new StringBuilder();

        for (NamedMdx q : corpus) {
            String[] xmlCells;
            try {
                xmlCells = runMdx(ctxXml, q.mdx);
            } catch (Throwable t) {
                // XML baseline failed — not a YAML regression; skip silently.
                skipped++;
                continue;
            }
            ran++;

            String[] yamlCells;
            try {
                yamlCells = runMdx(ctxYaml, q.mdx);
            } catch (Throwable t) {
                divergent++;
                diagnostics.append("\n[FAIL] ").append(q.name)
                    .append(": YAML context failed to run query: ")
                    .append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage());
                continue;
            }

            if (xmlCells.length != yamlCells.length) {
                divergent++;
                diagnostics.append("\n[FAIL] ").append(q.name)
                    .append(": row count diverged (xml=")
                    .append(xmlCells.length)
                    .append(", yaml=").append(yamlCells.length).append(")");
                continue;
            }

            boolean cellDivergence = false;
            for (int i = 0; i < xmlCells.length; i++) {
                if (!xmlCells[i].equals(yamlCells[i])) {
                    diagnostics.append("\n[FAIL] ").append(q.name)
                        .append(" cell[").append(i).append("]")
                        .append(" xml='").append(xmlCells[i]).append("'")
                        .append(" yaml='").append(yamlCells[i]).append("'");
                    cellDivergence = true;
                    break;
                }
            }
            if (cellDivergence) {
                divergent++;
            }
        }

        System.out.printf(
            "[SmokeCorpus M4] ran=%d skipped=%d divergent=%d%n",
            ran, skipped, divergent);

        assertEquals(
            "expected 0 divergent queries across M4 FoodMart smoke corpus"
                + " (ran=" + ran + " skipped=" + skipped + "),"
                + " got " + divergent + " divergence(s):" + diagnostics,
            0, divergent);
    }

    /**
     * Drift guard: regenerate YAML from {@code demo/FoodMart.mondrian.xml}
     * and assert it equals the committed {@code demo/FoodMart.yaml}.
     *
     * <p>If this test fails, run
     * {@code ./scripts/mondrian-schema to-yaml demo/FoodMart.mondrian.xml
     * -o demo/FoodMart.yaml} and re-commit.
     */
    @Test
    public void committedYamlMatchesRegenerated() throws Exception {
        assertTrue("XML fixture missing: " + XML_FIXTURE.toAbsolutePath(),
            Files.exists(XML_FIXTURE));
        assertTrue("YAML fixture missing: " + YAML_FIXTURE.toAbsolutePath(),
            Files.exists(YAML_FIXTURE));

        String originalXml =
            Files.readString(XML_FIXTURE, StandardCharsets.UTF_8);
        String committedYaml =
            Files.readString(YAML_FIXTURE, StandardCharsets.UTF_8);

        String regenerated = XmlSchemaToYaml.toYaml(originalXml);

        // Normalize trailing newlines before comparing so OS differences
        // in the file do not cause spurious failures.
        assertEquals(
            "demo/FoodMart.yaml has drifted from demo/FoodMart.mondrian.xml."
                + " Re-run: ./scripts/mondrian-schema to-yaml"
                + " demo/FoodMart.mondrian.xml -o demo/FoodMart.yaml",
            regenerated.stripTrailing(),
            committedYaml.stripTrailing());
    }

    private static String[] runMdx(TestContext ctx, String mdx) {
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(mdx);
            Result result = conn.execute(q);
            try {
                int rowCount = result.getAxes().length >= 2
                    ? result.getAxes()[1].getPositions().size() : 1;
                int colCount = result.getAxes().length >= 1
                    ? result.getAxes()[0].getPositions().size() : 1;
                String[] cells = new String[rowCount * colCount];
                int k = 0;
                for (int r = 0; r < rowCount; r++) {
                    for (int c = 0; c < colCount; c++) {
                        Object value = result.getCell(
                                result.getAxes().length >= 2
                                    ? new int[]{c, r}
                                    : new int[]{c})
                            .getFormattedValue();
                        cells[k++] = value == null ? "" : value.toString();
                    }
                }
                return cells;
            } finally {
                result.close();
            }
        } finally {
            conn.close();
        }
    }
}

// End FoodMartYamlSmokeCorpusEquivalenceTest.java
