/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2012-2012 Pentaho
// All Rights Reserved.
*/
package mondrian.spi.impl;

import mondrian.calcite.CalcitePlannerAdapters;
import mondrian.calcite.CalcitePlannerCache;
import mondrian.calcite.CalciteSqlPlanner;
import mondrian.calcite.MondrianBackend;
import mondrian.calcite.PlannerRequest;
import mondrian.calcite.UnsupportedTranslation;
import mondrian.rolap.RolapUtil;
import mondrian.rolap.SqlStatement;
import mondrian.server.Execution;
import mondrian.server.Locus;
import mondrian.spi.Dialect;
import mondrian.spi.StatisticsProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Arrays;
import javax.sql.DataSource;

/**
 * Implementation of {@link mondrian.spi.StatisticsProvider} that generates
 * SQL queries to count rows and distinct values.
 */
public class SqlStatisticsProvider implements StatisticsProvider {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(SqlStatisticsProvider.class);

    /**
     * Dispatches to {@link CalcitePlannerCache}, which is keyed on JDBC
     * identity ({@code url, catalog, schema, user}). See
     * {@code docs/reports/perf-investigation-y1.md} Fix #1.
     */
    private static CalciteSqlPlanner plannerFor(DataSource dataSource) {
        return CalcitePlannerCache.plannerFor(dataSource);
    }

    /** Test-only: drop the shared Calcite planner cache. */
    public static void clearCalcitePlannerCache() {
        CalcitePlannerCache.clear();
    }

    public int getTableCardinality(
        Dialect dialect,
        DataSource dataSource,
        String catalog,
        String schema,
        String table,
        Execution execution)
    {
        StringBuilder buf = new StringBuilder("select count(*) from ");
        dialect.quoteIdentifier(buf, catalog, schema, table);
        final String sql = buf.toString();
        SqlStatement stmt =
            RolapUtil.executeQuery(
                dataSource,
                sql,
                new Locus(
                    execution,
                    "SqlStatisticsProvider.getTableCardinality",
                    "Reading row count from table "
                    + Arrays.asList(catalog, schema, table)));
        try {
            ResultSet resultSet = stmt.getResultSet();
            if (resultSet.next()) {
                ++stmt.rowCount;
                return resultSet.getInt(1);
            }
            return -1; // huh?
        } catch (SQLException e) {
            throw stmt.handle(e);
        } finally {
            stmt.close();
        }
    }

    public int getQueryCardinality(
        Dialect dialect,
        DataSource dataSource,
        String sql,
        Execution execution)
    {
        final StringBuilder buf = new StringBuilder();
        buf.append(
            "select count(*) from (").append(sql).append(")");
        if (dialect.requiresAliasForFromQuery()) {
            if (dialect.allowsAs()) {
                buf.append(" as ");
            } else {
                buf.append(" ");
            }
            dialect.quoteIdentifier(buf, "init");
        }
        final String countSql = buf.toString();
        SqlStatement stmt =
            RolapUtil.executeQuery(
                dataSource,
                countSql,
                new Locus(
                    execution,
                    "SqlStatisticsProvider.getQueryCardinality",
                    "Reading row count from query [" + sql + "]"));
        try {
            ResultSet resultSet = stmt.getResultSet();
            if (resultSet.next()) {
                ++stmt.rowCount;
                return resultSet.getInt(1);
            }
            return -1; // huh?
        } catch (SQLException e) {
            throw stmt.handle(e);
        } finally {
            stmt.close();
        }
    }

    public int getColumnCardinality(
        Dialect dialect,
        DataSource dataSource,
        String catalog,
        String schema,
        String table,
        String column,
        Execution execution)
    {
        String sql =
            generateColumnCardinalitySql(
                dialect, schema, table, column);
        if (sql == null) {
            return -1;
        }
        // Task C dispatch: third SQL-origin seam. Adapter-level
        // UnsupportedTranslation (qualified schema, null table/column, etc.)
        // still propagates out of fromCardinalityProbe — that call sits
        // outside the try block on purpose, so genuinely unsupported probe
        // shapes surface as hard errors rather than silently downgrading.
        //
        // Issue #46 (4.8.1.10): the wrap in CalciteSqlPlanner.plan now
        // rebrands the RelBuilder.field IAE as UnsupportedTranslation, so
        // the saiku#781 "fall back when the probe can't resolve a field"
        // case has shifted exception types. Catch both — IAE for forward
        // compatibility if the wrap is ever loosened, and
        // UnsupportedTranslation for the current shape that the
        // optimizePredicates path hits on multi-dim queries.
        if (MondrianBackend.current().isCalcite()) {
            CalciteSqlPlanner planner = plannerFor(dataSource);
            if (planner != null) {
                PlannerRequest req =
                    CalcitePlannerAdapters.fromCardinalityProbe(
                        schema, table, column);
                try {
                    String calciteSql = planner.plan(req);
                    if (Boolean.getBoolean("mondrian.calcite.trace")) {
                        System.err.println("[calcite-ok-probe] " + calciteSql);
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                            "Calcite backend: cardinality probe translated.\n"
                            + "  legacy: " + sql + "\n"
                            + "  calcite: " + calciteSql);
                    }
                    sql = calciteSql;
                } catch (UnsupportedTranslation | IllegalArgumentException e) {
                    // Calcite's RelBuilder.field throws when a star-schema
                    // cardinality probe asks for a column the inferred input
                    // rowType doesn't carry (Warehouse cube's Country level
                    // is the known trigger — "field [warehouse_country] not
                    // found; input fields are: []"). Post-4.8.1.10 that IAE
                    // is wrapped as UnsupportedTranslation in plan(); pre-
                    // wrap it would surface here directly. Either way,
                    // cardinality is an optimizer hint and a hard-fail at
                    // this depth kills the whole user query, so we fall back
                    // to the legacy probe SQL.
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                            "Calcite cardinality probe failed for "
                            + table + "." + column + " — falling back to "
                            + "legacy SQL: " + e.getMessage());
                    }
                }
            }
            // planner == null => CalciteDialectMap returned no mapping for
            // this datasource; fall through to the legacy probe SQL.
        }
        SqlStatement stmt =
            RolapUtil.executeQuery(
                dataSource,
                sql,
                new Locus(
                    execution,
                    "SqlStatisticsProvider.getColumnCardinality",
                    "Reading cardinality for column "
                    + Arrays.asList(catalog, schema, table, column)));
        try {
            ResultSet resultSet = stmt.getResultSet();
            if (resultSet.next()) {
                ++stmt.rowCount;
                return resultSet.getInt(1);
            }
            return -1; // huh?
        } catch (SQLException e) {
            throw stmt.handle(e);
        } finally {
            stmt.close();
        }
    }

    private static String generateColumnCardinalitySql(
        Dialect dialect,
        String schema,
        String table,
        String column)
    {
        final StringBuilder buf = new StringBuilder();
        String exprString = dialect.quoteIdentifier(column);
        if (dialect.allowsCountDistinct()) {
            // e.g. "select count(distinct product_id) from product"
            buf.append("select count(distinct ")
                .append(exprString)
                .append(") from ");
            dialect.quoteIdentifier(buf, schema, table);
            return buf.toString();
        } else if (dialect.allowsFromQuery()) {
            // Some databases (e.g. Access) don't like 'count(distinct)',
            // so use, e.g., "select count(*) from (select distinct
            // product_id from product)"
            buf.append("select count(*) from (select distinct ")
                .append(exprString)
                .append(" from ");
            dialect.quoteIdentifier(buf, schema, table);
            buf.append(")");
            if (dialect.requiresAliasForFromQuery()) {
                if (dialect.allowsAs()) {
                    buf.append(" as ");
                } else {
                    buf.append(' ');
                }
                dialect.quoteIdentifier(buf, "init");
            }
            return buf.toString();
        } else {
            // Cannot compute cardinality: this database neither supports COUNT
            // DISTINCT nor SELECT in the FROM clause.
            return null;
        }
    }
}

// End SqlStatisticsProvider.java
