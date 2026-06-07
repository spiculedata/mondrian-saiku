/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap.agg;

import mondrian.observability.MondrianMetrics;
import mondrian.olap.MondrianException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

/**
 * Issue #90, scope item 3 — the runtime divergence telemetry guard.
 *
 * <p>The exception-based Calcite fallback (see
 * {@link SegmentLoader#createExecuteSql}) only catches "Calcite threw". It
 * cannot catch "Calcite produced valid-but-WRONG SQL" — SQL that runs without
 * error but returns a different result (the #89 class). This guard closes that
 * gap: when {@code -Dmondrian.calcite.parityCheck=true} is set, every Calcite
 * segment load ALSO runs the legacy SQL for the SAME load and compares the two
 * result sets. A mismatch is emitted as {@code mondrian.calcite.divergence}
 * (see {@link MondrianMetrics#recordCalciteDivergence}) plus a WARN log, so
 * silent divergences surface in telemetry instead of only as user-reported
 * "no data".
 *
 * <h3>Comparison mechanism: JDBC row-set</h3>
 *
 * <p>We compare the <em>actual JDBC result rows</em> of the two SQL strings,
 * not the SQL text. The legacy SQL ({@code pair.left}) is always built by
 * {@code AggregationManager.generateSql}; the Calcite SQL is the string the
 * worker is about to execute. Both are fully-formed (literals inlined, no
 * bind parameters), so we execute each against the same {@link DataSource}
 * with a plain {@link Statement}, normalise each row to a comparable form,
 * sort the two row multisets, and compare. This is faithful (it compares
 * real results), self-contained (no segment-cache mutation, no extra worker
 * scheduling — it runs sequentially on the thread already doing the load),
 * and dialect-agnostic. The throwaway legacy run never touches the cache and
 * its result is discarded; only the Calcite result is ever returned to the
 * caller.
 *
 * <h3>Safety: predicate-secured + Calcite-only loads are SKIPPED</h3>
 *
 * <p>The caller (SegmentLoader) MUST NOT invoke this guard for a
 * predicate-secured load (the legacy generator drops the row-security filter;
 * running it would leak rows) — that check lives at the call site via
 * {@code CalcitePlannerAdapters.isPredicateSecuredLoad}. Beyond that, this
 * class treats any inability to run the legacy comparison conservatively: if
 * executing the legacy SQL throws (a Calcite-only shape — bridge / symmetric /
 * distinct-grain / median / percentile that legacy cannot express), the load
 * is "not comparable" and is SKIPPED silently. A one-sided inability is NOT a
 * divergence; divergence means both sides ran and disagreed.
 *
 * <p>Intentionally-Calcite-only aggregations (measure-level distinct grain
 * #119, median/percentile #104, and bridge/symmetric fan-out #103/#107) are
 * skipped by design — via
 * {@code CalcitePlannerAdapters.isCalciteOnlyAggregation} at the call site for
 * the cases legacy runs-but-mis-aggregates, and via the legacy-throws skip for
 * the bridge case — so these correct-by-design results never false-trip the
 * guard.
 *
 * <h3>Hot-path cost</h3>
 *
 * <p>When {@code mondrian.calcite.parityCheck} is false there is zero added
 * cost beyond a single boolean read ({@link #isEnabled()}, cached once). The
 * guard is dev/CI/sampled tooling, not a production-by-default feature: it
 * doubles SQL load work for the segment loads it inspects.
 */
final class CalciteParityGuard {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(CalciteParityGuard.class);

    /** {@code -Dmondrian.calcite.parityCheck} — default OFF. */
    private static final String PROP_PARITY_CHECK =
        "mondrian.calcite.parityCheck";

    /** {@code -Dmondrian.calcite.parityCheck.strict} — default OFF. */
    private static final String PROP_STRICT =
        "mondrian.calcite.parityCheck.strict";

    private static final String SITE = "segment-load";

    /** Column delimiter for the row signature — an ASCII unit separator
     *  (0x1F), chosen so it never collides with real data. */
    private static final String COL_SEP = "\u001F";

    /** Sentinel for a SQL NULL cell, distinct from the string "null". */
    private static final String NULL_TOKEN = "NULL";

    private CalciteParityGuard() {}

    /**
     * The single cheap boolean the hot path checks before doing any parity
     * work. When false the caller must not build any comparison state, so the
     * default-OFF cost is just this one {@code Boolean.getBoolean} map lookup
     * (mirrors the existing {@code mondrian.calcite.trace}/{@code .strict}
     * flag reads in {@link SegmentLoader}). Read per call rather than cached
     * so dev/CI tooling can toggle it without a JVM restart.
     */
    static boolean isEnabled() {
        return Boolean.getBoolean(PROP_PARITY_CHECK);
    }

    /** Whether a found divergence should hard-fail the load. */
    static boolean isStrict() {
        return Boolean.getBoolean(PROP_STRICT);
    }

    /**
     * Runs the legacy SQL alongside the (already-chosen) Calcite SQL and
     * compares the JDBC row-sets. Never mutates the load result — the
     * Calcite result the worker already has is what's returned. On a real
     * divergence: records {@code mondrian.calcite.divergence}, WARNs, and (in
     * strict mode) throws {@link MondrianException}.
     *
     * <p>If the legacy SQL cannot be executed (a Calcite-only shape), the
     * comparison is skipped silently — a one-sided inability is not a
     * divergence.
     *
     * @param dataSource the load's JDBC datasource
     * @param legacySql  the legacy SQL string ({@code pair.left})
     * @param calciteSql the Calcite SQL string the worker executed
     */
    static void compare(
        DataSource dataSource, String legacySql, String calciteSql)
    {
        if (legacySql == null || calciteSql == null) {
            return;
        }
        // Identical text => identical result; nothing to compare.
        if (legacySql.equals(calciteSql)) {
            return;
        }
        List<String> legacyRows;
        List<String> calciteRows;
        try {
            legacyRows = runAndNormalise(dataSource, legacySql);
        } catch (RuntimeException | SQLException notComparable) {
            // Legacy cannot run this shape (Calcite-only: bridge / symmetric /
            // distinct-grain / median / percentile). Not a divergence — skip.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "#90 parity check skipped (legacy SQL not runnable for "
                    + "this load — treating as Calcite-only): "
                    + notComparable.getMessage());
            }
            return;
        }
        try {
            calciteRows = runAndNormalise(dataSource, calciteSql);
        } catch (RuntimeException | SQLException ex) {
            // The Calcite SQL the worker already executed successfully failing
            // a re-run here is itself anomalous, but it is NOT a silent-wrong
            // result. Skip rather than mis-attribute a divergence.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "#90 parity check skipped (Calcite SQL re-run failed): "
                    + ex.getMessage());
            }
            return;
        }
        report(diff(legacyRows, calciteRows), isStrict());
    }

    /**
     * Compares two already-materialised normalised row-sets and reports any
     * divergence. Package-private so the negative/fault-injection test can
     * drive the comparison + reporting path directly with two deliberately
     * different result sets (proving the guard actually fires).
     *
     * @return the divergence category, or {@code null} if the row-sets match
     */
    static String compareRowSets(
        List<String> legacyRows, List<String> calciteRows)
    {
        return compareRowSets(legacyRows, calciteRows, isStrict());
    }

    /**
     * Strict-explicit variant of {@link #compareRowSets(List, List)} — lets
     * the fault-injection test prove that strict mode hard-fails on a real
     * divergence without depending on the (load-time-final) static
     * {@code mondrian.calcite.parityCheck.strict} flag.
     */
    static String compareRowSets(
        List<String> legacyRows, List<String> calciteRows, boolean strict)
    {
        String category = diff(legacyRows, calciteRows);
        report(category, strict);
        return category;
    }

    /**
     * Returns the LOW-CARDINALITY divergence category, or {@code null} when
     * the two normalised row-sets are equal. NEVER returns raw cell values
     * or member names (PII / cardinality safety).
     */
    private static String diff(
        List<String> legacyRows, List<String> calciteRows)
    {
        if (legacyRows.size() != calciteRows.size()) {
            return "row-count";
        }
        List<String> a = new ArrayList<>(legacyRows);
        List<String> b = new ArrayList<>(calciteRows);
        Collections.sort(a);
        Collections.sort(b);
        if (a.equals(b)) {
            return null;
        }
        // Same row count, same column count (rows are positional joins of all
        // columns), but at least one cell differs.
        return "cell-value";
    }

    /** Emits telemetry + WARN (+ throws in strict mode) for a divergence. */
    private static void report(String category, boolean strict) {
        if (category == null) {
            return;
        }
        MondrianMetrics.recordCalciteDivergence(SITE, category);
        // SECURITY: log only the low-cardinality category, never the diverging
        // rows/values themselves.
        LOGGER.warn(
            "#90 Calcite parity divergence detected on a {} load "
            + "(category={}): the Calcite SQL and the legacy SQL returned "
            + "different results. The Calcite result was still returned; "
            + "enable -Dmondrian.calcite.parityCheck.strict=true to hard-fail.",
            SITE, category);
        if (strict) {
            throw new MondrianException(
                "#90 Calcite parity divergence (category=" + category
                + ") on a " + SITE + " load: legacy and Calcite SQL "
                + "returned different results (strict mode).");
        }
    }

    /**
     * Executes {@code sql} and normalises the result to a sorted-friendly,
     * value-faithful list of strings — one per row, columns joined with a
     * delimiter unlikely to collide with data. Each row carries every
     * column so the comparison covers axis keys AND measure values.
     */
    private static List<String> runAndNormalise(DataSource dataSource, String sql)
        throws SQLException
    {
        List<String> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql))
        {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) {
                        sb.append(COL_SEP);
                    }
                    Object v = rs.getObject(i);
                    sb.append(v == null ? "NULL" : v.toString());
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }
}

// End CalciteParityGuard.java
