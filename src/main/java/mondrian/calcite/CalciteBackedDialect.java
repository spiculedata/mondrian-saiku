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
import mondrian.spi.impl.JdbcDialectImpl;

import org.apache.calcite.sql.SqlDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A Mondrian {@link Dialect} that derives capability flags and identifier
 * quoting from a wrapped Calcite {@link SqlDialect}, with conservative
 * defaults inherited from {@link JdbcDialectImpl} for everything Calcite
 * doesn't speak to.
 *
 * <p>Use case: backends with no hand-written Mondrian dialect class
 * (Snowflake, BigQuery, Databricks, ClickHouse, DuckDB, Trino, etc.) fall
 * back to {@link JdbcDialectImpl} today, whose conservative defaults make
 * Mondrian's native evaluators give up on push-down. Wrapping a Calcite
 * {@link SqlDialect} — which Mondrian already resolves per data source via
 * {@link CalciteDialectMap} — lets the planner see accurate per-backend
 * capability answers without anyone having to write a Mondrian
 * {@code Dialect} subclass first.
 *
 * <p>Pattern: extend {@link JdbcDialectImpl} so all the JDBC-metadata-derived
 * properties (max column name length, supported result-set concurrency,
 * identifier regex, etc.) work unchanged. Override <em>only</em> the
 * methods Calcite can answer more accurately than the conservative
 * generic fallback.
 *
 * <p>Where Calcite is silent on a capability the Mondrian planner cares
 * about, the bridge leaves the parent's behaviour in place — we never
 * over-claim a capability.
 *
 * <p>See ticket #40 for the design rationale.
 */
public class CalciteBackedDialect extends JdbcDialectImpl {

    /**
     * Calcite {@code DatabaseProduct} enum names of backends known to
     * support the {@code GROUP BY GROUPING SETS (...)} syntax. Calcite's
     * own {@code supportsGroupByWithRollup()} / {@code WithCube()} flags
     * under-claim — most dialect subclasses leave the base {@code false}
     * defaults in place even when the engine itself supports it (verified
     * for DuckDB via integration test). Maintain an explicit allowlist
     * derived from each engine's official docs.
     *
     * <p>Conservative: when in doubt, leave out. Engines absent from this
     * list fall back to the parent's conservative {@code false}.
     */
    private static final Set<String> CALCITE_PRODUCTS_WITH_GROUPING_SETS;
    static {
        Set<String> s = new HashSet<>(Arrays.asList(
            // SQL:1999 implementations
            "POSTGRESQL",   // >= 9.5
            "ORACLE",
            "DB2",
            "MSSQL",        // SQL Server >= 2008
            // Modern analytical engines
            "SNOWFLAKE",
            "BIG_QUERY",
            "REDSHIFT",
            "TRINO",
            "PRESTO",
            "SPARK",
            "DUCKDB",
            "CLICKHOUSE",
            "VERTICA",
            "HIVE",
            "TERADATA",
            "NETEZZA",
            "DORIS",
            "STARROCKS",
            "EXASOL"));
        CALCITE_PRODUCTS_WITH_GROUPING_SETS = Collections.unmodifiableSet(s);
    }

    private final SqlDialect calciteDialect;

    /**
     * Creates a bridge dialect for the given JDBC connection, wrapping the
     * supplied Calcite {@link SqlDialect}.
     *
     * @param connection live JDBC connection — used by the parent
     *                   {@link JdbcDialectImpl} to read metadata
     * @param calciteDialect non-null Calcite SqlDialect to delegate to
     * @throws SQLException if the parent class can't read metadata
     * @throws IllegalArgumentException if {@code calciteDialect} is null
     */
    public CalciteBackedDialect(Connection connection, SqlDialect calciteDialect)
        throws SQLException
    {
        super(connection);
        if (calciteDialect == null) {
            throw new IllegalArgumentException(
                "CalciteBackedDialect requires a non-null Calcite SqlDialect");
        }
        this.calciteDialect = calciteDialect;
    }

    /** Returns the wrapped Calcite SqlDialect. */
    public SqlDialect getCalciteDialect() {
        return calciteDialect;
    }

    // ----- quoting: delegate to Calcite -----

    @Override
    public String quoteIdentifier(String val) {
        return calciteDialect.quoteIdentifier(val);
    }

    // ----- capability flags derived from Calcite -----

    /**
     * GROUPING SETS support derivation. Two signals, either suffices:
     *
     * <ol>
     *   <li>Calcite's {@code supportsGroupByWithRollup() &&
     *       supportsGroupByWithCube()} — true when the dialect subclass
     *       explicitly opts in.
     *   <li>The backend's Calcite {@code DatabaseProduct} appears in
     *       {@link #CALCITE_PRODUCTS_WITH_GROUPING_SETS} — an allowlist
     *       derived from each engine's docs. Necessary because most
     *       Calcite dialect subclasses inherit the {@code false} default
     *       even when the engine itself supports the feature (verified
     *       for DuckDB via integration test).
     * </ol>
     *
     * <p>If neither signal fires, fall back to the parent's conservative
     * answer — never over-claim.
     */
    @Override
    public boolean supportsGroupingSets() {
        if (calciteDialect.supportsGroupByWithRollup()
            && calciteDialect.supportsGroupByWithCube())
        {
            return true;
        }
        SqlDialect.DatabaseProduct cp = calciteDialect.getDatabaseProduct();
        if (cp != null
            && CALCITE_PRODUCTS_WITH_GROUPING_SETS.contains(cp.name()))
        {
            return true;
        }
        return super.supportsGroupingSets();
    }

    @Override
    public boolean requiresAliasForFromQuery() {
        return calciteDialect.requiresAliasForFromItems();
    }

    /**
     * Calcite-specific capability — not on the Mondrian {@link Dialect}
     * interface today, but exposed here for future planner work that wants
     * to push approximate-distinct aggregations down to engines that
     * support {@code APPROX_COUNT_DISTINCT} (Snowflake, BigQuery,
     * ClickHouse, Trino, DuckDB).
     */
    public boolean supportsApproxCountDistinct() {
        return calciteDialect.supportsApproxCountDistinct();
    }

    /**
     * Any backend Calcite has a {@link SqlDialect} for is modern enough to
     * support the ANSI {@code ORDER BY ... NULLS LAST}/{@code NULLS FIRST}
     * syntax — Postgres ≥ 8.3, Oracle, DB2, SQL Server 2022, MySQL ≥ 8,
     * Snowflake, BigQuery, DuckDB, ClickHouse, Trino, Spark. Override the
     * parent's verbose {@code CASE WHEN expr IS NULL THEN 0 ELSE 1 END}
     * workaround.
     *
     * <p>The handful of engines that don't (MySQL ≤ 5.x, SQL Server ≤ 2019)
     * have their own hand-written Mondrian dialects, so the bridge never
     * fires for them. Safe.
     */
    @Override
    protected String generateOrderByNulls(
        String expr,
        boolean ascending,
        boolean collateNullsLast)
    {
        return generateOrderByNullsAnsi(expr, ascending, collateNullsLast);
    }

    /**
     * Map Calcite's {@code DatabaseProduct} enum to Mondrian's where
     * names align (HSQLDB, POSTGRESQL, MYSQL, ORACLE, MSSQL, DB2, DERBY,
     * REDSHIFT, VERTICA, etc.). For Calcite-only products (DuckDB,
     * Snowflake, BigQuery, ClickHouse, Spark, Trino, H2, Exasol, ...)
     * return {@link DatabaseProduct#UNKNOWN} — never lie by reporting a
     * different product to Mondrian code that branches on the enum.
     */
    @Override
    public DatabaseProduct getDatabaseProduct() {
        // Java constructor-time virtual dispatch: super(connection) invokes
        // computeStatisticsProviders() → getStatisticsProviderNames() →
        // getDatabaseProduct() before our calciteDialect field is assigned.
        // Defer to the parent for that initial call; once the bridge is
        // fully constructed, derive from Calcite as designed.
        if (calciteDialect == null) {
            return super.getDatabaseProduct();
        }
        org.apache.calcite.sql.SqlDialect.DatabaseProduct cp =
            calciteDialect.getDatabaseProduct();
        if (cp == null) {
            return super.getDatabaseProduct();
        }
        try {
            return DatabaseProduct.valueOf(cp.name());
        } catch (IllegalArgumentException calciteOnlyProduct) {
            return DatabaseProduct.UNKNOWN;
        }
    }
}

// End CalciteBackedDialect.java
