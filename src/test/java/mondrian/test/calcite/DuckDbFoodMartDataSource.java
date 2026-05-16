/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.test.calcite;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * DuckDB-backed FoodMart {@link DataSource} for the Calcite harness.
 * Activated when {@link HarnessBackend#current()} returns
 * {@link HarnessBackend#DUCKDB}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>System property {@code mondrian.foodmart.jdbcURL} (the same
 *       property Mondrian's own connect string honours) — e.g.
 *       {@code jdbc:duckdb:/tmp/foodmart.duckdb}. Falls back to env var
 *       {@code MONDRIAN_FOODMART_JDBCURL} if absent.</li>
 * </ul>
 *
 * <p>Uses {@link DriverManager} under the hood — the {@code org.duckdb}
 * driver is registered via JDBC's SPI on first
 * {@code DriverManager.getConnection} call.
 */
public final class DuckDbFoodMartDataSource {

    public static final String URL_SYSPROP = "mondrian.foodmart.jdbcURL";
    public static final String URL_ENV = "MONDRIAN_FOODMART_JDBCURL";

    private DuckDbFoodMartDataSource() {}

    public static DataSource create() {
        String url = System.getProperty(URL_SYSPROP);
        if (url == null || url.isEmpty()) {
            url = System.getenv(URL_ENV);
        }
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException(
                "DuckDB harness backend selected but no JDBC URL set. "
                + "Set -D" + URL_SYSPROP + "=jdbc:duckdb:/path/to/db "
                + "or the " + URL_ENV + " environment variable.");
        }
        return new DriverManagerShim(url);
    }

    /**
     * {@link DataSource} backed by a single root DuckDB Connection per
     * (JVM, URL) pair. Subsequent {@link #getConnection()} calls return
     * a {@code duplicate()} of the root — DuckDB allows multiple JDBC
     * connections to the same file ONLY when they share the same
     * underlying database instance, which {@code duplicate()} guarantees.
     *
     * <p>The harness opens multiple connections in quick succession
     * (Mondrian's schema-cache flush, then the SqlCapture-wrapped query
     * connection). Without this singleton, each
     * {@code DriverManager.getConnection(url)} would create a NEW DuckDB
     * instance and fail with "Can't open a connection to same database
     * file with a different configuration".
     */
    static final class DriverManagerShim implements DataSource {
        private final String url;
        private PrintWriter logWriter;
        private int loginTimeout;
        // Lazily-initialised, never closed during JVM lifetime.
        private static volatile Connection root;

        DriverManagerShim(String url) {
            this.url = url;
        }

        private Connection rootConnection() throws SQLException {
            Connection r = root;
            if (r == null) {
                synchronized (DriverManagerShim.class) {
                    r = root;
                    if (r == null) {
                        r = DriverManager.getConnection(url);
                        root = r;
                    }
                }
            }
            return r;
        }

        @Override public Connection getConnection() throws SQLException {
            // DuckDB JDBC's duplicate() returns a NEW Connection sharing
            // the same underlying database instance — safe to close
            // independently of the root.
            Connection r = rootConnection();
            try {
                return (Connection) r.getClass()
                    .getMethod("duplicate").invoke(r);
            } catch (ReflectiveOperationException e) {
                throw new SQLException(
                    "DuckDB Connection.duplicate() not available — "
                    + "expected on DuckDBConnection in 1.x drivers.", e);
            }
        }
        @Override public Connection getConnection(String u, String p)
            throws SQLException
        {
            // DuckDB ignores credentials but Mondrian's RolapConnection
            // passes HSQLDB-flavoured "sa" defaults — drop them and
            // route through the same duplicate-of-root mechanism.
            return getConnection();
        }
        @Override public PrintWriter getLogWriter() { return logWriter; }
        @Override public void setLogWriter(PrintWriter out) {
            this.logWriter = out;
        }
        @Override public void setLoginTimeout(int seconds) {
            this.loginTimeout = seconds;
        }
        @Override public int getLoginTimeout() { return loginTimeout; }
        @Override public Logger getParentLogger()
            throws SQLFeatureNotSupportedException
        {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException(
                "DriverManagerShim does not wrap " + iface);
        }
        @Override public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}

// End DuckDbFoodMartDataSource.java
