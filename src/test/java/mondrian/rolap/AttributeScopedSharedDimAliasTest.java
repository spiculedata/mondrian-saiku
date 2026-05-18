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
import mondrian.olap.Query;
import mondrian.olap.Result;
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
 * Issue #74 — residual sliver of the #46/#51/#63 audit. The #63 fix dedupes
 * on {@code sharedDimension.table} (the {@code <Dimension table='store'>}
 * form) but misses the launcher's {@code Store2}-style variant where the
 * dim has no {@code table=} attribute and each {@code <Attribute>} carries
 * {@code table='store'} instead. Both forms resolve to the same physical
 * {@code store} table at execution time, so a cube that imports
 * {@code Store2} (attribute-scoped) and {@code Store3} (dim-scoped) still
 * fires {@code MondrianException: query already contains alias 'store'}
 * at {@code SqlQuery.addFromTable:264}.
 *
 * <p>Sibling test to
 * {@link MultiSharedDimSameTableLegacyAliasTest} which pins the
 * dim-level shape — same MDX runs but with the
 * {@code <Attribute table='store'>} variant.
 */
public class AttributeScopedSharedDimAliasTest {

    private static final String SCHEMA_OVERLAY =
        // Store2: NO dim-level table attribute; each Attribute carries
        // table='store'. Matches the launcher seed verbatim and is the
        // exact shape #63's cubeToPhysTableMap dedup misses.
        "<Dimension name='Store2' key='Store Id'>\n"
        + "  <Attributes>\n"
        + "    <Attribute name='Store Id' table='store'"
        + " keyColumn='store_id' hasHierarchy='false'/>\n"
        + "    <Attribute name='Store Type' table='store'"
        + " keyColumn='store_type'/>\n"
        + "  </Attributes>\n"
        + "  <Hierarchies>\n"
        + "    <Hierarchy name='Store' allMemberName='All Path'>\n"
        + "      <Level attribute='Store Type'/>\n"
        + "    </Hierarchy>\n"
        + "  </Hierarchies>\n"
        + "</Dimension>\n"
        // Store3: dim-level table='store' (the form #63 already handles).
        + "<Dimension name='Store3' table='store' key='Store Id'>\n"
        + "  <Attributes>\n"
        + "    <Attribute name='Store Id' keyColumn='store_id'"
        + " hasHierarchy='false'/>\n"
        + "    <Attribute name='Store Manager' keyColumn='store_manager'/>\n"
        + "  </Attributes>\n"
        + "  <Hierarchies>\n"
        + "    <Hierarchy name='Store' allMemberName='All Path'>\n"
        + "      <Level attribute='Store Manager'/>\n"
        + "    </Hierarchy>\n"
        + "  </Hierarchies>\n"
        + "</Dimension>\n"
        + "<Cube name='WarehouseAndSales74' defaultMeasure='Sales Count'>\n"
        + "  <Dimensions>\n"
        + "    <Dimension source='Store2'/>\n"
        + "    <Dimension source='Store3'/>\n"
        + "  </Dimensions>\n"
        + "  <MeasureGroups>\n"
        + "    <MeasureGroup name='Sales' table='sales_fact_1997'>\n"
        + "      <Measures>\n"
        + "        <Measure name='Sales Count' column='product_id'\n"
        + "                 aggregator='count'/>\n"
        + "      </Measures>\n"
        + "      <DimensionLinks>\n"
        + "        <ForeignKeyLink dimension='Store2'"
        + " foreignKeyColumn='store_id'/>\n"
        + "        <ForeignKeyLink dimension='Store3'"
        + " foreignKeyColumn='product_id'/>\n"
        + "      </DimensionLinks>\n"
        + "    </MeasureGroup>\n"
        + "  </MeasureGroups>\n"
        + "</Cube>\n";

    private static Connection mondrianConn;

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
        String catalog = new String(
            Files.readAllBytes(
                Paths.get("demo/FoodMart.mondrian.xml")));
        String overlay = catalog.replace(
            "</Schema>", SCHEMA_OVERLAY + "</Schema>");
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(
            RolapConnectionProperties.CatalogContent.name(), overlay);
        props.remove(RolapConnectionProperties.Catalog.name());
        mondrianConn = DriverManager.getConnection(props, null, null);
    }

    @AfterAll
    public static void close() {
        if (mondrianConn != null) {
            mondrianConn.close();
            mondrianConn = null;
        }
    }

    @BeforeEach
    public void forceLegacy() {
        System.setProperty("mondrian.backend", "legacy");
    }

    @AfterEach
    public void clearBackend() {
        System.clearProperty("mondrian.backend");
    }

    @Test
    public void crossjoinAttributeScopedStore2Store3DoesNotAssert() {
        String mdx =
            "SELECT {[Measures].[Sales Count]} ON COLUMNS,\n"
            + "       NON EMPTY CROSSJOIN(\n"
            + "         [Store2].[Store].[Store Type].MEMBERS,\n"
            + "         [Store3].[Store].[Store Manager].MEMBERS\n"
            + "       ) ON ROWS\n"
            + "FROM [WarehouseAndSales74]";
        Result result = assertDoesNotThrow(() -> execute(mdx),
            "Multi-shared-dim crossjoin where one dim binds 'store' at "
                + "the Attribute level and the other at the Dimension "
                + "level must not assert 'query already contains alias "
                + "store' (issue #74)");
        assertNotNull(result);
    }

    private static Result execute(String mdx) {
        Query q = mondrianConn.parseQuery(mdx);
        return mondrianConn.execute(q);
    }
}
