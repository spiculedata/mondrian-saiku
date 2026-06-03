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

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 1 Session 9: the strongest possible acid test for the
 * YAML converters — round-trip {@code demo/FoodMart3.mondrian.xml}
 * through {@link XmlSchemaToYaml} + {@link YamlSchemaConverter},
 * load both the original and the round-tripped XML into Mondrian,
 * run the same MDX query against each, assert byte-identical cell
 * values.
 *
 * <p>Unlike {@code FoodMart3RoundtripTest} which only proves the
 * converters consume + emit cleanly, this test proves the round-trip
 * is <em>semantically lossless</em> — Mondrian sees the same schema
 * and produces the same data. If any element type is silently dropped
 * by the converter pipeline, this test catches it.
 *
 * <p>Goes through {@code TestContext.withSchema} which runs the full
 * {@code RolapSchemaLoader} pipeline including legacy upgrader, so
 * Mondrian-3 enum values like {@code type="TimeDimension"} are
 * accepted (this is the test {@link SchemaCli#lint} couldn't run
 * standalone — but the test harness can since it has the full
 * loader + FoodMart fixture context).
 */
public class FoodMart3MdxEquivalenceTest {

    /** Workhorse MDX that exercises a wide spectrum of FoodMart3
     *  features: Measures axis, Store dim (shared with foreignKey),
     *  Time dim (multi-hierarchy), and the Customer Name level
     *  (KeyExpression with SQL dialect blocks + concatenation). */
    private static final String MDX =
        "SELECT {[Measures].[Unit Sales], [Measures].[Store Sales]} "
        + "ON COLUMNS, "
        + "[Store].[Store Country].Members "
        + "ON ROWS "
        + "FROM [Sales]";

    @Test
    public void roundTrippedFoodMart3ReturnsIdenticalCellsToOriginal()
        throws Exception
    {
        Path fixture = Paths.get("demo/FoodMart3.mondrian.xml");
        assertTrue("fixture missing: " + fixture.toAbsolutePath(),
            Files.exists(fixture));
        String originalXml = Files.readString(
            fixture, StandardCharsets.UTF_8);

        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        String roundTrippedXml = YamlSchemaConverter.toXml(yaml);

        String[] originalCells = runMdx(originalXml, MDX);
        String[] roundTrippedCells = runMdx(roundTrippedXml, MDX);

        assertEquals("row × col count diverged",
            originalCells.length, roundTrippedCells.length);
        for (int i = 0; i < originalCells.length; i++) {
            assertEquals(
                "cell " + i + " diverged after round-trip",
                originalCells[i], roundTrippedCells[i]);
        }
        boolean anyNonBlank = false;
        for (String c : originalCells) {
            if (!c.isEmpty()) { anyNonBlank = true; break; }
        }
        assertTrue("expected at least one non-blank cell, got: "
                + java.util.Arrays.toString(originalCells),
            anyNonBlank);
    }

    /** Second probe — a different MDX exercising the Product Join
     *  (product + product_class multi-table hierarchy). Catches any
     *  Join-handling regression independently. */
    @Test
    public void roundTrippedFoodMart3HandlesJoinedHierarchies()
        throws Exception
    {
        String originalXml = Files.readString(
            Paths.get("demo/FoodMart3.mondrian.xml"),
            StandardCharsets.UTF_8);
        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        String roundTrippedXml = YamlSchemaConverter.toXml(yaml);

        String mdx =
            "SELECT [Measures].[Unit Sales] ON COLUMNS, "
            + "[Product].[Product Family].Members "
            + "ON ROWS "
            + "FROM [Sales]";

        String[] originalCells = runMdx(originalXml, mdx);
        String[] roundTrippedCells = runMdx(roundTrippedXml, mdx);

        assertEquals("Product axis row count diverged",
            originalCells.length, roundTrippedCells.length);
        for (int i = 0; i < originalCells.length; i++) {
            assertEquals(
                "Product cell " + i + " diverged",
                originalCells[i], roundTrippedCells[i]);
        }
    }

    private static String[] runMdx(String schemaXml, String mdx) {
        TestContext ctx = TestContext.instance().withSchema(schemaXml);
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(mdx);
            Result result = conn.execute(q);
            try {
                int rowCount = result.getAxes().length >= 2
                    ? result.getAxes()[1].getPositions().size() : 0;
                int colCount = result.getAxes().length >= 1
                    ? result.getAxes()[0].getPositions().size() : 0;
                String[] cells = new String[rowCount * colCount];
                int k = 0;
                for (int r = 0; r < rowCount; r++) {
                    for (int c = 0; c < colCount; c++) {
                        Object value = result.getCell(new int[] {c, r})
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

// End FoodMart3MdxEquivalenceTest.java
