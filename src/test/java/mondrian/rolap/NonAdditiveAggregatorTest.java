/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #104: the non-additive leaf aggregators (median / percentile) are
 * registered, carry their fraction, and are flagged non-rollupable while the
 * additive aggregators stay rollupable.
 */
public class NonAdditiveAggregatorTest {

    @Test
    public void medianIsPercentileHalfAndNonRollupable() {
        assertInstanceOf(
            RolapAggregator.PercentileAggregator.class,
            RolapAggregator.Median);
        assertEquals(
            0.5,
            ((RolapAggregator.PercentileAggregator) RolapAggregator.Median)
                .fraction,
            0.0001);
        assertFalse(RolapAggregator.Median.isRollupable(),
            "median must not be rollupable");
    }

    @Test
    public void percentileIsRegisteredAndNonRollupable() {
        assertInstanceOf(
            RolapAggregator.PercentileAggregator.class,
            RolapAggregator.Percentile);
        assertFalse(RolapAggregator.Percentile.isRollupable());
    }

    @Test
    public void additiveAggregatorsStayRollupable() {
        assertTrue(RolapAggregator.Sum.isRollupable());
        assertTrue(RolapAggregator.Count.isRollupable());
        assertTrue(RolapAggregator.Min.isRollupable());
        assertTrue(RolapAggregator.Max.isRollupable());
        assertTrue(RolapAggregator.Avg.isRollupable());
        // Distinct-count stays rollupable here (its narrower limits are
        // enforced elsewhere); only the non-additive leaf aggs flip the flag.
        assertTrue(RolapAggregator.DistinctCount.isRollupable());
    }

    @Test
    public void medianResolvesByNameInEnumeration() {
        assertEquals(
            RolapAggregator.Median,
            RolapAggregator.enumeration.getValue("median", true));
    }

    @Test
    public void medianIsExcludedFromInMemoryRollup() {
        // The in-memory segment rollup proposal (FastBatchingCellReader)
        // requires BOTH isRollupable() and supportsFastAggregates(). Both
        // are false here, so median/percentile are never rolled up from a
        // cached finer-grain segment — they reload at the exact grain.
        assertFalse(RolapAggregator.Median.isRollupable());
        assertFalse(
            RolapAggregator.Median.supportsFastAggregates(
                mondrian.spi.Dialect.Datatype.Numeric));
        assertFalse(
            RolapAggregator.Percentile.supportsFastAggregates(
                mondrian.spi.Dialect.Datatype.Numeric));
    }

    /**
     * #104 edge: a median/percentile measure must also be excluded from
     * agg-table substitution. Agg-table substitution is currently dead (no agg
     * stars are registered), but the {@code AggregationManager} guard keys off
     * the SAME {@link RolapAggregator#isRollupable()} signal as the in-memory
     * rollup guard — a precomputed aggregate cannot serve a non-rollupable
     * leaf aggregator (no percentile-of-percentiles). This pins the signal the
     * agg-table guard relies on, so a future re-enable cannot silently bypass
     * #104.
     */
    @Test
    public void percentileExcludedFromAggTables() {
        assertFalse(RolapAggregator.Median.isRollupable(),
            "median is non-rollupable → agg-table guard refuses substitution");
        assertFalse(RolapAggregator.Percentile.isRollupable(),
            "percentile is non-rollupable → agg-table guard refuses it");
        // An additive measure is NOT excluded (the dead fast path stays
        // available to it if re-enabled).
        assertTrue(RolapAggregator.Sum.isRollupable());
    }

    @Test
    public void percentileAggregatorEmitsOrderedSetSql() {
        RolapAggregator p90 =
            new RolapAggregator.PercentileAggregator("percentile", 99, 0.9);
        assertEquals(
            "percentile_cont(0.9) within group (order by \"x\")",
            p90.getExpression("\"x\""));
    }
}
