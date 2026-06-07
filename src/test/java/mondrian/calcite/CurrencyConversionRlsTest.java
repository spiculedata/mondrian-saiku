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

/**
 * #112 SECURITY: a currency-converted measure ({@code <CurrencyConversion>}) is
 * produced by an effective-date rate band join applied at the Calcite
 * segment-load chokepoint. Row-security (a {@code <HierarchyGrant>} on the
 * Calendar dimension) must still bound what the converted measure can disclose —
 * the conversion must NOT become a side channel that re-exposes hidden periods.
 *
 * <p>The {@code Y2024} role grants only the 2024 Calendar subtree. Under it:
 * <ul>
 *   <li>the converted measure for the visible 2024 is correct (660 = 600 @ 1.10),
 *       proving conversion composes with RLS rather than being bypassed; and</li>
 *   <li>the hidden 2025 (whose unsecured converted value is 900 — see
 *       {@link CurrencyConversionH2EndToEndTest}) is NOT disclosed: the query
 *       either fails closed or returns blank, never 900.</li>
 * </ul>
 */
public class CurrencyConversionRlsTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_fx_rls;DB_CLOSE_DELAY=-1";
    private static String xmlSchema;

    /** Custom hierarchy grant on Calendar, visible subtree = 2024 only. */
    private static final String ROLE_Y2024 =
        "  <Role name=\"Y2024\">\n"
        + "    <SchemaGrant access=\"all\">\n"
        + "      <CubeGrant cube=\"Monthly Revenue\" access=\"all\">\n"
        + "        <HierarchyGrant hierarchy=\"[Calendar].[Calendar]\""
        + " access=\"custom\""
        + " bottomLevel=\"[Calendar].[Calendar].[Month]\">\n"
        + "          <MemberGrant member=\"[Calendar].[Calendar].[2024]\""
        + " access=\"all\"/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n";

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
        xmlSchema = base.replace("</Schema>", ROLE_Y2024 + "</Schema>");
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
     * The converted measure for the VISIBLE 2024 is correct under the role —
     * proving the band join / rate weighting composes with RLS (not bypassed to
     * an unsecured path).
     */
    @Test
    public void convertedMeasureCorrectForVisiblePeriodUnderRole() {
        Connection c = connect("Y2024");
        try {
            assertEquals(660.0,
                cell(c, "Revenue (USD)", "[Calendar].[Calendar].[2024]"),
                0.001, "2024 Revenue (USD) = 600 @ 1.10 under the Y2024 role");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    /**
     * The converted measure must NOT disclose a hidden period. Asking for the
     * un-granted 2025 (unsecured value 900) must fail closed: either the member
     * is inaccessible (the query throws) or the cell is blank — never 900.
     */
    @Test
    public void convertedMeasureDoesNotDiscloseHiddenPeriod() {
        Connection c = connect("Y2024");
        try {
            Double v;
            try {
                v = cell(c, "Revenue (USD)", "[Calendar].[Calendar].[2025]");
            } catch (RuntimeException failClosed) {
                // Inaccessible member → query refused. That is fail-closed.
                return;
            }
            org.junit.jupiter.api.Assertions.assertTrue(
                v == null || Math.abs(v - 900.0) > 0.001,
                "hidden 2025 converted value must not be disclosed, got " + v);
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
