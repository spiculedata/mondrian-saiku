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

import org.junit.Ignore;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 M4 acceptance (deferred): the full M4 FoodMart schema round-trips
 * losslessly through YAML. Uses the real {@code demo/FoodMart.mondrian.xml}
 * as the source, runs a query that exercises the customer
 * {@code full_name} calculated column, and asserts the YAML round-trip
 * yields identical cells.
 *
 * <p>Currently {@link Ignore}d: at Phase 1 the M4 converter only handles
 * the physical layer, so the round-tripped XML lacks dimensions and
 * measure groups and cannot resolve {@code [Sales]}. The Phase 3 task
 * that adds cubes + measure groups removes the {@code @Ignore}.
 */
@Ignore("M4 dimensions/measure-groups land in Phase 2-3; remove @Ignore then")
public class M4PhysicalRoundtripTest {

    private static final String MDX =
        "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
        + "{[Customer].[Customers].[USA].[CA]} ON ROWS "
        + "FROM [Sales]";

    @Test
    public void foodMartM4PhysicalLayerRoundTripsIdentically()
        throws Exception
    {
        Path fixture = Paths.get("demo/FoodMart.mondrian.xml");
        assertTrue("fixture missing: " + fixture.toAbsolutePath(),
            Files.exists(fixture));
        String originalXml =
            Files.readString(fixture, StandardCharsets.UTF_8);

        String yaml = XmlSchemaToYaml.toYaml(originalXml);
        String roundTripped = YamlSchemaConverter.toXml(yaml);

        // Sanity: the physical layer survived (full_name expression present)
        assertTrue("round-tripped XML lost full_name",
            roundTripped.contains("full_name"));

        String[] original = runMdx(originalXml, MDX);
        String[] roundtrip = runMdx(roundTripped, MDX);
        assertArrayEquals(original, roundtrip);
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
                        Object value = result.getCell(new int[]{c, r})
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

// End M4PhysicalRoundtripTest.java
