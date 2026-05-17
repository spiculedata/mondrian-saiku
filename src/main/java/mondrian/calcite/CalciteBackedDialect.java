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
     * Engines that support both {@code GROUP BY ... WITH ROLLUP} and
     * {@code WITH CUBE} effectively always support GROUPING SETS too —
     * the three are part of the SQL:1999 grouping extension. Where Calcite
     * doesn't claim both, fall back to the parent's conservative answer.
     */
    @Override
    public boolean supportsGroupingSets() {
        if (calciteDialect.supportsGroupByWithRollup()
            && calciteDialect.supportsGroupByWithCube())
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
}

// End CalciteBackedDialect.java
