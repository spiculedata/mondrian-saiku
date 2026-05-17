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
 * Issue #74 — sibling to {@link MultiSharedDimSameTableLegacyAliasTest}.
 * PR #63 closed the multi-shared-dim alias collision when both
 * dimensions declare their physical table at the {@code <Dimension>}
 * level ({@code <Dimension name='Store3' table='store'>}). The
 * follow-up audit on 4.8.1.11 found 8 distinct failure shapes for
 * the variant where one dim declares the table at the
 * {@code <Attribute>} level instead:
 *
 * <pre>{@code
 * <Dimension name='Store2' key='Store Id'>
 *   <Attributes>
 *     <Attribute name='Store Id'  table='store' keyColumn='store_id'/>
 *     <Attribute name='Store Type' table='store' keyColumn='store_type'/>
 *     ...
 *   </Attributes>
 *   <Hierarchies>...</Hierarchies>
 * </Dimension>
 * }</pre>
 *
 * <p>The widened trigger {@code RolapSchemaLoader.useSharedDimension}
 * added in #63 keys {@code cubeToPhysTableMap} on
 * {@code sharedDimension.table}. For the attribute-scoped variant
 * above that field is {@code null}, so the lookup misses, no clone
 * fires, and both {@code Store2} and {@code Store3} hand the same
 * {@code store} alias to {@code SqlQueryBuilder.addFromSuper} —
 * tripping the same {@code "query already contains alias 'store'"}
 * assertion #63 was supposed to fix.
 *
 * <p>Fix: when {@code sharedDimension.table} is null, fall back to
 * the first non-null {@code attribute.table} across the dim's
 * {@code <Attributes>}. Shared dims always source from a single
 * physical table, so the first attribute is a reliable proxy.
 */
public class MultiSharedDimAttributeScopedAliasTest {

    /**
     * Store2 uses ATTRIBUTE-scoped {@code table='store'} (the variant
     * #63 missed). Store3 uses DIM-scoped {@code table='store'} (the
     * variant #63 fixed). A cube imports both linked to
     * {@code sales_fact_1997}.
     */
    private static final String SCHEMA_OVERLAY =
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
        // Store3: dim-level table='store' (already-fixed shape, kept
        // so the second dim has the same physical target).
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
        System.setProperty(
            "mondrian.native.crossjoin.enable", "true");
        System.setProperty(
            "mondrian.native.nonempty.enable", "true");
    }

    @AfterEach
    public void clearBackend() {
        System.clearProperty("mondrian.backend");
        System.clearProperty("mondrian.native.crossjoin.enable");
        System.clearProperty("mondrian.native.nonempty.enable");
    }

    @Test
    public void crossjoinSpansAttributeScopedSharedDim() {
        // Three of the 8 failure shapes from issue #74's audit.
        // All three route through SqlTupleReader.prepareTuples →
        // SqlQueryBuilder.flush → SqlQuery.addFromTable, which is
        // exactly the assertion site #63 hardened against the
        // dim-level variant but missed for attribute-scoped tables.
        String[] shapes = {
            "SELECT {[Measures].[Sales Count]} ON COLUMNS,\n"
            + "       NON EMPTY CROSSJOIN(\n"
            + "         [Store2].[Store].[Store Type].MEMBERS,\n"
            + "         [Store3].[Store].[Store Manager].MEMBERS\n"
            + "       ) ON ROWS\n"
            + "FROM [WarehouseAndSales74]",
            "SELECT NON EMPTY [Store2].[Store].[Store Type].MEMBERS"
            + " ON COLUMNS,\n"
            + "       NON EMPTY [Store3].[Store].[Store Manager].MEMBERS"
            + " ON ROWS\n"
            + "FROM [WarehouseAndSales74]\n"
            + "WHERE [Measures].[Sales Count]",
            "SELECT {[Measures].[Sales Count]} ON COLUMNS,\n"
            + "       NON EMPTY [Store3].[Store].[Store Manager].MEMBERS"
            + " ON ROWS\n"
            + "FROM [WarehouseAndSales74]\n"
            + "WHERE [Store2].[Store].[Store Type].[Supermarket]"
        };
        for (String mdx : shapes) {
            final String q = mdx;
            Result result = assertDoesNotThrow(() -> execute(q),
                "Attribute-scoped multi-shared-dim shape under legacy "
                    + "backend must not assert 'query already contains "
                    + "alias store' — failing shape was:\n" + mdx);
            assertNotNull(result);
        }
    }

    private static Result execute(String mdx) {
        Query q = mondrianConn.parseQuery(mdx);
        return mondrianConn.execute(q);
    }
}
