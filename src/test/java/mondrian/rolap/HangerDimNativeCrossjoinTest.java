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
 * Regression test for issue #54: {@code MemberBuilder not found} internal
 * assertion at {@link RolapNativeSet}{@code .addLevel:386} when a NON EMPTY
 * crossjoin pairs a hanger-dim level (no joining table) with a real-dim
 * level. The launcher's HR cube hits this via {@code [Actual VS Budget]
 * [Type] × [Employee].[Store Id]}.
 *
 * <p>Schema shape (from issue follow-up): a {@code hanger="true"} dim with
 * a single bare {@code <Attribute>}, plus one {@code <CalculatedMember>}
 * pinned to the auto-generated hierarchy. The bare attribute means
 * Mondrian auto-creates a "regular" hierarchy structure, so
 * {@code RolapNativeCrossJoin.createEvaluator}'s {@code hasCalcMembers()}
 * gate doesn't reject it — but at execution time, hanger dims have no
 * SQL-backed MemberReader, so {@code memberReader.getMemberBuilder()}
 * returns null and the assertion fires.
 */
public class HangerDimNativeCrossjoinTest {

    private static final String SCHEMA_OVERLAY =
        "<Cube name='HangerCube54' defaultMeasure='Unit Sales'>\n"
        + "  <Dimensions>\n"
        + "    <Dimension name='Scenario54' hanger='true'>\n"
        + "      <Attributes>\n"
        + "        <Attribute name='Type'/>\n"
        + "      </Attributes>\n"
        + "    </Dimension>\n"
        + "    <Dimension source='Product'/>\n"
        + "  </Dimensions>\n"
        + "  <MeasureGroups>\n"
        + "    <MeasureGroup name='Sales' table='sales_fact_1997'>\n"
        + "      <Measures>\n"
        + "        <Measure name='Unit Sales' column='unit_sales'\n"
        + "                 aggregator='sum'/>\n"
        + "      </Measures>\n"
        + "      <DimensionLinks>\n"
        + "        <ForeignKeyLink dimension='Product'"
        + " foreignKeyColumn='product_id'/>\n"
        + "      </DimensionLinks>\n"
        + "    </MeasureGroup>\n"
        + "  </MeasureGroups>\n"
        // CalculatedMember pinned to the auto-generated hierarchy.
        // Launcher schema places this outside <CalculatedMembers>;
        // bundled loader expects it inside.
        // Match the launcher's [Actual VS Budget].[Type].[Actual] shape:
        // formula is a constant member reference, not a measure expression
        // (a self-referential measure would just recurse).
        + "  <CalculatedMembers>\n"
        + "    <CalculatedMember name='Actual'"
        + " hierarchy='[Scenario54].[Type]'"
        + " formula='1'/>\n"
        + "    <CalculatedMember name='Budget'"
        + " hierarchy='[Scenario54].[Type]'"
        + " formula='2'/>\n"
        + "  </CalculatedMembers>\n"
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
    public void forceNativeCrossjoin() {
        System.setProperty(
            "mondrian.native.crossjoin.enable", "true");
        System.setProperty(
            "mondrian.native.nonempty.enable", "true");
    }

    @AfterEach
    public void clearProps() {
        System.clearProperty("mondrian.native.crossjoin.enable");
        System.clearProperty("mondrian.native.nonempty.enable");
    }

    /**
     * Smoke test: schema loads, calc members register, the crossjoin
     * executes without the internal assertion. On bundled HSQLDB FoodMart
     * the MemberListCrossJoinArg path eagerly resolves the hanger axis
     * to just {@code [All Type]}, so the existing hasAllMember gate
     * already deflects this combination to non-native before the new
     * guard is exercised. The guard ships because the launcher's
     * {@code Actual VS Budget × Employee.Store Id} shape DOES bypass the
     * hasAllMember gate per the fuzz report on issue #54 — that
     * production reproducer is the real RED for this fix.
     */
    @Test
    public void crossjoinHangerLevelWithRealLevelDoesNotAssert() {
        String mdx =
            "SELECT {[Measures].[Unit Sales]} ON COLUMNS,\n"
            + "       NON EMPTY CROSSJOIN(\n"
            + "         [Scenario54].[Type].ALLMEMBERS,\n"
            + "         [Product].[Products].[Product Family].MEMBERS\n"
            + "       ) ON ROWS\n"
            + "FROM [HangerCube54]";
        Result result = assertDoesNotThrow(() -> execute(mdx),
            "Hanger-dim crossjoin must execute without internal "
                + "'MemberBuilder not found' assertion (issue #54)");
        assertNotNull(result);
    }

    /**
     * Precondition for the issue #54 guard: hanger-dim hierarchies must
     * surface a null MemberBuilder — that's the exact signal the guard
     * in {@link RolapNativeCrossJoin}{@code .createEvaluator} keys on to
     * decline native eval. If this invariant ever changes the guard would
     * silently stop firing; this test pins it down so a future Mondrian
     * change that wires a builder onto hanger reader trips this assertion
     * and forces re-think.
     */
    @Test
    public void hangerHierarchyHasNullMemberBuilder() {
        mondrian.olap.Cube cube = null;
        for (mondrian.olap.Cube c : mondrianConn.getSchema().getCubes()) {
            if ("HangerCube54".equals(c.getName())) {
                cube = c;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(
            cube, "HangerCube54 not loaded");
        for (mondrian.olap.Dimension d : cube.getDimensions()) {
            if (!"Scenario54".equals(d.getName())) {
                continue;
            }
            for (mondrian.olap.Hierarchy h : d.getHierarchies()) {
                RolapCubeHierarchy rch = (RolapCubeHierarchy) h;
                MemberReader mr = rch.getMemberReader();
                org.junit.jupiter.api.Assertions.assertNotNull(
                    mr,
                    "hanger hierarchy should have a MemberReader");
                org.junit.jupiter.api.Assertions.assertNull(
                    mr.getMemberBuilder(),
                    "hanger hierarchy must surface null MemberBuilder so "
                        + "the issue #54 guard in "
                        + "RolapNativeCrossJoin.createEvaluator can "
                        + "detect and decline native eval");
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail(
            "Did not find hanger dim Scenario54 on HangerCube54");
    }

    private static Result execute(String mdx) {
        Query q = mondrianConn.parseQuery(mdx);
        return mondrianConn.execute(q);
    }
}
