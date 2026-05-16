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
import org.apache.calcite.sql.SqlDialectFactoryImpl;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.sql.dialect.HsqldbSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.dialect.DuckDBSqlDialect;
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
 * <p>Resolution order:
 * <ol>
 *   <li>Hand-curated entries for HSQLDB and PostgreSQL — these get special
 *       dialect subclasses that the legacy code paths assumed (HSQLDB needs
 *       forced quoting because the FoodMart fixture is populated via
 *       Mondrian's legacy SqlQuery builder, which quotes every identifier;
 *       Postgres uses Calcite's stock {@code PostgresqlSqlDialect.DEFAULT}).
 *   <li>Calcite's built-in {@link SqlDialectFactoryImpl}, which auto-resolves
 *       MySQL, Oracle, MS SQL Server, BigQuery, Snowflake, Redshift,
 *       ClickHouse, DB2, Vertica, Hive, Spark, Derby, H2, DuckDB, and more.
 *       The auto-detected dialect is wrapped to force quoting on every
 *       identifier — case-folding databases (Oracle, HSQLDB, DB2 → UPPER;
 *       Postgres, MySQL → LOWER) would otherwise case-mangle the FoodMart
 *       fixture's lowercase table names.
 *   <li>Truly unknown products (Calcite returns {@code AnsiSqlDialect} as its
 *       "I don't know" sentinel) → {@code null}, signalling per-datasource
 *       fallback to the legacy Mondrian SQL builder.
 * </ol>
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
            if (dialect == null) {
                // Fall through to Calcite's built-in dialect factory so we
                // don't have to hand-curate every database. The factory
                // returns AnsiSqlDialect as its "I don't know" sentinel.
                SqlDialect autoDetected =
                    SqlDialectFactoryImpl.INSTANCE.create(md);
                if (autoDetected != null
                    && !(autoDetected instanceof AnsiSqlDialect))
                {
                    dialect = forceQuoting(autoDetected);
                }
            }
            if (dialect == null && WARNED_PRODUCTS.add(product)) {
                LOGGER.warn(
                    "No Calcite SqlDialect mapping for JDBC product '"
                    + product + "' (neither hand-curated nor recognised by "
                    + "Calcite's SqlDialectFactoryImpl) — falling back to "
                    + "the legacy Mondrian backend for this datasource.");
            }
            return dialect;
        } catch (SQLException e) {
            throw new RuntimeException(
                "CalciteDialectMap.forDataSource: failed to read "
                + "DatabaseMetaData.getDatabaseProductName()", e);
        }
    }

    /**
     * Wraps a Calcite-auto-detected dialect in a thin subclass that forces
     * quoting on every identifier. The FoodMart fixture uses lowercase
     * delimited table names; case-folding databases (Oracle, HSQLDB, DB2)
     * would otherwise look for UPPER-CASE versions and fail. Quoted
     * identifiers preserve case on every database, matching what Mondrian's
     * legacy {@code SqlQuery} builder has always done.
     *
     * <p>{@link SqlDialect#getContext()} is not public in Calcite 1.41, so
     * we synthesize a fresh {@link SqlDialect.Context} from
     * {@link SqlDialect#EMPTY_CONTEXT}, copying over the database product
     * and the few introspectable properties from {@code base} that
     * downstream Calcite code may check. Class-level overrides on {@code base}
     * (custom function unparse, dialect-specific pagination syntax, etc.) are
     * <em>not</em> inherited — for FoodMart SELECT/GROUP BY/WHERE/JOIN that
     * is sufficient. Add a hand-curated entry in {@link #forProductNameOrNull}
     * above for any database that needs richer dialect-specific unparse.
     */
    private static SqlDialect forceQuoting(SqlDialect base) {
        SqlDialect.Context ctx = SqlDialect.EMPTY_CONTEXT
            .withDatabaseProduct(base.getDatabaseProduct())
            .withNullCollation(base.getNullCollation())
            .withConformance(base.getConformance())
            .withIdentifierQuoteString("\"")
            .withCaseSensitive(true);
        return new SqlDialect(ctx) {
            @Override
            protected boolean identifierNeedsQuote(String val) {
                return true;
            }
        };
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
                "No hand-curated Calcite SqlDialect mapping for JDBC product '"
                + product + "'. The hand-curated table covers HSQLDB and "
                + "PostgreSQL; for any other product call forDataSource(ds) "
                + "instead — it delegates to Calcite's SqlDialectFactoryImpl "
                + "for auto-detection.");
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
        if (p.contains("duckdb")) {
            return QUOTING_DUCKDB;
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

    /**
     * DuckDB dialect with the same force-quote treatment as HSQLDB.
     * Calcite 1.41's {@link org.apache.calcite.sql.SqlDialectFactoryImpl}
     * does not auto-detect DuckDB from the JDBC product name; without a
     * hand-curated entry here the planner cache silently falls through to
     * the legacy Mondrian SQL builder and the Calcite path is never
     * exercised against DuckDB. FoodMart's quoted lowercase table names
     * require the same forced-quoting behaviour as HSQLDB / Postgres.
     */
    private static final SqlDialect QUOTING_DUCKDB =
        new DuckDBSqlDialect(
            DuckDBSqlDialect.DEFAULT_CONTEXT
                .withIdentifierQuoteString("\""))
        {
            @Override
            protected boolean identifierNeedsQuote(String val) {
                return true;
            }
        };
}
