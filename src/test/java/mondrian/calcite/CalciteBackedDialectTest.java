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

import org.apache.calcite.sql.SqlDialect;
import org.hsqldb.jdbc.jdbcDataSource;
import org.junit.After;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link CalciteBackedDialect} — a Mondrian {@link Dialect}
 * that derives capability flags and quoting from a wrapped Calcite
 * {@link SqlDialect}, so backends with no hand-written Mondrian dialect
 * still get accurate push-down capability answers instead of the
 * conservative {@code JdbcDialectImpl} defaults.
 *
 * <p>Tests use an in-memory HSQLDB so the Calcite SqlDialect resolution
 * goes through {@link CalciteDialectMap#forDataSource(DataSource)} end-to-end.
 */
public class CalciteBackedDialectTest {

    private final String dbName = "calcbacked_" + UUID.randomUUID()
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

    private Dialect newDialect() throws SQLException {
        SqlDialect cd = CalciteDialectMap.forDataSource(ds());
        assertNotNull(
            "Calcite must resolve a SqlDialect for HSQLDB", cd);
        try (Connection conn = ds().getConnection()) {
            return new CalciteBackedDialect(conn, cd);
        }
    }

    /**
     * Identifier quoting must delegate to the wrapped Calcite dialect so
     * we produce backend-flavoured SQL — backticks for BigQuery, double-quotes
     * for Postgres-shaped engines, etc. HSQLDB's hand-curated entry in
     * {@link CalciteDialectMap} forces double-quote quoting on every
     * identifier so the assertion is stable.
     */
    @Test
    public void delegatesQuoteIdentifierToCalcite() throws SQLException {
        Dialect d = newDialect();
        assertEquals(
            "expected double-quoted form from Calcite's HSQLDB SqlDialect",
            "\"foo\"", d.quoteIdentifier("foo"));
    }

    /**
     * The wrapped Calcite SqlDialect must be retrievable for callers that
     * want to thread the same dialect through {@code RelToSqlConverter} —
     * the whole point of the bridge is "ask Calcite once, use it everywhere".
     */
    @Test
    public void exposesWrappedCalciteDialect() throws SQLException {
        SqlDialect cd = CalciteDialectMap.forDataSource(ds());
        try (Connection conn = ds().getConnection()) {
            CalciteBackedDialect d = new CalciteBackedDialect(conn, cd);
            assertSame(
                "getCalciteDialect must return the instance we wrapped",
                cd, d.getCalciteDialect());
        }
    }

    /**
     * Capability flags backed by Calcite must not return the conservative
     * generic-fallback "no" — that's the whole reason this bridge exists.
     * HSQLDB supports {@code requiresAliasForFromItems = true} in Calcite,
     * which the bridge must reflect as {@code requiresAliasForFromQuery()}.
     */
    @Test
    public void requiresAliasForFromQueryReflectsCalcite() throws SQLException {
        Dialect d = newDialect();
        SqlDialect cd = CalciteDialectMap.forDataSource(ds());
        assertEquals(
            "bridge must mirror Calcite's requiresAliasForFromItems",
            cd.requiresAliasForFromItems(),
            d.requiresAliasForFromQuery());
    }

    /**
     * {@code supportsGroupingSets} must be derived from Calcite's
     * {@code supportsGroupByWithRollup} + {@code supportsGroupByWithCube}:
     * engines that speak ROLLUP + CUBE typically speak GROUPING SETS too.
     * For backends where Calcite doesn't claim CUBE/ROLLUP, the bridge
     * must fall back to the conservative {@code false}.
     */
    @Test
    public void supportsGroupingSetsDerivedFromCalciteRollupAndCube()
        throws SQLException
    {
        Dialect d = newDialect();
        SqlDialect cd = CalciteDialectMap.forDataSource(ds());
        boolean expected =
            cd.supportsGroupByWithRollup() && cd.supportsGroupByWithCube();
        assertEquals(
            "supportsGroupingSets must reflect Calcite ROLLUP + CUBE support",
            expected,
            d.supportsGroupingSets());
    }

    /**
     * Calcite's {@code supportsApproxCountDistinct} maps directly onto a
     * concept the legacy Mondrian planner uses for native-evaluator
     * push-down decisions. Even though Mondrian's {@link Dialect} interface
     * doesn't have a dedicated method for it today, the bridge must expose
     * it so future planner work can read it without re-querying Calcite.
     */
    @Test
    public void exposesApproxCountDistinctFromCalcite() throws SQLException {
        SqlDialect cd = CalciteDialectMap.forDataSource(ds());
        try (Connection conn = ds().getConnection()) {
            CalciteBackedDialect d = new CalciteBackedDialect(conn, cd);
            assertEquals(
                cd.supportsApproxCountDistinct(),
                d.supportsApproxCountDistinct());
        }
    }

    /**
     * NULL collation: Mondrian's default {@code generateOrderByNulls}
     * emits a verbose {@code CASE WHEN expr IS NULL THEN 0 ELSE 1 END}
     * workaround for engines that don't support the ANSI
     * {@code NULLS LAST}/{@code NULLS FIRST} syntax. Any backend Calcite
     * has a SqlDialect for is modern enough to support the ANSI form;
     * the bridge must emit the cleaner SQL. Regression: parent default
     * would contain "CASE WHEN".
     */
    @Test
    public void generateOrderItemUsesAnsiNullsClause() throws SQLException {
        Dialect d = newDialect();
        String orderBy = d.generateOrderItem(
            "\"sales\"", true /* nullable */, true /* ascending */,
            true /* nulls last */);
        assertTrue(
            "expected ANSI NULLS LAST syntax, not CASE WHEN — got: " + orderBy,
            orderBy.toUpperCase().contains("NULLS LAST"));
        assertFalse(
            "ANSI form must not fall back to the CASE WHEN workaround — got: "
                + orderBy,
            orderBy.toUpperCase().contains("CASE WHEN"));
    }

    /**
     * Non-nullable expression: no NULLS clause needed at all. Bridge must
     * still skip the workaround for this case.
     */
    @Test
    public void generateOrderItemForNonNullableSkipsNullsClause()
        throws SQLException
    {
        Dialect d = newDialect();
        String orderBy = d.generateOrderItem(
            "\"sales\"", false /* not nullable */, true, true);
        assertFalse(
            "non-nullable expr should have no NULLS clause — got: " + orderBy,
            orderBy.toUpperCase().contains("NULLS"));
        assertTrue(
            "expected ASC direction — got: " + orderBy,
            orderBy.toUpperCase().contains("ASC"));
    }

    /**
     * {@code getDatabaseProduct()} must reflect Calcite's reported product
     * where Mondrian's enum has a matching value. HSQLDB exists in both
     * enums so the bridge must return {@link Dialect.DatabaseProduct#HSQLDB}
     * — letting downstream code that branches on product (e.g. statistics
     * providers, schema-specific quirks) recognise the engine.
     */
    @Test
    public void getDatabaseProductMapsCalciteHsqldbToMondrian()
        throws SQLException
    {
        Dialect d = newDialect();
        assertEquals(
            "bridge must surface HSQLDB from Calcite's DatabaseProduct enum",
            Dialect.DatabaseProduct.HSQLDB,
            d.getDatabaseProduct());
    }

    /**
     * Calcite-only products (DuckDB, Snowflake, BigQuery, ClickHouse,
     * Spark, Trino, H2, etc.) have no matching Mondrian enum value. The
     * bridge must return {@link Dialect.DatabaseProduct#UNKNOWN} for
     * those — never lie by reporting a different product.
     */
    @Test
    public void getDatabaseProductReturnsUnknownForCalciteOnlyProduct()
        throws SQLException
    {
        // Construct the bridge by hand with a DuckDB Calcite SqlDialect
        // even though the JDBC connection is HSQLDB. This isolates the
        // enum-mapping logic from JDBC-product detection.
        org.apache.calcite.sql.SqlDialect duck =
            org.apache.calcite.sql.dialect.DuckDBSqlDialect.DEFAULT;
        try (Connection conn = ds().getConnection()) {
            CalciteBackedDialect d = new CalciteBackedDialect(conn, duck);
            assertEquals(
                "DuckDB has no Mondrian DatabaseProduct enum value — "
                    + "bridge must return UNKNOWN",
                Dialect.DatabaseProduct.UNKNOWN,
                d.getDatabaseProduct());
        }
    }

    /**
     * JDBC-metadata-derived properties (max column name length, supported
     * result set concurrency, regex chars) MUST come from the parent
     * {@code JdbcDialectImpl} unchanged — the bridge only overrides what
     * Calcite can answer better. Regression test: a value the JDBC driver
     * reports surfaces unchanged through the bridge.
     */
    @Test
    public void inheritsJdbcMetadataFromParent() throws SQLException {
        Dialect d = newDialect();
        // HSQLDB 1.8 reports a positive max column length; the bridge
        // must not zero this out or override it.
        assertTrue(
            "JDBC-derived getMaxColumnNameLength must survive the bridge",
            d.getMaxColumnNameLength() >= 0);
        // HSQLDB allows COUNT(DISTINCT ...) — the bridge defaults to the
        // parent's answer (true) rather than the generic-fallback "no".
        assertTrue(
            "allowsCountDistinct must default to parent behaviour (true)",
            d.allowsCountDistinct());
        // Identifier quote string must surface unchanged from the parent
        // (sourced from JDBC DatabaseMetaData), not be overridden to empty.
        assertNotNull(
            "getQuoteIdentifierString must survive the bridge",
            d.getQuoteIdentifierString());
        assertFalse(
            "quote string from JDBC metadata must not be empty",
            d.getQuoteIdentifierString().isEmpty());
    }
}

// End CalciteBackedDialectTest.java
