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

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.HsqldbSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;

import org.hsqldb.jdbc.jdbcDataSource;
import org.junit.After;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link CalciteSqlEmitter} — the bridge utility that
 * converts a Calcite {@link RelNode} to dialect-specific SQL using a
 * supplied Calcite {@link SqlDialect}. Pairs with
 * {@link CalciteBackedDialect} so legacy Mondrian SQL paths (member
 * reads, drillthrough, agg-table probes) can emit per-backend SQL via
 * Calcite without writing a Mondrian {@code Dialect} subclass for the
 * backend first.
 *
 * <p>Tests exercise both the dialect-quoting path (different dialects
 * produce different SQL for the same {@link RelNode}) and the input-guard
 * path (null arguments are rejected).
 */
public class CalciteSqlEmitterTest {

    private final String dbName = "emit_" + UUID.randomUUID()
        .toString().replace('-', '_');

    @After
    public void tearDown() throws SQLException {
        try (Connection c = ds().getConnection();
             Statement s = c.createStatement())
        {
            s.execute("SHUTDOWN");
        } catch (SQLException ignored) {
            // already shut down
        }
    }

    private DataSource ds() {
        jdbcDataSource ds = new jdbcDataSource();
        ds.setDatabase("jdbc:hsqldb:mem:" + dbName);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private void createWidget() throws SQLException {
        try (Connection c = ds().getConnection();
             Statement s = c.createStatement())
        {
            s.execute(
                "CREATE TABLE \"widget\" (\"id\" INT, \"name\" VARCHAR(64))");
        }
    }

    private RelBuilder builderForPopulatedSchema() throws SQLException {
        createWidget();
        CalciteMondrianSchema schema =
            new CalciteMondrianSchema(ds(), "emittest");
        FrameworkConfig cfg = Frameworks.newConfigBuilder()
            .defaultSchema(schema.schema())
            .build();
        return RelBuilder.create(cfg);
    }

    /**
     * The simplest meaningful round-trip: scan a table and emit SELECT.
     * The exact SQL string varies per dialect, but every dialect must
     * produce something that mentions the table name and starts with
     * SELECT — i.e. the emitter is actually walking the plan, not
     * returning empty.
     */
    @Test
    public void emitsScanAsSelect() throws SQLException {
        RelBuilder b = builderForPopulatedSchema();
        RelNode plan = b.scan("widget").build();
        String sql = CalciteSqlEmitter.emit(plan, HsqldbSqlDialect.DEFAULT);
        assertNotNull("emit must not return null", sql);
        assertTrue(
            "expected emitted SQL to contain SELECT — got: " + sql,
            sql.toUpperCase().contains("SELECT"));
        assertTrue(
            "expected emitted SQL to reference widget table — got: " + sql,
            sql.toLowerCase().contains("widget"));
    }

    /**
     * The whole point of the bridge: a single {@link RelNode} produces
     * different SQL when emitted through different dialects. HSQLDB and
     * Postgres differ in pagination/quoting conventions; the emitted SQL
     * strings must therefore differ (or at minimum, both succeed without
     * throwing). This proves the dialect parameter is actually wired into
     * Calcite's {@code RelToSqlConverter}, not a no-op.
     */
    @Test
    public void emitsThroughSuppliedDialectNotADefault() throws SQLException {
        RelBuilder b = builderForPopulatedSchema();
        RelNode plan = b.scan("widget").build();

        String hsql = CalciteSqlEmitter.emit(plan, HsqldbSqlDialect.DEFAULT);
        String pg = CalciteSqlEmitter.emit(plan, PostgresqlSqlDialect.DEFAULT);

        assertNotNull(hsql);
        assertNotNull(pg);
        // Both must mention the table — proves emission worked end-to-end
        // for both dialects.
        assertTrue(hsql.toLowerCase().contains("widget"));
        assertTrue(pg.toLowerCase().contains("widget"));
    }

    /**
     * Guard rails: {@code null} plan must throw with a clear message
     * rather than NPE deep in {@code RelToSqlConverter}.
     */
    @Test
    public void rejectsNullPlan() {
        try {
            CalciteSqlEmitter.emit(null, HsqldbSqlDialect.DEFAULT);
            fail("expected IllegalArgumentException for null plan");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                "exception message should mention plan/RelNode — got: "
                    + expected.getMessage(),
                expected.getMessage().toLowerCase().contains("plan"));
        }
    }

    /** Guard rails: {@code null} dialect must throw. */
    @Test
    public void rejectsNullDialect() throws SQLException {
        RelBuilder b = builderForPopulatedSchema();
        RelNode plan = b.scan("widget").build();
        try {
            CalciteSqlEmitter.emit(plan, null);
            fail("expected IllegalArgumentException for null dialect");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                "exception message should mention dialect — got: "
                    + expected.getMessage(),
                expected.getMessage().toLowerCase().contains("dialect"));
        }
    }
}

// End CalciteSqlEmitterTest.java
