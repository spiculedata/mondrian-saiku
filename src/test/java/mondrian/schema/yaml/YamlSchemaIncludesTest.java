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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * #34 Phase 1 Session 6: {@code $ref} include resolution for
 * multi-file YAML schemas.
 *
 * <p>A map containing exactly the key {@code $ref} (with a relative
 * file path as value) is spliced — the loaded YAML's root replaces
 * the {@code $ref} map. Refs are resolved relative to the file
 * containing them so layouts like
 *
 * <pre>
 *   schema.yaml          ← top-level
 *   shared/store.yaml    ← shared dimension
 *   cubes/sales.yaml     ← cube definition
 * </pre>
 *
 * <p>work without forcing every reference to know the project root.
 *
 * <p>End-to-end equivalence acid test: a 3-file split of the FoodMart
 * Sales cube returns byte-identical MDX cells to the inline-everything
 * version, proving include resolution is semantically a pure splice.
 */
public class YamlSchemaIncludesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Splice a shared dimension from a sibling file. */
    @Test
    public void refResolvesSharedDimensionFromSiblingFile() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Path schema = dir.resolve("schema.yaml");
        Path storeDim = dir.resolve("store.yaml");
        Files.writeString(
            schema,
            "schema: Inc\n"
            + "shared_dimensions:\n"
            + "  Store:\n"
            + "    $ref: store.yaml\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            storeDim,
            "hierarchy:\n"
            + "  has_all: true\n"
            + "  primary_key: store_id\n"
            + "  table: store\n"
            + "  levels:\n"
            + "    - name: Store Country\n"
            + "      column: store_country\n"
            + "      unique_members: true\n",
            StandardCharsets.UTF_8);

        String xml = YamlSchemaConverter.toXmlFromPath(schema);

        assertTrue("spliced Store dim missing:\n" + xml,
            xml.contains("<Dimension name=\"Store\""));
        assertTrue("spliced Store hierarchy table missing:\n" + xml,
            xml.contains("<Table name=\"store\""));
        assertTrue("spliced Store Country level missing:\n" + xml,
            xml.contains("name=\"Store Country\""));
    }

    /** Splice an entire cube body. */
    @Test
    public void refResolvesCubeFromSiblingFile() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Path schema = dir.resolve("schema.yaml");
        Path salesCube = dir.resolve("sales.yaml");
        Files.writeString(
            schema,
            "schema: Inc\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    $ref: sales.yaml\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            salesCube,
            "fact_table: sales_fact_1997\n"
            + "measures:\n"
            + "  - name: Unit Sales\n"
            + "    column: unit_sales\n"
            + "    aggregator: sum\n",
            StandardCharsets.UTF_8);

        String xml = YamlSchemaConverter.toXmlFromPath(schema);

        assertTrue("spliced cube body missing fact table:\n" + xml,
            xml.contains("<Table name=\"sales_fact_1997\""));
        assertTrue("spliced measure missing:\n" + xml,
            xml.contains("<Measure name=\"Unit Sales\""));
    }

    /** Refs nested under a subdirectory must resolve relative to the
     *  file that contains the ref, not the top-level file. */
    @Test
    public void refResolvesRelativeToContainingFile() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Path schema = dir.resolve("schema.yaml");
        Path cubesDir = dir.resolve("cubes");
        Files.createDirectories(cubesDir);
        Path cube = cubesDir.resolve("sales.yaml");
        Path measures = cubesDir.resolve("measures.yaml");
        Files.writeString(
            schema,
            "schema: Inc\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    $ref: cubes/sales.yaml\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            cube,
            "fact_table: sales_fact_1997\n"
            + "measures:\n"
            + "  $ref: measures.yaml\n",
            StandardCharsets.UTF_8);
        // Note: relative to cube file (cubes/), NOT the schema (root).
        Files.writeString(
            measures,
            "- name: Unit Sales\n"
            + "  column: unit_sales\n"
            + "  aggregator: sum\n",
            StandardCharsets.UTF_8);

        String xml = YamlSchemaConverter.toXmlFromPath(schema);

        assertTrue("transitively resolved measure missing:\n" + xml,
            xml.contains("<Measure name=\"Unit Sales\""));
    }

    /** $ref to a missing file fails with a useful message. */
    @Test
    public void refToMissingFileThrowsClearError() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Path schema = dir.resolve("schema.yaml");
        Files.writeString(
            schema,
            "schema: Inc\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    $ref: does-not-exist.yaml\n",
            StandardCharsets.UTF_8);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> YamlSchemaConverter.toXmlFromPath(schema));
        String msg = ex.getMessage();
        assertTrue("expected useful error mentioning the missing path; got: "
                + msg,
            msg.contains("does-not-exist.yaml"));
    }

    /** Cyclic refs (a.yaml → b.yaml → a.yaml) detected and rejected. */
    @Test
    public void refCycleIsDetected() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Path schema = dir.resolve("a.yaml");
        Path other = dir.resolve("b.yaml");
        Files.writeString(
            schema,
            "schema: Inc\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    $ref: b.yaml\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            other,
            "$ref: a.yaml\n",
            StandardCharsets.UTF_8);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> YamlSchemaConverter.toXmlFromPath(schema));
        assertTrue("expected cycle error mentioning the path; got: "
                + ex.getMessage(),
            ex.getMessage().toLowerCase().contains("cycle")
                || ex.getMessage().toLowerCase().contains("circular"));
    }

    /**
     * Acid test: split a working schema into 3 files (schema + shared
     * dims + cube), resolve includes, run an MDX query, assert the
     * cells match the inline-everything version of the same schema.
     */
    @Test
    public void splitSchemaReturnsIdenticalCellsToInlineEquivalent()
        throws Exception
    {
        Path dir = tmp.getRoot().toPath();
        Path schemaPath = dir.resolve("schema.yaml");
        Path timeDim = dir.resolve("time.yaml");
        Path salesCube = dir.resolve("sales.yaml");

        Files.writeString(
            schemaPath,
            "schema: SplitSchema\n"
            + "shared_dimensions:\n"
            + "  Time:\n"
            + "    $ref: time.yaml\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    $ref: sales.yaml\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            timeDim,
            "type: TimeDimension\n"
            + "hierarchy:\n"
            + "  has_all: false\n"
            + "  primary_key: time_id\n"
            + "  table: time_by_day\n"
            + "  levels:\n"
            + "    - name: Year\n"
            + "      column: the_year\n"
            + "      type: Numeric\n"
            + "      unique_members: true\n"
            + "      level_type: TimeYears\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            salesCube,
            "fact_table: sales_fact_1997\n"
            + "dimension_usages:\n"
            + "  - name: Time\n"
            + "    source: Time\n"
            + "    foreign_key: time_id\n"
            + "measures:\n"
            + "  - name: Unit Sales\n"
            + "    column: unit_sales\n"
            + "    aggregator: sum\n",
            StandardCharsets.UTF_8);

        String inlineYaml =
            "schema: SplitSchema\n"
            + "shared_dimensions:\n"
            + "  Time:\n"
            + "    type: TimeDimension\n"
            + "    hierarchy:\n"
            + "      has_all: false\n"
            + "      primary_key: time_id\n"
            + "      table: time_by_day\n"
            + "      levels:\n"
            + "        - name: Year\n"
            + "          column: the_year\n"
            + "          type: Numeric\n"
            + "          unique_members: true\n"
            + "          level_type: TimeYears\n"
            + "cubes:\n"
            + "  Sales:\n"
            + "    fact_table: sales_fact_1997\n"
            + "    dimension_usages:\n"
            + "      - name: Time\n"
            + "        source: Time\n"
            + "        foreign_key: time_id\n"
            + "    measures:\n"
            + "      - name: Unit Sales\n"
            + "        column: unit_sales\n"
            + "        aggregator: sum\n";

        String mdx =
            "SELECT [Measures].[Unit Sales] ON COLUMNS, "
            + "[Time].[Year].Members ON ROWS "
            + "FROM [Sales]";

        String[] inlineCells = runMdx(
            YamlSchemaConverter.toXml(inlineYaml), mdx);
        String[] splitCells = runMdx(
            YamlSchemaConverter.toXmlFromPath(schemaPath), mdx);

        assertEquals("row count diverged",
            inlineCells.length, splitCells.length);
        for (int i = 0; i < inlineCells.length; i++) {
            assertEquals(
                "cell " + i + " diverged",
                inlineCells[i], splitCells[i]);
        }
        boolean anyNonBlank = false;
        for (String c : inlineCells) {
            if (!c.isEmpty()) { anyNonBlank = true; break; }
        }
        assertTrue("expected at least one non-blank cell", anyNonBlank);
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
                String[] cells = new String[rowCount];
                for (int r = 0; r < rowCount; r++) {
                    Object value = result.getCell(new int[] {0, r})
                        .getFormattedValue();
                    cells[r] = value == null ? "" : value.toString();
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

// End YamlSchemaIncludesTest.java
