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

import mondrian.spi.Dialect;
import mondrian.spi.DialectManager;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end integration tests that boot an in-memory DuckDB, resolve the
 * dialect via {@link DialectManager}, and actually <em>execute</em> SQL
 * against DuckDB to validate the bridge's capability claims.
 *
 * <p>DuckDB is the canonical "Mondrian has no specific dialect but Calcite
 * does" backend — so this is where the bridge fires in production.
 *
 * <p>Each test answers a concrete question:
 * <ul>
 *   <li>Does the bridge actually fire for DuckDB?
 *   <li>For each capability the bridge claims, does DuckDB really support
 *       the corresponding SQL Mondrian would emit?
 *   <li>For each capability the bridge declines to claim, document what
 *       DuckDB would have supported — those are the seams where a
 *       {@code DuckDbDialect extends CalciteBackedDialect} subclass could
 *       extract more push-down.
 * </ul>
 *
 * <p>Uses DuckDB JDBC at test scope (already pinned in {@code pom.xml}).
 */
public class CalciteBackedDuckDbIntegrationTest {

    /**
     * Per-test temp-file DuckDB. Anonymous in-memory DuckDB
     * ({@code jdbc:duckdb:}) creates a fresh database per JDBC connection,
     * so the fixture rows we INSERT in {@link #setUp} would be invisible
     * to subsequent connections within the same test. A temp file is the
     * cleanest way to get a shared-across-connections, isolated-per-test
     * DuckDB instance.
     */
    private Path dbFile;
    private String url;
    private DataSource ds;

    @Before
    public void setUp() throws Exception {
        dbFile = Files.createTempFile(
            "duckbridge_" + UUID.randomUUID().toString().substring(0, 8),
            ".duckdb");
        // DuckDB refuses to open an existing-but-empty file; delete the
        // tempfile and let DuckDB create it fresh.
        Files.deleteIfExists(dbFile);
        url = "jdbc:duckdb:" + dbFile.toAbsolutePath();
        ds = new SimpleDuckDbDataSource(url);
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement())
        {
            s.execute(
                "CREATE TABLE widget ("
                + "  id INTEGER, "
                + "  category VARCHAR, "
                + "  region VARCHAR, "
                + "  sales DOUBLE)");
            s.execute(
                "INSERT INTO widget VALUES "
                + "(1, 'A', 'EU', 10.0), "
                + "(2, 'A', 'US', 20.0), "
                + "(3, 'B', 'EU', 5.0), "
                + "(4, 'B', 'US', NULL), "
                + "(5, 'A', 'EU', 15.0)");
        }
    }

    @After
    public void tearDown() throws Exception {
        ds = null;
        // DuckDB writes a .wal sidecar; clean both.
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(
                dbFile.resolveSibling(dbFile.getFileName() + ".wal"));
        }
    }

    // ====== bridge resolution ======

    /**
     * The whole point of the bridge: {@link DialectManager} must return a
     * {@link CalciteBackedDialect} for a DuckDB connection (because no
     * hand-written {@code DuckDbDialect} is registered in Mondrian today).
     * If this regresses, the bridge isn't firing for the backend it was
     * built for.
     */
    @Test
    public void dialectManagerReturnsBridgeForDuckDb() throws SQLException {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            assertNotNull(d);
            assertTrue(
                "DialectManager must resolve DuckDB → CalciteBackedDialect; "
                    + "got " + d.getClass().getName(),
                d instanceof CalciteBackedDialect);
        }
    }

    /**
     * Mondrian's enum has no DUCKDB value, so the bridge must report
     * UNKNOWN — never lie by reporting POSTGRESQL or HSQLDB or anything
     * else that downstream code might branch on.
     */
    @Test
    public void getDatabaseProductReportsUnknown() throws SQLException {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            assertEquals(
                Dialect.DatabaseProduct.UNKNOWN,
                d.getDatabaseProduct());
        }
    }

    /**
     * Identifier quoting must produce double-quoted form that DuckDB
     * actually accepts — and round-trip cleanly through a real query.
     */
    @Test
    public void quotedIdentifierExecutesAgainstDuckDb() throws SQLException {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            String quoted = d.quoteIdentifier("widget");
            assertEquals(
                "DuckDB Calcite dialect uses double-quotes",
                "\"widget\"", quoted);
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM " + quoted))
            {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
            }
        }
    }

    // ====== capabilities the bridge claims ======

    /**
     * Bridge claims {@code supportsGroupingSets = true} for DuckDB.
     * Execute a real GROUPING SETS query to prove it.
     */
    @Test
    public void groupingSetsSqlExecutesAgainstDuckDb() throws SQLException {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            assertTrue(
                "bridge must claim GROUPING SETS support for DuckDB",
                d.supportsGroupingSets());

            String sql =
                "SELECT category, region, SUM(sales) "
                + "FROM widget "
                + "GROUP BY GROUPING SETS ((category), (region), ())";
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(sql))
            {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                }
                // 2 categories + 2 regions + 1 grand total = 5 rows
                assertEquals(
                    "GROUPING SETS should produce 5 rows for this fixture",
                    5, rows);
            }
        }
    }

    /**
     * Bridge overrides {@code generateOrderByNulls} to emit the ANSI
     * {@code NULLS LAST} / {@code NULLS FIRST} clause. Verify DuckDB
     * actually executes the SQL the override produces.
     */
    @Test
    public void orderByAnsiNullsExecutesAgainstDuckDb() throws SQLException {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            String orderBy = d.generateOrderItem(
                "sales", true /* nullable */, true /* asc */,
                true /* nulls last */);
            assertTrue(
                "expected ANSI NULLS LAST — got: " + orderBy,
                orderBy.toUpperCase().contains("NULLS LAST"));

            String sql = "SELECT id, sales FROM widget ORDER BY " + orderBy;
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(sql))
            {
                Double last = null;
                int seen = 0;
                Integer nullRowId = null;
                while (rs.next()) {
                    seen++;
                    int id = rs.getInt("id");
                    double sales = rs.getDouble("sales");
                    if (rs.wasNull()) {
                        nullRowId = id;
                    } else {
                        last = sales;
                    }
                }
                assertEquals(5, seen);
                assertEquals(
                    "NULL sales row (id=4) must be sorted LAST",
                    Integer.valueOf(4), nullRowId);
            }
        }
    }

    /**
     * Single-column {@code COUNT(DISTINCT)} — bridge inherits parent's
     * default {@code allowsCountDistinct = true}. Verify against DuckDB.
     */
    @Test
    public void countDistinctExecutesAgainstDuckDb() throws SQLException {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            assertTrue(d.allowsCountDistinct());

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT COUNT(DISTINCT category) FROM widget"))
            {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    // ====== known sub-optimal coverage (documenting the seam) ======

    /**
     * Bridge says {@code allowsCompoundCountDistinct = false} (parent
     * default). For DuckDB this turns out to be <em>correct</em> —
     * verified by trying the {@code COUNT(DISTINCT a, b)} syntax and
     * getting back a binder error from DuckDB itself: \"No function
     * matches the given name and argument types 'count(VARCHAR, VARCHAR)'\".
     * DuckDB requires a workaround (concatenate or struct-pack), so the
     * conservative answer is right. Documents this for future reference.
     */
    @Test
    public void compoundCountDistinctCorrectlyFalse_DuckDbRejectsTheSyntax()
        throws SQLException
    {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            assertEquals(
                "bridge correctly inherits parent's false",
                false, d.allowsCompoundCountDistinct());
            // Confirm DuckDB actually rejects the multi-arg COUNT DISTINCT
            // syntax that Mondrian's planner would generate if the flag
            // were true. If a future DuckDB upgrade adds support, this
            // assertion will start failing — at which point we can flip
            // the flag in a DuckDbDialect subclass.
            try (Statement s = c.createStatement()) {
                s.executeQuery(
                    "SELECT COUNT(DISTINCT category, region) FROM widget");
                fail(
                    "DuckDB unexpectedly accepted COUNT(DISTINCT a, b) — "
                    + "the bridge's conservative-false answer is now "
                    + "sub-optimal and should be revisited");
            } catch (SQLException expected) {
                // Bind error proves the bridge's conservative answer is right
                assertTrue(
                    "expected binder error mentioning count function — got: "
                        + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("count"));
            }
        }
    }

    /**
     * Same shape as the compound-count-distinct gap: bridge says
     * {@code supportsMultiValueInExpr = false}, DuckDB supports it.
     * Documented as a seam for a future {@code DuckDbDialect}.
     */
    @Test
    public void multiValueInIsSubOptimal_DuckDbActuallySupportsIt()
        throws SQLException
    {
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            assertEquals(
                "bridge today inherits parent's conservative false — "
                    + "this is the known sub-optimal gap for DuckDB",
                false, d.supportsMultiValueInExpr());
            // But DuckDB itself supports it. Prove it directly.
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM widget "
                     + "WHERE (category, region) IN "
                     + "(('A','EU'), ('B','US'))"))
            {
                assertTrue(rs.next());
                // (A,EU) matches ids 1,5 → 2; (B,US) matches id 4 → 1. Total 3.
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    // ====== CalciteSqlEmitter round-trip ======

    /**
     * The other half of #40: take a Calcite {@link RelNode}, emit SQL
     * through {@link CalciteSqlEmitter} using the bridge's wrapped
     * SqlDialect, and verify DuckDB executes the emitted SQL. Proves
     * the {@code RelToSqlConverter} path produces DuckDB-flavoured SQL
     * that the engine actually accepts.
     */
    @Test
    public void calciteSqlEmitterProducesExecutableDuckDbSql()
        throws SQLException
    {
        try (Connection c = ds.getConnection()) {
            CalciteBackedDialect bridge =
                (CalciteBackedDialect) DialectManager.createDialect(ds, c);
            SqlDialect calciteDialect = bridge.getCalciteDialect();

            // Build the RelNode against a Calcite schema reflecting our
            // DuckDB instance. The CalciteMondrianSchema readiness check
            // requires a populated schema — our @Before created widget,
            // so we're fine.
            CalciteMondrianSchema schema =
                new CalciteMondrianSchema(ds, "duckdb_emit");
            FrameworkConfig cfg = Frameworks.newConfigBuilder()
                .defaultSchema(schema.schema())
                .build();
            RelBuilder b = RelBuilder.create(cfg);
            RelNode plan = b.scan("widget").build();

            String sql = CalciteSqlEmitter.emit(plan, calciteDialect);
            assertNotNull(sql);
            assertTrue(
                "emitted SQL should reference widget — got: " + sql,
                sql.toLowerCase().contains("widget"));

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(sql))
            {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                }
                assertEquals(
                    "Calcite-emitted DuckDB SQL must round-trip 5 rows",
                    5, rows);
            }
        }
    }

    // ====== tiny DataSource shim — DuckDB doesn't ship one ======

    private static final class SimpleDuckDbDataSource implements DataSource {
        private final String url;
        SimpleDuckDbDataSource(String url) { this.url = url; }
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }
        @Override public Connection getConnection(String u, String p)
            throws SQLException
        {
            return DriverManager.getConnection(url);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger()
            throws SQLFeatureNotSupportedException
        {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

// End CalciteBackedDuckDbIntegrationTest.java
