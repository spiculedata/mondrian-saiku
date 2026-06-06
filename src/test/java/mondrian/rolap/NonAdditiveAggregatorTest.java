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
    public void percentileAggregatorEmitsOrderedSetSql() {
        RolapAggregator p90 =
            new RolapAggregator.PercentileAggregator("percentile", 99, 0.9);
        assertEquals(
            "percentile_cont(0.9) within group (order by \"x\")",
            p90.getExpression("\"x\""));
    }
}
