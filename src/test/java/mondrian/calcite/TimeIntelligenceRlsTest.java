/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.calcite;

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * #112 SECURITY: declarative {@code <TimeCalc>} measures (YoY/PoP) desugar into
 * MDX that reaches a PRIOR period (ParallelPeriod / PrevMember). When a role's
 * row-security hides that prior period, the calc must NOT disclose the hidden
 * value — it must render blank, exactly as the empty-prior guard does for a
 * genuinely missing prior.
 *
 * <p>The Bank "Monthly Revenue" cube carries only the Calendar (TIME) dimension,
 * so row-security here is a {@code <HierarchyGrant access='custom'>} on
 * {@code [Calendar].[Calendar]}. The {@code Y2025} role grants only the 2025
 * subtree; 2024 is invisible. A user under that role asking for {@code Revenue
 * YoY} of 2025 (whose ParallelPeriod basis is the hidden 2024) must see blank —
 * never the leaked 0.25 that the unsecured query returns.
 *
 * <p>Companion to {@link TimeIntelligenceH2EndToEndTest} (the unsecured golden
 * values) — that test proves {@code YoY[2025] == 0.25}, which is precisely the
 * value this test proves a role-restricted user can NOT read.
 */
public class TimeIntelligenceRlsTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_timeintel_rls;DB_CLOSE_DELAY=-1";
    private static String xmlSchema;

    /** Custom hierarchy grant on Calendar, visible subtree = 2025 only. */
    private static final String ROLE_Y2025 =
        "  <Role name=\"Y2025\">\n"
        + "    <SchemaGrant access=\"all\">\n"
        + "      <CubeGrant cube=\"Monthly Revenue\" access=\"all\">\n"
        + "        <HierarchyGrant hierarchy=\"[Calendar].[Calendar]\""
        + " access=\"custom\""
        + " bottomLevel=\"[Calendar].[Calendar].[Month]\">\n"
        + "          <MemberGrant member=\"[Calendar].[Calendar].[2025]\""
        + " access=\"all\"/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n";

    private static final String JAN25 =
        "[Calendar].[Calendar].[2025].[2025-Q1].[Jan]";
    private static final String FEB25 =
        "[Calendar].[Calendar].[2025].[2025-Q1].[Feb]";

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            st.execute("RUNSCRIPT FROM 'demo/bank.sql'");
        }
        String base = new String(
            Files.readAllBytes(Path.of("demo/Bank.mondrian.xml")));
        // Splice the Y2025 role in just before </Schema>.
        xmlSchema = base.replace("</Schema>", ROLE_Y2025 + "</Schema>");
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    private static Connection connect(String role) {
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), xmlSchema);
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        }
        return DriverManager.getConnection(props, null, null);
    }

    private static Double cell(Connection c, String measure, String member) {
        Query q = c.parseQuery(
            "SELECT {[Measures].[" + measure + "]} ON COLUMNS,\n"
            + " {" + member + "} ON ROWS\n"
            + "FROM [Monthly Revenue]");
        Result r = c.execute(q);
        try {
            Object v = r.getCell(new int[]{0, 0}).getValue();
            return v == null ? null : ((Number) v).doubleValue();
        } finally {
            r.close();
        }
    }

    /**
     * THE LEAK TEST: under the Y2025 role, YoY of the visible year 2025 reaches
     * the hidden 2024 via ParallelPeriod. The hidden prior must read as empty,
     * so YoY renders blank — NOT the unsecured 0.25 (proven in
     * {@link TimeIntelligenceH2EndToEndTest}).
     */
    @Test
    public void yoyDoesNotLeakHiddenPriorYear() {
        Connection c = connect("Y2025");
        try {
            assertNull(cell(c, "Revenue YoY", "[Calendar].[Calendar].[2025]"),
                "YoY of 2025 must be blank when the 2024 basis is hidden by RLS"
                + " — never the unsecured 0.25");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    /**
     * Same vector at month grain: PoP of Jan 2025 steps to Dec 2024 (hidden);
     * must be blank, not a disclosed cross-boundary ratio.
     */
    @Test
    public void popDoesNotLeakHiddenPriorMonth() {
        Connection c = connect("Y2025");
        try {
            assertNull(cell(c, "Revenue PoP", JAN25),
                "PoP of the first visible month must be blank when its prior"
                + " month is hidden by RLS");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    /**
     * SANITY: row-security must not break a calc whose BOTH periods are visible.
     * PoP of Feb 2025 over Jan 2025 (both in the granted 2025 subtree) still
     * computes (250-150)/150.
     */
    @Test
    public void popWithinVisibleScopeStillComputes() {
        Connection c = connect("Y2025");
        try {
            assertEquals(0.6667, cell(c, "Revenue PoP", FEB25), 0.001,
                "PoP Feb2025 over visible Jan2025 = (250-150)/150");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
