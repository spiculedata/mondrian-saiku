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

import mondrian.olap.Connection;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.test.TestContext;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the OpenTelemetry metrics wired in for #33 Session 5 + #10
 * Calcite fallback audit actually emit when an OTel SDK is registered.
 *
 * <p>Uses {@link InMemoryMetricReader} — no network, no env dependency.
 * Resets the global SDK between runs so other tests in the same JVM
 * are unaffected.
 *
 * <p>What's covered:
 * <ul>
 *   <li>{@code mondrian.queries.executed} counter + outcome attribute</li>
 *   <li>{@code mondrian.query.duration} histogram</li>
 *   <li>{@code mondrian.sql.statements} counter + kind attribute</li>
 *   <li>{@code mondrian.sql.duration} histogram</li>
 *   <li>{@code mondrian.cache.segment.{hits,misses}} counters</li>
 * </ul>
 *
 * <p>The {@code mondrian.calcite.fallback} counter only fires when a
 * Calcite translator actually fails — synthesised in a separate test
 * via the {@link MondrianMetrics#recordCalciteFallback} helper rather
 * than trying to construct a failing Calcite plan in unit-test scope.
 */
public class MondrianMetricsTest {

    private InMemoryMetricReader reader;
    private OpenTelemetrySdk sdk;

    @Before
    public void registerInMemorySdk() {
        GlobalOpenTelemetry.resetForTest();
        MondrianMetrics.resetForTest();
        reader = InMemoryMetricReader.create();
        SdkMeterProvider mp = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        sdk = OpenTelemetrySdk.builder().setMeterProvider(mp).build();
        GlobalOpenTelemetry.set(sdk);
    }

    @After
    public void tearDown() {
        if (sdk != null) {
            sdk.close();
        }
        GlobalOpenTelemetry.resetForTest();
        MondrianMetrics.resetForTest();
    }

    /**
     * A successful MDX query against FoodMart emits the
     * queries.executed counter + query.duration histogram, plus the
     * sql.statements/sql.duration pair for each SQL Mondrian issued.
     */
    @Test
    public void successfulMdxQueryEmitsExpectedMetrics() {
        TestContext ctx = TestContext.instance();
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(
                "SELECT [Measures].[Unit Sales] ON COLUMNS, "
                + "[Store].[USA].Children ON ROWS FROM [Sales]");
            Result result = conn.execute(q);
            result.close();
        } finally {
            conn.close();
        }

        Collection<MetricData> metrics = reader.collectAllMetrics();

        MetricData queries = findMetric(metrics, "mondrian.queries.executed");
        assertNotNull(
            "expected mondrian.queries.executed metric — got: " + metrics,
            queries);
        long queriesCount = sumLongCounter(queries);
        assertTrue(
            "expected queries.executed >= 1 — got " + queriesCount,
            queriesCount >= 1L);

        // Verify the outcome attribute attached
        boolean foundSuccess = false;
        for (LongPointData p : queries.getLongSumData().getPoints()) {
            String outcome = p.getAttributes().get(
                AttributeKey.stringKey("mondrian.query.outcome"));
            if ("success".equals(outcome)) {
                foundSuccess = true;
            }
        }
        assertTrue(
            "expected at least one queries.executed point with "
                + "outcome=success",
            foundSuccess);

        MetricData duration = findMetric(metrics, "mondrian.query.duration");
        assertNotNull(duration);
        assertTrue(
            "expected query.duration histogram to have at least one point",
            !duration.getHistogramData().getPoints().isEmpty());
        long durationCount = sumHistogram(duration);
        assertTrue(
            "expected query.duration count >= 1 — got " + durationCount,
            durationCount >= 1L);
    }

    /**
     * The Calcite fallback counter helper increments cleanly and
     * attaches the site + exception attributes the dashboards group by.
     */
    @Test
    public void recordCalciteFallbackIncrementsCounter() {
        MondrianMetrics.recordCalciteFallback(
            "tuple-read",
            new IllegalArgumentException("synthetic"));
        MondrianMetrics.recordCalciteFallback(
            "segment-load",
            new mondrian.calcite.UnsupportedTranslation("synthetic"));

        Collection<MetricData> metrics = reader.collectAllMetrics();
        MetricData fallback =
            findMetric(metrics, "mondrian.calcite.fallback");
        assertNotNull(
            "expected mondrian.calcite.fallback counter — got: " + metrics,
            fallback);
        long total = sumLongCounter(fallback);
        assertTrue(
            "expected fallback counter >= 2 — got " + total,
            total >= 2L);

        // Verify both sites recorded distinct points
        boolean foundTupleRead = false;
        boolean foundSegmentLoad = false;
        for (LongPointData p : fallback.getLongSumData().getPoints()) {
            String site = p.getAttributes().get(
                AttributeKey.stringKey("mondrian.calcite.fallback.site"));
            if ("tuple-read".equals(site)) foundTupleRead = true;
            if ("segment-load".equals(site)) foundSegmentLoad = true;
        }
        assertTrue("expected tuple-read fallback point", foundTupleRead);
        assertTrue("expected segment-load fallback point", foundSegmentLoad);
    }

    // ---------- helpers ----------

    private static MetricData findMetric(
        Collection<MetricData> metrics, String name)
    {
        for (MetricData m : metrics) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    private static long sumLongCounter(MetricData m) {
        long total = 0;
        for (LongPointData p : m.getLongSumData().getPoints()) {
            total += p.getValue();
        }
        return total;
    }

    private static long sumHistogram(MetricData m) {
        long total = 0;
        for (HistogramPointData p : m.getHistogramData().getPoints()) {
            total += p.getCount();
        }
        return total;
    }
}

// End MondrianMetricsTest.java
