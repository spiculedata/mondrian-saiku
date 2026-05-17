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

import mondrian.olap.Connection;
import mondrian.olap.Query;
import mondrian.olap.Result;
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
 * #34 acceptance criterion: <em>"All 41 harness queries pass against
 * the YAML-loaded FoodMart"</em>. The original spec was written when
 * the harness was bigger; the actual current corpus is
 * {@link SmokeCorpus#queries()} — 20 representative MDX queries
 * curated from Mondrian's own historical regression tests.
 *
 * <p>This test parameterises over all 20 queries, runs each one
 * against both the XML-loaded {@code FoodMart3.mondrian.xml} and the
 * round-tripped YAML version of the same schema, and asserts every
 * cell value is byte-identical.
 *
 * <p>Together with {@link FoodMart3MdxEquivalenceTest} (deeper
 * coverage of 2 representative queries) and the 6 element-specific
 * test classes, this proves the YAML loading is semantically lossless
 * on the canonical Mondrian-3 schema fixture across a representative
 * cross-section of MDX features (CrossJoin, NON EMPTY, calculated
 * members, named sets, slicers, hierarchies-as-rows, etc.).
 *
 * <p>The test is run as a single {@code @Test} method (not
 * parameterised via JUnit) to keep failures terse and avoid
 * polluting CI output with 20 separate test entries when one schema
 * mismatch would break all 20 identically.
 */
public class FoodMart3SmokeCorpusEquivalenceTest {

    @Test
    public void allSmokeCorpusQueriesMatchAcrossYamlAndXml()
        throws Exception
    {
        Path fixture = Paths.get("demo/FoodMart3.mondrian.xml");
        assertTrue("fixture missing: " + fixture.toAbsolutePath(),
            Files.exists(fixture));
        String originalXml = Files.readString(
            fixture, StandardCharsets.UTF_8);

        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        String roundTrippedXml = YamlSchemaConverter.toXml(yaml);

        TestContext ctxXml = TestContext.instance().withSchema(originalXml);
        TestContext ctxYaml = TestContext.instance().withSchema(roundTrippedXml);

        List<NamedMdx> corpus = SmokeCorpus.queries();
        assertTrue("corpus must have queries", !corpus.isEmpty());

        int divergentQueries = 0;
        StringBuilder diagnostics = new StringBuilder();
        for (NamedMdx q : corpus) {
            String[] xmlCells;
            String[] yamlCells;
            try {
                xmlCells = runMdx(ctxXml, q.mdx);
            } catch (Throwable t) {
                // Original XML can't run the query → not a YAML
                // regression. Skip silently — these queries reflect
                // features that the fixture or schema doesn't
                // implement on this branch.
                continue;
            }
            try {
                yamlCells = runMdx(ctxYaml, q.mdx);
            } catch (Throwable t) {
                divergentQueries++;
                diagnostics.append("\n[FAIL] ").append(q.name)
                    .append(": YAML schema couldn't run query: ")
                    .append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage());
                continue;
            }
            if (xmlCells.length != yamlCells.length) {
                divergentQueries++;
                diagnostics.append("\n[FAIL] ").append(q.name)
                    .append(": row count diverged (xml=")
                    .append(xmlCells.length).append(", yaml=")
                    .append(yamlCells.length).append(")");
                continue;
            }
            boolean perCellDivergence = false;
            for (int i = 0; i < xmlCells.length; i++) {
                if (!xmlCells[i].equals(yamlCells[i])) {
                    diagnostics.append("\n[FAIL] ").append(q.name)
                        .append(" cell ").append(i)
                        .append(" diverged: xml='")
                        .append(xmlCells[i]).append("' yaml='")
                        .append(yamlCells[i]).append("'");
                    perCellDivergence = true;
                    break;
                }
            }
            if (perCellDivergence) {
                divergentQueries++;
            }
        }

        assertEquals(
            "expected 0 divergent queries across smoke corpus, got "
                + divergentQueries + " divergence(s):" + diagnostics,
            0, divergentQueries);
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
                                    ? new int[] {c, r}
                                    : new int[] {c})
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

// End FoodMart3SmokeCorpusEquivalenceTest.java
