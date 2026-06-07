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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * #112 phase 3: the {@code <CurrencyConversion>} on the Bank "Monthly Revenue"
 * cube converts EUR revenue to USD via an effective-date rate band join
 * (fx_rate, 1.10 across 2024, 1.20 across 2025). Asserts the converted total +
 * the per-year interval boundary in BOTH schema forms, plus fail-closed cases.
 */
public class CurrencyConversionH2EndToEndTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_fx;DB_CLOSE_DELAY=-1";
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

    private static Connection connect(String catalog) {
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        return DriverManager.getConnection(props, null, null);
    }

    private static Connection connect(String form, boolean ignored) {
        return connect("yaml".equals(form) ? yamlSchema : xmlSchema);
    }

    private static Double scalar(Connection c, String mdx) {
        Query q = c.parseQuery(mdx);
        Result r = c.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Double cell(Connection c, String measure, String member) {
        Query q = c.parseQuery(
            "SELECT {[Measures].[" + measure + "]} ON COLUMNS,\n"
            + " {" + member + "} ON ROWS\n"
            + "FROM [Monthly Revenue]");
        Result r = c.execute(q);
        Object v = r.getCell(new int[]{0, 0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void convertedGrandTotalUsesPerIntervalRate(String form) {
        Connection c = connect(form, true);
        try {
            // (100+200+300)*1.10 + (150+250+350)*1.20 = 660 + 900 = 1560.
            assertEquals(1560.0, scalar(c,
                "SELECT {[Measures].[Revenue (USD)]} ON COLUMNS"
                + " FROM [Monthly Revenue]"), 0.001,
                "converted total picks 1.10 for 2024, 1.20 for 2025");
            // The base measure is unchanged (band join is 1:1).
            assertEquals(1350.0, scalar(c,
                "SELECT {[Measures].[Revenue]} ON COLUMNS"
                + " FROM [Monthly Revenue]"), 0.001,
                "base EUR Revenue unchanged by the conversion");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void intervalBoundaryPicksTheRightRate(String form) {
        Connection c = connect(form, true);
        try {
            assertEquals(660.0,
                cell(c, "Revenue (USD)", "[Calendar].[Calendar].[2024]"),
                0.001, "2024 revenue 600 @ 1.10 = 660");
            assertEquals(900.0,
                cell(c, "Revenue (USD)", "[Calendar].[Calendar].[2025]"),
                0.001, "2025 revenue 750 @ 1.20 = 900");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @Test
    public void unknownBaseMeasureFailsClosed() {
        String bad = xmlSchema.replace(
            "name=\"Revenue (USD)\" measure=\"Revenue\"",
            "name=\"Revenue (USD)\" measure=\"Nope\"");
        assertNotEquals(xmlSchema, bad,
            "the CurrencyConversion measure replace must match the schema text");
        assertThrows(RuntimeException.class, () -> {
            Connection c = connect(bad);
            c.execute(c.parseQuery(
                "SELECT {[Measures].[Revenue]} ON COLUMNS"
                + " FROM [Monthly Revenue]"));
        }, "a CurrencyConversion on a missing measure must fail at load");
    }

    @Test
    public void legacyBackendRefusesConvertedLoad() {
        String prior = System.getProperty("mondrian.backend");
        System.setProperty("mondrian.backend", "legacy");
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        try {
            Connection c = connect(xmlSchema);
            // Currency conversion is not security-keyed, so the segment cache
            // is shared with the Calcite tests above; flush it so the legacy
            // load actually runs and trips the fail-closed gate.
            c.getCacheControl(null).flushSchemaCache();
            final Connection fc = c;
            assertThrows(RuntimeException.class, () -> fc.execute(fc.parseQuery(
                "SELECT {[Measures].[Revenue (USD)]} ON COLUMNS"
                + " FROM [Monthly Revenue]")),
                "a currency-converted load must fail closed on the legacy"
                + " backend");
            c.close();
        } finally {
            if (prior == null) {
                System.clearProperty("mondrian.backend");
            } else {
                System.setProperty("mondrian.backend", prior);
            }
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
