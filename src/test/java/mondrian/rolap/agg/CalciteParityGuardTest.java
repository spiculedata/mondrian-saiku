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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * #90 scope-item-3 NEGATIVE / fault-injection test — the most important
 * test for the divergence guard: it proves the guard actually FIRES when the
 * legacy and Calcite result sets diverge. A guard that is never proven to
 * fire is worthless.
 *
 * <p>Rather than construct a failing Calcite plan in unit scope, we feed
 * {@link CalciteParityGuard#compareRowSets} two deliberately different
 * normalised row-sets — the exact comparison the production
 * {@link CalciteParityGuard#compare} path performs after executing both SQL
 * strings — and assert (a) the {@code mondrian.calcite.divergence} counter
 * increments with the right low-cardinality category, and (b) strict mode
 * throws. A matching pair must stay silent.
 */
public class CalciteParityGuardTest {

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

    /** Two equal row-sets: no divergence, counter stays at zero, no throw. */
    @Test
    public void matchingRowSetsStaySilent() {
        List<String> a = Arrays.asList("USA100", "MEX50");
        List<String> b = Arrays.asList("MEX50", "USA100");
        String category = CalciteParityGuard.compareRowSets(a, b, false);
        assertNull("equal multisets must not diverge", category);

        MetricData m = findDivergence();
        // No divergence => either no metric emitted, or a zero total.
        assertTrue(
            "matching row-sets must not increment the divergence counter",
            m == null || sumLongCounter(m) == 0L);
    }

    /** Same row count but a differing cell value => cell-value divergence,
     *  counter increments, non-strict does NOT throw. */
    @Test
    public void divergingCellValueFiresGuardNonStrict() {
        List<String> legacy = Arrays.asList("USA100", "MEX50");
        List<String> calcite = Arrays.asList("USA999", "MEX50");
        String category =
            CalciteParityGuard.compareRowSets(legacy, calcite, false);
        assertEquals("cell-value", category);

        MetricData m = findDivergence();
        assertNotNull(
            "diverging cell value must increment mondrian.calcite.divergence",
            m);
        assertTrue(sumLongCounter(m) >= 1L);
        assertTrue("expected cell-value detail attribute",
            hasDetail(m, "cell-value"));
    }

    /** Differing row count => row-count divergence category. */
    @Test
    public void divergingRowCountFiresGuard() {
        List<String> legacy = Arrays.asList("USA100", "MEX50");
        List<String> calcite = Collections.singletonList("USA100");
        String category =
            CalciteParityGuard.compareRowSets(legacy, calcite, false);
        assertEquals("row-count", category);
        assertTrue(hasDetail(findDivergence(), "row-count"));
    }

    /** Strict mode hard-fails on a real divergence (so CI can gate on it),
     *  AND still records the counter before throwing. */
    @Test
    public void strictModeThrowsOnDivergence() {
        List<String> legacy = Arrays.asList("USA100");
        List<String> calcite = Arrays.asList("USA200");
        try {
            CalciteParityGuard.compareRowSets(legacy, calcite, true);
            fail("strict mode must throw on a divergence");
        } catch (mondrian.olap.MondrianException expected) {
            assertTrue(
                "exception should reference the parity divergence",
                expected.getMessage().toLowerCase().contains("divergence"));
        }
        MetricData m = findDivergence();
        assertNotNull("strict mode must still record the divergence", m);
        assertTrue(sumLongCounter(m) >= 1L);
    }

    /** Strict mode does NOT throw when the row-sets match. */
    @Test
    public void strictModeSilentOnMatch() {
        List<String> a = Arrays.asList("X1");
        List<String> b = Arrays.asList("X1");
        assertNull(CalciteParityGuard.compareRowSets(a, b, true));
    }

    /** Flags default OFF so the hot path is zero-overhead unless opted in. */
    @Test
    public void flagsDefaultOff() {
        // No system property set in this test => both must read false.
        assertFalse(
            "parityCheck must default OFF", CalciteParityGuard.isEnabled());
        assertFalse(
            "strict must default OFF", CalciteParityGuard.isStrict());
    }

    // ---------- helpers ----------

    private MetricData findDivergence() {
        Collection<MetricData> metrics = reader.collectAllMetrics();
        for (MetricData m : metrics) {
            if ("mondrian.calcite.divergence".equals(m.getName())) {
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

    private static boolean hasDetail(MetricData m, String detail) {
        if (m == null) {
            return false;
        }
        for (LongPointData p : m.getLongSumData().getPoints()) {
            if (detail.equals(p.getAttributes().get(
                AttributeKey.stringKey(
                    "mondrian.calcite.divergence.detail"))))
            {
                return true;
            }
        }
        return false;
    }
}

// End CalciteParityGuardTest.java
