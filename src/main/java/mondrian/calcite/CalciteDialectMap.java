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

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.HsqldbSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Maps Mondrian-reported JDBC database product names to Calcite
 * {@link SqlDialect} instances configured for SQL emission compatible with
 * Mondrian's FoodMart fixture.
 *
 * <p>Worktree #1 supports HSQLDB only. The returned dialect is a subclass
 * of {@link HsqldbSqlDialect} that forces double-quoting of all identifiers
 * — the FoodMart HSQLDB fixture is populated via Mondrian's legacy
 * {@code SqlQuery} builder, which also quotes every identifier, so the
 * physical tables are stored in case-sensitive (delimited) form. Without
 * quoting, HSQLDB case-folds the identifier to UPPER and fails to resolve.
 */
public final class CalciteDialectMap {
    private static final Logger LOGGER =
        Logger.getLogger(CalciteDialectMap.class);

    /**
     * Product names we have already logged "no Calcite dialect, falling
     * back to legacy" for. Prevents spamming the log every time a query
     * runs against an unsupported database.
     */
    private static final Set<String> WARNED_PRODUCTS =
        ConcurrentHashMap.newKeySet();

    private CalciteDialectMap() {}

    /**
     * Resolves a Calcite {@link SqlDialect} for the given JDBC
     * {@link DataSource} by reading the database product name directly from
     * {@link DatabaseMetaData}.
     *
     * <p>Returns {@code null} when the JDBC product does not map to a
     * supported Calcite dialect (e.g. H2). Callers must treat {@code null}
     * as a signal to fall back to the legacy Mondrian SQL builder for
     * <em>that</em> datasource; the global {@code mondrian.backend=calcite}
     * flag continues to apply to other datasources that do map cleanly.
     * A one-shot WARN line is emitted per unsupported product name so the
     * fallback is observable but doesn't spam.
     */
    public static SqlDialect forDataSource(DataSource ds) {
        if (ds == null) {
            throw new IllegalArgumentException("null DataSource");
        }
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName();
            SqlDialect dialect = forProductNameOrNull(product);
            if (dialect == null && WARNED_PRODUCTS.add(product)) {
                LOGGER.warn(
                    "No Calcite SqlDialect mapping for JDBC product '"
                    + product + "' — falling back to the legacy Mondrian "
                    + "backend for this datasource. Supported Calcite "
                    + "dialects: HSQLDB, PostgreSQL.");
            }
            return dialect;
        } catch (SQLException e) {
            throw new RuntimeException(
                "CalciteDialectMap.forDataSource: failed to read "
                + "DatabaseMetaData.getDatabaseProductName()", e);
        }
    }

    /**
     * Strict variant: throws {@link IllegalArgumentException} if the
     * product name is unknown. Retained for callers that genuinely require
     * a dialect to proceed (e.g. tests asserting we can handle a target
     * database). Production code paths should prefer
     * {@link #forProductNameOrNull(String)}.
     */
    public static SqlDialect forProductName(String product) {
        SqlDialect dialect = forProductNameOrNull(product);
        if (dialect == null) {
            throw new IllegalArgumentException(
                "No Calcite SqlDialect mapping for JDBC product '" + product
                + "'. Supported: HSQLDB, PostgreSQL. Extend "
                + "CalciteDialectMap to add more.");
        }
        return dialect;
    }

    /**
     * Returns the Calcite {@link SqlDialect} mapped to {@code product}, or
     * {@code null} if no mapping exists. Does NOT log — callers control
     * whether unsupported is normal (return null, fall back) or surprising
     * (throw).
     */
    public static SqlDialect forProductNameOrNull(String product) {
        if (product == null) {
            return null;
        }
        String p = product.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("hsql")) {
            return QUOTING_HSQLDB;
        }
        if (p.contains("postgres")) {
            return PostgresqlSqlDialect.DEFAULT;
        }
        return null;
    }

    /**
     * HSQLDB dialect whose {@code identifierNeedsQuote} returns true for
     * every identifier, so unparsed SQL matches the case-sensitive
     * delimited form used by the FoodMart fixture.
     */
    private static final SqlDialect QUOTING_HSQLDB =
        new HsqldbSqlDialect(
            HsqldbSqlDialect.DEFAULT_CONTEXT
                .withIdentifierQuoteString("\""))
        {
            @Override
            protected boolean identifierNeedsQuote(String val) {
                return true;
            }
        };
}
