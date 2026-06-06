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
import mondrian.olap.Util;
import mondrian.rolap.RolapConnection;
import mondrian.rolap.RolapConnectionProperties;

import mondrian.schema.yaml.m4.M4XmlToYaml;
import mondrian.schema.yaml.m4.M4YamlToXml;

import org.apache.calcite.sql.dialect.H2SqlDialect;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #105 (TDD #4): full Mondrian → Calcite → H2 proof of bounded query
 * parameters. A schema declares a closed, typed {@code <QueryParameter>}.
 * Two connections carrying different {@code session.region} values resolve
 * different validated contexts; a parameter-bound filter then renders to
 * different SQL whose aggregate over real H2 data differs. An out-of-set
 * value is rejected at connect time. Run against both the XML and the
 * YAML-round-tripped form of the schema.
 */
public class QueryParameterH2EndToEndTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"qp_sales\"",
        "CREATE TABLE \"qp_sales\" (\"region\" VARCHAR(8),"
            + " \"amount\" INTEGER)",
        "INSERT INTO \"qp_sales\" VALUES"
            + " ('EAST',100),('EAST',50),('WEST',7),('WEST',3)",
    };

    private static final String SCHEMA =
        "<Schema name='QP' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='qp_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <QueryParameter name='region' type='String'"
        + " defaultValue='EAST'>\n"
        + "    <QueryParameterValue>EAST</QueryParameterValue>\n"
        + "    <QueryParameterValue>WEST</QueryParameterValue>\n"
        + "  </QueryParameter>\n"
        + "  <Dimension name='Region' table='qp_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='qp_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <FactLink dimension='Region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    private static final String H2_URL =
        "jdbc:h2:mem:qp_e2e;DB_CLOSE_DELAY=-1";

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            for (String sql : DDL) {
                st.execute(sql);
            }
        }
    }

    private static String schemaFor(String form) {
        return "yaml".equals(form)
            ? M4YamlToXml.toXml(M4XmlToYaml.toYaml(SCHEMA))
            : SCHEMA;
    }

    private static Connection connect(String catalog, String region) {
        Util.PropertyList props = new Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(),
            "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), catalog);
        if (region != null) {
            props.put("session.region", region);
        }
        return DriverManager.getConnection(props, null, null);
    }

    /** The connection harvests session.region into a validated context. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void connectionResolvesSessionParameter(String form) {
        String schema = schemaFor(form);
        Connection east = connect(schema, "EAST");
        Connection west = connect(schema, "WEST");
        Connection dflt = connect(schema, null);
        try {
            QueryParameterContext ce =
                ((RolapConnection) east).getQueryParameterContext();
            QueryParameterContext cw =
                ((RolapConnection) west).getQueryParameterContext();
            QueryParameterContext cd =
                ((RolapConnection) dflt).getQueryParameterContext();
            assertEquals("EAST", ce.resolve("region"));
            assertEquals("WEST", cw.resolve("region"));
            assertEquals("EAST", cd.resolve("region"), "missing -> default");
        } finally {
            east.close();
            west.close();
            dflt.close();
        }
    }

    /** An out-of-set session value is rejected at connect time. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void illegalSessionValueRejectedAtConnect(String form) {
        String schema = schemaFor(form);
        assertThrows(Throwable.class,
            () -> connect(schema, "NORTH").close(),
            "out-of-set session.region must fail to connect");
    }

    /** A parameter-bound filter renders to different SQL per context and the
     *  aggregate over H2 data differs — the full substitution proof. */
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void paramBoundAggregateDiffersByContext(String form)
        throws Exception
    {
        Connection east = connect(schemaFor(form), "EAST");
        Connection west = connect(schemaFor(form), "WEST");
        try {
            CalciteMondrianSchema cms = new CalciteMondrianSchema(
                jdbcDataSource(), "qp");
            CalciteSqlPlanner planner =
                new CalciteSqlPlanner(cms, H2SqlDialect.DEFAULT);

            String eastSql = sumSql(planner,
                ((RolapConnection) east).getQueryParameterContext());
            String westSql = sumSql(planner,
                ((RolapConnection) west).getQueryParameterContext());
            assertNotEquals(eastSql, westSql);
            assertTrue(eastSql.contains("EAST"),
                "EAST literal in: " + eastSql);
            assertTrue(westSql.contains("WEST"),
                "WEST literal in: " + westSql);

            assertEquals(150L, runScalar(eastSql), "EAST = 100+50");
            assertEquals(10L, runScalar(westSql), "WEST = 7+3");
        } finally {
            east.close();
            west.close();
        }
    }

    private static String sumSql(
        CalciteSqlPlanner planner, QueryParameterContext ctx)
    {
        PlannerRequest req = PlannerRequest.builder("qp_sales")
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column(null, "amount"),
                "total"))
            .addFilter(PlannerRequest.Filter.boundToParam(
                new PlannerRequest.Column(null, "region"), "region"))
            .paramContext(ctx)
            .build();
        return planner.plan(req);
    }

    private static javax.sql.DataSource jdbcDataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(H2_URL);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static long runScalar(String sql) throws Exception {
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql))
        {
            rs.next();
            return rs.getLong(1);
        }
    }
}
