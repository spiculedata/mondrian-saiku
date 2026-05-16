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

import org.hsqldb.jdbc.jdbcDataSource;
import org.junit.After;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Regression tests for issue #30 — Calcite's {@link
 * org.apache.calcite.adapter.jdbc.JdbcSchema} caches its table list +
 * per-table column lists lazily on first access, and once frozen the
 * cached state lives for the JVM lifetime. If the backing JDBC source
 * hasn't finished initialising (H2 .mv.db lazy load, DBCP pool warm-up,
 * etc.) when Calcite first probes, every subsequent
 * {@code RelBuilder.field(...)} call throws
 * {@code "field not found; input fields are: []"}.
 *
 * <p>The fix lives in two places:
 * <ol>
 *   <li>{@link CalciteMondrianSchema} eagerly validates the JdbcSchema
 *       at construction time and throws
 *       {@link CalciteMondrianSchema.JdbcSchemaNotReadyException} if it
 *       looks unusable (empty table list, or sampled user tables report
 *       zero columns).</li>
 *   <li>{@link CalcitePlannerCache} catches that exception, declines to
 *       cache, and returns {@code null} so callers fall back to the
 *       legacy SQL builder for <em>that call only</em>. Subsequent calls
 *       retry the build — by which point the underlying database is
 *       usually ready.</li>
 * </ol>
 *
 * <p>These tests reproduce the empty-schema window with a HSQLDB
 * in-memory database that starts empty, then has a user table added.
 * Pre-fix, the second call returns a cached planner backed by an empty
 * Calcite schema. Post-fix, the second call rebuilds and succeeds.
 */
public class CalciteSchemaReadinessTest {

    /** Per-test in-memory HSQLDB name — keeps test classes isolated. */
    private final String dbName = "readiness_" + UUID.randomUUID()
        .toString().replace('-', '_');

    @After
    public void tearDown() throws SQLException {
        // Wipe the planner cache + any per-test HSQLDB content so the
        // next test starts from a known state.
        CalcitePlannerCache.clear();
        try (Connection c = ds().getConnection();
             Statement s = c.createStatement())
        {
            s.execute("SHUTDOWN");
        } catch (SQLException ignored) {
            // already shut down — fine
        }
    }

    private DataSource ds() {
        jdbcDataSource ds = new jdbcDataSource();
        ds.setDatabase("jdbc:hsqldb:mem:" + dbName);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Empty database (no user tables) → constructor must raise
     * {@link CalciteMondrianSchema.JdbcSchemaNotReadyException} rather
     * than silently cache a poisoned schema.
     */
    @Test
    public void emptySchemaConstructorThrows() {
        try {
            new CalciteMondrianSchema(ds(), "test");
            fail(
                "expected JdbcSchemaNotReadyException for an empty "
                + "JdbcSchema, but construction succeeded");
        } catch (CalciteMondrianSchema.JdbcSchemaNotReadyException expected) {
            // ok — message should mention zero tables / system tables
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * Populated database (one user table with two columns) →
     * constructor succeeds and the reflected schema exposes the table.
     */
    @Test
    public void populatedSchemaConstructorSucceeds() throws SQLException {
        createTable("widget", "id INT", "year INT");
        CalciteMondrianSchema s = new CalciteMondrianSchema(ds(), "test");
        assertNotNull(s.schema());
        assertNotNull(
            "expected widget table to be reflected; saw "
                + s.schema().getTableNames(),
            findTableCi(s.schema(), "widget"));
    }

    /**
     * Planner cache integration — the fix's whole point:
     * <ol>
     *   <li>First {@code plannerFor(ds)} against empty DB → returns
     *       {@code null} (transient "not ready"). MUST NOT cache.</li>
     *   <li>Create the user table.</li>
     *   <li>Second {@code plannerFor(ds)} → builds a fresh planner
     *       reflecting the now-populated schema, caches it.</li>
     * </ol>
     */
    @Test
    public void plannerCacheRetriesAfterNotReady() throws SQLException {
        CalcitePlannerCache.clear();

        // Initial state: no tables → must return null, no cache entry.
        DataSource ds = ds();
        CalciteSqlPlanner first = CalcitePlannerCache.plannerFor(ds);
        assertNull(
            "first call against empty DB should return null (transient "
            + "not-ready) — got " + first,
            first);
        assertEquals(
            "no planner should be cached after a not-ready failure",
            0, CalcitePlannerCache.size());

        // Add a user table.
        createTable("widget", "id INT", "year INT");

        // Second call must rebuild successfully — the not-ready key was
        // not poisoned into UNSUPPORTED.
        CalciteSqlPlanner second = CalcitePlannerCache.plannerFor(ds);
        assertNotNull(
            "second call after schema populated should succeed",
            second);
        assertEquals(
            "successful build should be cached",
            1, CalcitePlannerCache.size());
    }

    /**
     * Belt-and-braces: even if validation passes for a populated
     * schema, an information-schema-only view (HSQLDB / Postgres) must
     * still throw — we expect to see at least one user table.
     */
    @Test
    public void systemTablesOnlyTreatedAsNotReady() {
        // No user table created — INFORMATION_SCHEMA is the only thing
        // HSQLDB exposes by default. Confirms our system-table filter
        // doesn't mask the empty-user-schema condition.
        try {
            new CalciteMondrianSchema(ds(), "test");
            fail(
                "expected JdbcSchemaNotReadyException — INFORMATION_SCHEMA "
                + "is not a user schema");
        } catch (CalciteMondrianSchema.JdbcSchemaNotReadyException expected) {
            // ok
        }
    }

    // ----- helpers -----

    private void createTable(String name, String... colDefs)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("CREATE TABLE \"")
            .append(name).append("\" (");
        for (int i = 0; i < colDefs.length; i++) {
            if (i > 0) sql.append(", ");
            sql.append(colDefs[i]);
        }
        sql.append(")");
        try (Connection c = ds().getConnection();
             Statement s = c.createStatement())
        {
            s.execute(sql.toString());
        }
    }

    private static org.apache.calcite.schema.Table findTableCi(
        org.apache.calcite.schema.SchemaPlus schema, String name)
    {
        for (String t : schema.getTableNames()) {
            if (t.equalsIgnoreCase(name)) {
                return schema.getTable(t);
            }
        }
        return null;
    }
}

// End CalciteSchemaReadinessTest.java
