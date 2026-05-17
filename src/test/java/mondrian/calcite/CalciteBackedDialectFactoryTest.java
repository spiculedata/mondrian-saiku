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
import mondrian.spi.DialectFactory;
import mondrian.spi.DialectManager;

import org.hsqldb.jdbc.jdbcDataSource;
import org.junit.After;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit + integration tests for {@link CalciteBackedDialectFactory} and
 * its wiring into {@link DialectManager}.
 *
 * <p>The factory's job: when no hand-written Mondrian {@code Dialect}
 * matches a connection but Calcite has a {@code SqlDialect} for the
 * backend, return a {@link CalciteBackedDialect} wrapping it. Otherwise
 * return {@code null} so the chain continues to the conservative
 * {@link mondrian.spi.impl.JdbcDialectImpl} fallback.
 *
 * <p>The integration test pins regression behaviour: HSQLDB (which
 * <em>does</em> have a hand-written {@code HsqldbDialect}) must continue
 * to resolve to that dialect, not the bridge. The bridge only fires for
 * backends with no registered Mondrian dialect.
 */
public class CalciteBackedDialectFactoryTest {

    private final String dbName = "factory_" + UUID.randomUUID()
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

    /**
     * The factory must produce a {@link CalciteBackedDialect} for any
     * connection whose backend Calcite recognises — HSQLDB is in
     * {@link CalciteDialectMap}'s hand-curated table, so the factory
     * resolves it. (Whether DialectManager <em>uses</em> the bridge for
     * HSQLDB depends on chain ordering — covered by the integration
     * test below.)
     */
    @Test
    public void createsBridgeForCalciteRecognisedBackend()
        throws SQLException
    {
        DataSource ds = ds();
        try (Connection c = ds.getConnection()) {
            DialectFactory f = CalciteBackedDialectFactory.INSTANCE;
            Dialect d = f.createDialect(ds, c);
            assertNotNull(
                "factory must wrap a Calcite-recognised backend "
                    + "(HSQLDB) in CalciteBackedDialect — got null",
                d);
            assertTrue(
                "expected CalciteBackedDialect — got " + d.getClass(),
                d instanceof CalciteBackedDialect);
        }
    }

    /**
     * Null connection → factory returns null. The factory contract is to
     * be silent when it can't probe; the upstream chain handles the case.
     */
    @Test
    public void returnsNullForNullConnection() {
        Dialect d = CalciteBackedDialectFactory.INSTANCE
            .createDialect(null, null);
        assertNull(
            "factory must return null for a null connection (chain-friendly)",
            d);
    }

    /**
     * The {@code INSTANCE} singleton is exposed publicly so tests and
     * DialectManager can both reference the same factory without
     * re-instantiation. Sanity check: it's non-null and stable.
     */
    @Test
    public void singletonIsStable() {
        assertNotNull(CalciteBackedDialectFactory.INSTANCE);
        assertTrue(
            "singleton must reference itself across calls",
            CalciteBackedDialectFactory.INSTANCE
                == CalciteBackedDialectFactory.INSTANCE);
    }

    /**
     * Regression test for the DialectManager wiring: HSQLDB has its own
     * hand-written {@code HsqldbDialect} registered via service discovery.
     * After inserting the Calcite-backed factory into the chain, HSQLDB
     * MUST continue to resolve to {@code HsqldbDialect}, not the bridge.
     * The bridge only fires for backends with no registered Mondrian
     * dialect — i.e. it's strictly additive, never displacing.
     */
    @Test
    public void dialectManagerStillPrefersRegisteredOverBridge()
        throws SQLException
    {
        DataSource ds = ds();
        try (Connection c = ds.getConnection()) {
            Dialect d = DialectManager.createDialect(ds, c);
            assertNotNull("DialectManager must always return a dialect", d);
            assertTrue(
                "HSQLDB must still resolve to the registered HsqldbDialect "
                    + "(not the Calcite bridge) — got " + d.getClass(),
                d.getClass().getSimpleName().contains("Hsqldb"));
        }
    }
}

// End CalciteBackedDialectFactoryTest.java
