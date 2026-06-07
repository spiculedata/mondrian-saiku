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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end golden-number + row-security coverage for the comprehensive Bank
 * demo ({@code demo/Bank.mondrian.xml} over {@code demo/bank.sql}), run in BOTH
 * schema forms (XML and the YAML round-trip). Doubles as living documentation:
 * every assertion is a hand-verifiable number from {@code bank.sql}.
 */
public class BankShowcaseH2EndToEndTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_showcase;DB_CLOSE_DELAY=-1";

    private static String xmlSchema;
    private static String yamlSchema;

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        // Load the demo dataset the same way the product does — via RUNSCRIPT,
        // which handles comments and multi-row inserts natively.
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

    private static String schemaFor(String form) {
        return "yaml".equals(form) ? yamlSchema : xmlSchema;
    }

    static Connection connect(String form, String role, String tenant) {
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(),
            schemaFor(form));
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            props.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(props, null, null);
    }

    static Double scalar(Connection conn, String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void bridgeAndStatsGoldenNumbers(String form) {
        Connection c = connect(form, null, null);
        try {
            assertEquals(13000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Joint Accounts (Full Count)]"), 0.001,
                "full-count grand total de-dups the fan-out to 13000");
            assertEquals(13000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Joint Accounts (Weighted)]"), 0.001,
                "weighted grand total reconciles to 13000");
            assertEquals(1250.0, scalar(c,
                "SELECT {[Measures].[Median Balance]} ON COLUMNS"
                + " FROM [Account Statistics]"), 0.001,
                "median of 8 balances = midpoint(1000,1500) = 1250");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void tierAndDurationDimensions(String form) {
        Connection c = connect(form, null, null);
        try {
            // Balance tier bins: <1000 Small, <3000 Medium, else Large.
            // Large = accts with balance >= 3000: acct7 4000 + acct8 3000 = 7000.
            assertEquals(7000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS FROM [Accounts]\n"
                + "WHERE [Account].[Balance Tier].[Large]"), 0.001,
                "Large tier = balances >= 3000 (acct7+acct8)");
            // Account age (years) to as_of 2025-01-01: acct7 opened 2015 = 10y.
            assertEquals(4000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS FROM [Accounts]\n"
                + "WHERE [Account].[Age Years].[10]"), 0.001,
                "10-year-old account is acct7 (balance 4000)");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void predicateRowSecurityByTenant(String form) {
        // tenant 1 = LON accounts (1000+500+2000+4000=7500);
        // tenant 2 = LDS (300+1500+700+3000=5500); ungranted = 13000.
        Connection t1 = connect(form, "Tenant", "1");
        Connection t2 = connect(form, "Tenant", "2");
        Connection all = connect(form, null, null);
        try {
            String mdx = "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Accounts]";
            assertEquals(7500.0, scalar(t1, mdx), 0.001, "tenant 1 sees LON");
            assertEquals(5500.0, scalar(t2, mdx), 0.001, "tenant 2 sees LDS");
            assertEquals(13000.0, scalar(all, mdx), 0.001, "ungranted sees all");
        } finally {
            t1.close(); t2.close(); all.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void memberGrantRowSecurityBySegment(String form) {
        // Premium customers: alice, bob, erin, grace. Accounts with >=1 Premium
        // owner: 1(alice/bob),2(bob),3(alice/carol),4(erin/frank),6(grace/heidi),
        // 7(erin/grace). Account 5 (frank only=Standard) and 8 (heidi only) are
        // excluded. Full-count de-duped balance over visible-owner accounts:
        // 1000+500+300+2000+700+4000 = 8500.
        Connection premium = connect(form, "Premium", null);
        Connection all = connect(form, null, null);
        try {
            String mdx = "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Joint Accounts (Full Count)]";
            assertEquals(8500.0, scalar(premium, mdx), 0.001,
                "Premium role excludes Standard-only accounts (5,8)");
            assertEquals(13000.0, scalar(all, mdx), 0.001,
                "ungranted sees the full 13000");
        } finally {
            premium.close(); all.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
