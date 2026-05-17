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

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialectFactoryImpl;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link DialectFactory} that produces a {@link CalciteBackedDialect}
 * for any JDBC connection whose backend Calcite recognises but Mondrian
 * has no hand-written {@link Dialect} for.
 *
 * <p>Wired into {@link mondrian.spi.DialectManager}'s chain
 * <em>between</em> the registered-factories step and the
 * conservative {@link mondrian.spi.impl.JdbcDialectImpl} generic fallback,
 * so the resolution order is:
 *
 * <ol>
 *   <li>Hand-written Mondrian dialect (HsqldbDialect, MySqlDialect,
 *       OracleDialect, etc.) — registered via service discovery.</li>
 *   <li>Calcite-backed bridge — this class.</li>
 *   <li>Generic {@code JdbcDialectImpl} — conservative ANSI defaults.</li>
 * </ol>
 *
 * <p>Effect: backends with a Mondrian dialect (the long tail of legacy
 * DBs) keep their hand-rolled behaviour. Backends with no Mondrian
 * dialect but a Calcite {@link SqlDialect} (Snowflake, BigQuery,
 * Databricks/Spark, ClickHouse, DuckDB, Trino, etc.) get accurate
 * capability flags + per-backend SQL emission via the bridge. Truly
 * unknown backends still get the generic ANSI fallback as today.
 *
 * <p>The factory is silent on failure — returns {@code null} so the
 * chain continues to the next factory rather than throwing.
 *
 * <p>One-shot WARN per unrecognised product, so the silent-skip is
 * observable in production logs without spamming.
 */
public final class CalciteBackedDialectFactory implements DialectFactory {

    /** Public singleton — same instance used by tests and DialectManager. */
    public static final CalciteBackedDialectFactory INSTANCE =
        new CalciteBackedDialectFactory();

    private static final Logger LOGGER =
        LoggerFactory.getLogger(CalciteBackedDialectFactory.class);

    private static final Set<String> WARNED_PRODUCTS =
        ConcurrentHashMap.newKeySet();

    private CalciteBackedDialectFactory() {}

    @Override
    public Dialect createDialect(DataSource dataSource, Connection connection) {
        if (connection == null) {
            // Factory contract: null connection means we can't probe.
            // Returning null lets the chain move to the next factory.
            return null;
        }
        try {
            DatabaseMetaData md = connection.getMetaData();
            String product = md.getDatabaseProductName();

            // Step 1: hand-curated product → SqlDialect mapping.
            SqlDialect calciteDialect =
                CalciteDialectMap.forProductNameOrNull(product);

            // Step 2: Calcite's built-in factory (auto-detects MySQL,
            // Oracle, MS SQL, BigQuery, Snowflake, Redshift, ClickHouse,
            // DB2, Vertica, Spark, Derby, H2, DuckDB, etc.).
            if (calciteDialect == null) {
                SqlDialect autoDetected =
                    SqlDialectFactoryImpl.INSTANCE.create(md);
                if (autoDetected != null
                    && !(autoDetected instanceof AnsiSqlDialect))
                {
                    calciteDialect = autoDetected;
                }
            }

            // Step 3: Calcite doesn't know either → silent skip.
            if (calciteDialect == null) {
                if (WARNED_PRODUCTS.add(product)) {
                    LOGGER.warn(
                        "[mondrian-calcite] No Calcite SqlDialect for JDBC "
                        + "product '{}' — falling back to the generic "
                        + "Mondrian JdbcDialectImpl. Native-evaluator "
                        + "push-down decisions will use conservative ANSI "
                        + "defaults for this backend.", product);
                }
                return null;
            }

            return new CalciteBackedDialect(connection, calciteDialect);
        } catch (SQLException e) {
            // Can't probe metadata → silently skip. The chain handles it.
            LOGGER.debug(
                "[mondrian-calcite] CalciteBackedDialectFactory could not "
                + "probe DatabaseMetaData; falling through to next factory",
                e);
            return null;
        }
    }
}

// End CalciteBackedDialectFactory.java
