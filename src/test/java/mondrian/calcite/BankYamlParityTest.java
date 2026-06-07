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
import mondrian.schema.yaml.XmlSchemaToYaml;
import mondrian.schema.yaml.YamlSchemaConverter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The committed {@code demo/Bank.yaml} is the faithful YAML form of
 * {@code demo/Bank.mondrian.xml}, so the comprehensive showcase can be shown in
 * BOTH formats. Two guards:
 * <ol>
 *   <li><b>Drift</b>: the committed YAML equals the CLI conversion
 *       ({@code XmlSchemaToYaml.toYaml}) of the XML — regenerate the YAML if the
 *       XML changes ({@code mondrian schema-cli to-yaml demo/Bank.mondrian.xml}).</li>
 *   <li><b>Functional</b>: the committed YAML, converted back to XML and loaded
 *       into Mondrian over {@code demo/bank.sql}, returns the same golden total
 *       as the XML — proving every new construct survives the YAML form.</li>
 * </ol>
 */
public class BankYamlParityTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_yaml_parity;DB_CLOSE_DELAY=-1";

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
    }

    @Test
    public void committedYamlMatchesCliConversionOfXml() throws Exception {
        String xml = new String(
            Files.readAllBytes(Path.of("demo/Bank.mondrian.xml")));
        String committedYaml = new String(
            Files.readAllBytes(Path.of("demo/Bank.yaml")));
        assertEquals(XmlSchemaToYaml.toYaml(xml).trim(), committedYaml.trim(),
            "demo/Bank.yaml is stale — regenerate with"
            + " 'mondrian schema-cli to-yaml demo/Bank.mondrian.xml"
            + " -o demo/Bank.yaml'");
    }

    @Test
    public void committedYamlLoadsAndReturnsGoldenTotal() throws Exception {
        // Convert the committed YAML back to XML the same way the CLI does, then
        // run it through Mondrian — proving the YAML form is fully functional.
        String committedYaml = new String(
            Files.readAllBytes(Path.of("demo/Bank.yaml")));
        String xmlFromYaml = YamlSchemaConverter.toXml(committedYaml);
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), xmlFromYaml);
        Connection conn = DriverManager.getConnection(props, null, null);
        try {
            Query q = conn.parseQuery(
                "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Joint Accounts (Full Count)]");
            Result r = conn.execute(q);
            Object v = r.getCell(new int[]{0}).getValue();
            r.close();
            assertEquals(13000.0, ((Number) v).doubleValue(), 0.001,
                "the committed YAML schema must produce the same 13000 total");
        } finally {
            conn.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
