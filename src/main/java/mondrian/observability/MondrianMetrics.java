/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

/**
 * Single source of truth for Mondrian's OpenTelemetry metrics. Sibling
 * to {@link MondrianTracing} — same library identity, same zero-overhead
 * property when no SDK is registered.
 *
 * <h3>Metric naming convention</h3>
 *
 * <p>All metrics use the {@code mondrian.} prefix. Counters report
 * deltas (OTel SDK aggregates); histograms report per-event durations
 * in milliseconds.
 *
 * <ul>
 *   <li>{@code mondrian.queries.executed} — counter, incremented at the
 *       end of each MDX query (success or failure). Attribute
 *       {@code mondrian.query.outcome} = {@code success} | {@code failure}.</li>
 *   <li>{@code mondrian.query.duration} — histogram (ms), recorded at
 *       the end of each MDX query. Same outcome attribute.</li>
 *   <li>{@code mondrian.sql.statements} — counter, incremented per
 *       JDBC SQL statement issued by Mondrian (segment-load, member-read,
 *       drillthrough, other). Attribute {@code mondrian.sql.kind}.</li>
 *   <li>{@code mondrian.sql.duration} — histogram (ms), recorded per
 *       JDBC SQL statement. Same kind attribute.</li>
 *   <li>{@code mondrian.cache.segment.hits} — counter, incremented per
 *       cell request that the segment cache satisfied without a SQL
 *       load. High hit rate = good cache locality.</li>
 *   <li>{@code mondrian.cache.segment.misses} — counter, incremented
 *       per cell request that fell through to a SQL load.</li>
 *   <li>{@code mondrian.calcite.fallback} — counter, incremented when
 *       a Calcite translator failure causes Mondrian to fall back to
 *       legacy SQL. Attribute {@code mondrian.calcite.fallback.site}
 *       identifies which code path tripped (segment-load / tuple-read
 *       / drillthrough). Pairs with #10 audit work.</li>
 * </ul>
 *
 * <h3>Hot-path care</h3>
 *
 * <p>Cache hit/miss counters are incremented inside per-cell loops
 * (see {@link mondrian.rolap.FastBatchingCellReader}). The OTel
 * {@code LongCounter#add(long)} call is an atomic CAS — comparable in
 * cost to the existing {@code AtomicLong#incrementAndGet} this code
 * already does for non-OTel counters. No measurable overhead added.
 *
 * <p>For per-cell <em>spans</em> (the original Session 4 plan), see
 * the design note in this class's commit history: spans were rejected
 * for per-cell hot-path use; counter aggregation is the right
 * granularity.
 */
public final class MondrianMetrics {

    private static final String INSTRUMENTATION_NAME =
        MondrianTracing.INSTRUMENTATION_NAME;
    private static final String INSTRUMENTATION_VERSION =
        MondrianTracing.INSTRUMENTATION_VERSION;

    /** Cached Meter — lazily resolved against whatever OTel SDK is wired. */
    private static volatile Meter meter;

    /** Cached instruments — built once on first use. */
    private static volatile LongCounter queriesExecuted;
    private static volatile LongHistogram queryDuration;
    private static volatile LongCounter sqlStatements;
    private static volatile LongHistogram sqlDuration;
    private static volatile LongCounter cacheSegmentHits;
    private static volatile LongCounter cacheSegmentMisses;
    private static volatile LongCounter calciteFallback;

    private MondrianMetrics() {}

    /** Returns the shared Mondrian {@link Meter}. */
    private static Meter meter() {
        Meter m = meter;
        if (m == null) {
            m = GlobalOpenTelemetry.getMeter(INSTRUMENTATION_NAME);
            meter = m;
        }
        return m;
    }

    public static LongCounter queriesExecuted() {
        LongCounter c = queriesExecuted;
        if (c == null) {
            c = meter().counterBuilder("mondrian.queries.executed")
                .setDescription("MDX queries completed (success or failure)")
                .setUnit("{query}")
                .build();
            queriesExecuted = c;
        }
        return c;
    }

    public static LongHistogram queryDuration() {
        LongHistogram h = queryDuration;
        if (h == null) {
            h = meter().histogramBuilder("mondrian.query.duration")
                .ofLongs()
                .setDescription("MDX query end-to-end wall time")
                .setUnit("ms")
                .build();
            queryDuration = h;
        }
        return h;
    }

    public static LongCounter sqlStatements() {
        LongCounter c = sqlStatements;
        if (c == null) {
            c = meter().counterBuilder("mondrian.sql.statements")
                .setDescription("JDBC SQL statements Mondrian issued")
                .setUnit("{statement}")
                .build();
            sqlStatements = c;
        }
        return c;
    }

    public static LongHistogram sqlDuration() {
        LongHistogram h = sqlDuration;
        if (h == null) {
            h = meter().histogramBuilder("mondrian.sql.duration")
                .ofLongs()
                .setDescription("JDBC SQL statement wall time")
                .setUnit("ms")
                .build();
            sqlDuration = h;
        }
        return h;
    }

    public static LongCounter cacheSegmentHits() {
        LongCounter c = cacheSegmentHits;
        if (c == null) {
            c = meter().counterBuilder("mondrian.cache.segment.hits")
                .setDescription(
                    "Cell requests satisfied from the segment cache")
                .setUnit("{cell}")
                .build();
            cacheSegmentHits = c;
        }
        return c;
    }

    public static LongCounter cacheSegmentMisses() {
        LongCounter c = cacheSegmentMisses;
        if (c == null) {
            c = meter().counterBuilder("mondrian.cache.segment.misses")
                .setDescription(
                    "Cell requests that fell through to a SQL load")
                .setUnit("{cell}")
                .build();
            cacheSegmentMisses = c;
        }
        return c;
    }

    public static LongCounter calciteFallback() {
        LongCounter c = calciteFallback;
        if (c == null) {
            c = meter().counterBuilder("mondrian.calcite.fallback")
                .setDescription(
                    "Calcite translator failures that fell back to "
                    + "legacy Mondrian SQL")
                .setUnit("{fallback}")
                .build();
            calciteFallback = c;
        }
        return c;
    }

    /**
     * Convenience: increment {@link #calciteFallback()} with the
     * standard {@code site} + {@code exception} attribute pair. Used by
     * every Calcite fallback catch site to keep the call-site code
     * small. Swallows exceptions internally — metric recording must
     * never break a fallback path that's already handling another error.
     *
     * @param site    one of {@code tuple-read}, {@code segment-load},
     *                {@code segment-load-worker}, {@code drillthrough}
     * @param ex      the exception that triggered the fallback (only
     *                {@code getClass().getSimpleName()} is recorded —
     *                no message, no stack, low cardinality)
     */
    public static void recordCalciteFallback(String site, Throwable ex) {
        try {
            calciteFallback().add(1,
                io.opentelemetry.api.common.Attributes.of(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                        "mondrian.calcite.fallback.site"), site,
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                        "mondrian.calcite.fallback.exception"),
                    ex == null ? "Unknown" : ex.getClass().getSimpleName()));
        } catch (RuntimeException ignored) { }
    }

    /**
     * Test-only: drop the cached Meter + instruments. After this call,
     * the next instrument access resolves against the (possibly newly
     * registered) {@link GlobalOpenTelemetry} provider. Lets tests
     * register a fresh SDK between runs.
     */
    public static void resetForTest() {
        meter = null;
        queriesExecuted = null;
        queryDuration = null;
        sqlStatements = null;
        sqlDuration = null;
        cacheSegmentHits = null;
        cacheSegmentMisses = null;
        calciteFallback = null;
    }
}

// End MondrianMetrics.java
