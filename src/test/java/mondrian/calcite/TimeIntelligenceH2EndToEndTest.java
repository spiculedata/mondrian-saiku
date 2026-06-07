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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * #112 Phase 2: golden time-intelligence values from the declarative
 * &lt;TimeCalc&gt; declarations on the Bank "Monthly Revenue" cube, in both XML
 * and YAML schema forms, plus a fail-closed validation case.
 */
public class TimeIntelligenceH2EndToEndTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_timeintel;DB_CLOSE_DELAY=-1";
    private static String xmlSchema;
    private static String yamlSchema;

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
        xmlSchema = new String(
            Files.readAllBytes(Path.of("demo/Bank.mondrian.xml")));
        yamlSchema = mondrian.schema.yaml.m4.M4YamlToXml.toXml(
            mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(xmlSchema));
    }

    private static Connection connect(String form) {
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(),
            "yaml".equals(form) ? yamlSchema : xmlSchema);
        return DriverManager.getConnection(props, null, null);
    }

    private static Double cell(Connection c, String measure, String month) {
        Query q = c.parseQuery(
            "SELECT {[Measures].[" + measure + "]} ON COLUMNS,\n"
            + " {" + month + "} ON ROWS\n"
            + "FROM [Monthly Revenue]");
        Result r = c.execute(q);
        Object v = r.getCell(new int[]{0, 0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    /** Read a whole row of measures (one column each) for a single member. */
    private static Double[] row(Connection c, String[] measures, String month) {
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < measures.length; i++) {
            cols.append(i == 0 ? "" : ", ")
                .append("[Measures].[").append(measures[i]).append("]");
        }
        Query q = c.parseQuery(
            "SELECT {" + cols + "} ON COLUMNS,\n"
            + " {" + month + "} ON ROWS\n"
            + "FROM [Monthly Revenue]");
        Result r = c.execute(q);
        try {
            Double[] out = new Double[measures.length];
            for (int i = 0; i < measures.length; i++) {
                Object v = r.getCell(new int[]{i, 0}).getValue();
                out[i] = v == null ? null : ((Number) v).doubleValue();
            }
            return out;
        } finally {
            r.close();
        }
    }

    // Member paths pinned from probe: yr=2024/2025 (key), quarter=2024-Q1/2025-Q1 (key),
    // month level uses month_name ("Jan"/"Feb"/"Mar") because the Attribute has a separate
    // <Name> column — Mondrian derives the unique name from the name caption in this case.
    private static final String JAN25 =
        "[Calendar].[Calendar].[2025].[2025-Q1].[Jan]";
    private static final String FEB24 =
        "[Calendar].[Calendar].[2024].[2024-Q1].[Feb]";
    private static final String MAR24 =
        "[Calendar].[Calendar].[2024].[2024-Q1].[Mar]";
    private static final String MAR25 =
        "[Calendar].[Calendar].[2025].[2025-Q1].[Mar]";

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void timeIntelligenceGoldenValues(String form) {
        Connection c = connect(form);
        try {
            assertEquals(0.5, cell(c, "Revenue YoY", JAN25), 0.001,
                "YoY Jan2025 = (150-100)/100");
            assertEquals(1.0, cell(c, "Revenue PoP", FEB24), 0.001,
                "PoP Feb2024 = (200-100)/100");
            assertEquals(600.0, cell(c, "Revenue YTD", MAR24), 0.001,
                "YTD Mar2024 = 100+200+300");
            assertEquals(250.0, cell(c, "Revenue R3", MAR25), 0.001,
                "rolling-3 avg Mar2025 = (150+250+350)/3");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void yoyAndPopOnEarliestPeriodAreBlankNotInfinity(String form) {
        // 2024 is the earliest year — no prior (2023) to compare against. The
        // empty-prior guard must render YoY/PoP as NULL (blank), not Infinity%.
        Connection c = connect(form);
        try {
            assertNull(cell(c, "Revenue YoY", "[Calendar].[Calendar].[2024]"),
                "YoY of the first year (no prior) must be blank, not Infinity");
            assertNull(cell(c, "Revenue PoP", "[Calendar].[Calendar].[2024]"),
                "PoP of the first year (no prior) must be blank, not Infinity");
            // And a period WITH a prior still computes (sanity).
            assertEquals(0.25,
                cell(c, "Revenue YoY", "[Calendar].[Calendar].[2025]"), 0.001,
                "2025 YoY = (750-600)/600 = 0.25");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    /**
     * QUERY SHAPE: all four TimeCalc kinds (YoY/PoP/YTD/rolling) requested
     * together in one query at the same member must each evaluate
     * independently and correctly — no cross-talk between the desugared calcs.
     */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void multipleTimeCalcsInOneQuery(String form) {
        Connection c = connect(form);
        try {
            // Mar 2025: prior month Feb2025=250, prior year Mar2024=300,
            // YTD = 150+250+350 = 750, rolling-3 avg = (150+250+350)/3 = 250.
            Double[] vals = row(c,
                new String[]{
                    "Revenue YoY", "Revenue PoP", "Revenue YTD", "Revenue R3"},
                MAR25);
            assertEquals(0.1667, vals[0], 0.001, "YoY Mar2025 = (350-300)/300");
            assertEquals(0.4, vals[1], 0.001, "PoP Mar2025 = (350-250)/250");
            assertEquals(750.0, vals[2], 0.001, "YTD Mar2025 = 150+250+350");
            assertEquals(250.0, vals[3], 0.001, "R3 Mar2025 = avg(150,250,350)");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    /**
     * QUERY VALIDITY / FAIL-CLOSED: a TimeCalc whose {@code timeDimension}
     * names a dimension that does not exist must be refused at schema load
     * ({@code resolveYearLevel} fails closed), never silently dropped.
     */
    @Test
    public void timeCalcWithUnknownTimeDimensionFailsClosed() {
        String bad = xmlSchema.replace(
            "timeDimension=\"Calendar\"", "timeDimension=\"NoSuchDim\"");
        org.junit.jupiter.api.Assertions.assertNotEquals(xmlSchema, bad,
            "the timeDimension replace must match the schema text exactly");
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), bad);
        assertThrows(RuntimeException.class, () -> {
            Connection c = DriverManager.getConnection(props, null, null);
            c.execute(c.parseQuery(
                "SELECT {[Measures].[Revenue]} ON COLUMNS FROM [Monthly Revenue]"));
        }, "a TimeCalc naming a missing time dimension must fail closed at load");
    }

    @Test
    public void timeCalcWithUnknownMeasureFailsClosed() {
        String bad = xmlSchema.replace(
            "<TimeCalc name=\"Revenue YoY\"  type=\"yoy\"     measure=\"Revenue\"",
            "<TimeCalc name=\"Revenue YoY\"  type=\"yoy\"     measure=\"Nope\"");
        // Guard: the replace must have matched (otherwise the test is vacuous).
        org.junit.jupiter.api.Assertions.assertNotEquals(xmlSchema, bad,
            "the TimeCalc replace must match the schema text exactly");
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), bad);
        assertThrows(RuntimeException.class, () -> {
            Connection c = DriverManager.getConnection(props, null, null);
            c.execute(c.parseQuery(
                "SELECT {[Measures].[Revenue]} ON COLUMNS FROM [Monthly Revenue]"));
        }, "a TimeCalc on a missing measure must fail closed at load");
    }
}
