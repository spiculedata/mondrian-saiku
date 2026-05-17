/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Util;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Issue #77: PR #63's clone path in
 * {@code RolapSchemaLoader.useSharedDimension} constructs the cloned
 * {@code MondrianDef.Dimension} via
 * {@code new MondrianDef.Dimension(sharedDimension._def)}. This NPEs
 * for <strong>every M3 (legacy) schema</strong> because
 * {@code RolapSchemaUpgrader.upgrade()} builds the fresh
 * {@code MondrianDef.Schema} programmatically via the <em>no-arg</em>
 * {@code Dimension()} constructor, which leaves {@code _def = null}.
 *
 * <p>The bug went undetected because no existing test combined an M3
 * shared-dim-via-DimensionUsage shape with the {@code #63} trigger:
 * the existing {@link MultiSharedDimSameTableLegacyAliasTest} and
 * {@link MultiSharedDimAttributeScopedAliasTest} both overlay onto
 * {@code demo/FoodMart.mondrian.xml} (M4 modern format), which skips
 * the upgrader and leaves {@code _def} populated from XOM parsing.
 *
 * <p>Repro shape: two top-level shared dims targeting the same
 * physical table, a cube importing both via {@code <DimensionUsage>}.
 * Just attempting to open a connection trips the NPE — no MDX query
 * needed.
 */
public class MultiSharedDimCloneNullDefTest {

    /**
     * M3 legacy-format overlay: Custom1 + Custom2 both
     * {@code table='customer'} (different attribute), cube uses both
     * via DimensionUsage. Mirrors the FoodMart3 Customers/Education
     * Level/Gender/Marital Status pattern in the smallest form that
     * trips the bug.
     *
     * <p>Note: this overlay uses Mondrian-3 element shapes
     * ({@code <Hierarchy>}, {@code <Level>}, {@code <DimensionUsage>})
     * because the trigger requires the upgrader to fire. M4 overlays
     * skip the upgrader and {@code _def} stays non-null.
     */
    private static final String M3_SCHEMA =
        "<?xml version=\"1.0\"?>\n"
        + "<Schema name=\"M3CloneRepro\">\n"
        + "  <Dimension name=\"Custom1\">\n"
        + "    <Hierarchy hasAll=\"true\" primaryKey=\"customer_id\">\n"
        + "      <Table name=\"customer\"/>\n"
        + "      <Level name=\"Gender\" column=\"gender\""
        +            " uniqueMembers=\"true\"/>\n"
        + "    </Hierarchy>\n"
        + "  </Dimension>\n"
        + "  <Dimension name=\"Custom2\">\n"
        + "    <Hierarchy hasAll=\"true\" primaryKey=\"customer_id\">\n"
        + "      <Table name=\"customer\"/>\n"
        + "      <Level name=\"Marital Status\" column=\"marital_status\""
        +            " uniqueMembers=\"true\"/>\n"
        + "    </Hierarchy>\n"
        + "  </Dimension>\n"
        + "  <Cube name=\"M3Sales\">\n"
        + "    <Table name=\"sales_fact_1997\"/>\n"
        + "    <DimensionUsage name=\"Custom1\" source=\"Custom1\""
        +            " foreignKey=\"customer_id\"/>\n"
        + "    <DimensionUsage name=\"Custom2\" source=\"Custom2\""
        +            " foreignKey=\"customer_id\"/>\n"
        + "    <Measure name=\"Sales Count\" column=\"product_id\""
        +            " aggregator=\"count\"/>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    @BeforeEach
    public void disableSchemaPool() {
        // Each test gets a fresh schema load so we exercise the
        // useSharedDimension clone path every time, not a cached
        // RolapSchema from a sibling test.
        System.setProperty("mondrian.rolap.SchemaPool.disable", "true");
    }

    @AfterEach
    public void restoreSchemaPool() {
        System.clearProperty("mondrian.rolap.SchemaPool.disable");
    }

    @AfterAll
    public static void tearDown() {
        // nothing — connections close in the test method
    }

    @Test
    public void loadingM3SchemaWithSharedTableDimsDoesNotNpe() {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(
            RolapConnectionProperties.CatalogContent.name(), M3_SCHEMA);
        props.remove(RolapConnectionProperties.Catalog.name());

        Connection conn = assertDoesNotThrow(
            () -> DriverManager.getConnection(props, null, null),
            "M3 schema with two shared dims on the same physical "
                + "table must not NPE on connection load — #63's "
                + "useSharedDimension clone path expects _def to be "
                + "populated, but the M3 upgrader leaves it null.");
        try {
            assertNotNull(conn);
        } finally {
            conn.close();
        }
    }
}
