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

import mondrian.spi.DoubleSegmentVector;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.util.BitSet;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Spike validation tests for {@link ArrowDoubleSegmentVector} (#37
 * Phase 1). Pins three claims:
 *
 * <ol>
 *   <li><strong>Equivalence:</strong> array-backed and Arrow-backed
 *       implementations of {@link DoubleSegmentVector} return
 *       byte-identical results for every method when fed identical
 *       input.</li>
 *   <li><strong>Spike-scope limitation:</strong> Arrow-backed body
 *       refuses Java serialization with a clear error (production
 *       work to land Arrow IPC round-trip is future scope).</li>
 *   <li><strong>Perf sanity:</strong> per-cell read on the Arrow
 *       backing is in the same order of magnitude as the array
 *       backing. Not a benchmark — a sanity floor to catch
 *       catastrophic regressions before they reach the cell
 *       evaluator hot path.</li>
 * </ol>
 */
public class ArrowDoubleSegmentVectorTest {

    /**
     * Equivalence on a deterministic mixed-null fixture. Every accessor
     * of {@link DoubleSegmentVector} returns the same answer from both
     * implementations.
     */
    @Test
    public void arrowAndArrayBackingsReturnIdenticalReads() {
        double[] values = {
            1.5, 2.5, 0.0, 4.5, -7.25, 100.0, 0.0, 999.999
        };
        BitSet nulls = new BitSet();
        nulls.set(2);  // 0.0 + bit set → null
        nulls.set(6);  // 0.0 + bit set → null

        DoubleSegmentVector array =
            new DoubleArraySegmentVector(values.clone(), (BitSet) nulls.clone());
        DoubleSegmentVector arrow =
            new ArrowDoubleSegmentVector(values.clone(), (BitSet) nulls.clone());

        assertEquals("size mismatch", array.size(), arrow.size());
        for (int i = 0; i < array.size(); i++) {
            assertEquals(
                "isNull mismatch at " + i, array.isNull(i), arrow.isNull(i));
            assertEquals(
                "getObject mismatch at " + i,
                array.getObject(i), arrow.getObject(i));
            // getDouble only safe to compare directly when not null
            if (!array.isNull(i)) {
                assertEquals(
                    "getDouble mismatch at " + i,
                    array.getDouble(i), arrow.getDouble(i), 0d);
            }
        }
        // Bulk copy: arrays must match element-for-element
        assertArrayEquals(
            "toDoubleArray() output mismatch",
            array.toDoubleArray(), arrow.toDoubleArray(), 0d);
    }

    /**
     * Stress equivalence on a larger random fixture — exercises a
     * realistic-shaped segment with mixed nulls.
     */
    @Test
    public void arrowAndArrayMatchOnRandomFixture() {
        final int N = 10_000;
        Random rng = new Random(42L);
        double[] values = new double[N];
        BitSet nulls = new BitSet(N);
        for (int i = 0; i < N; i++) {
            // 10% nulls; non-null values uniform in [-1000, 1000]
            if (rng.nextDouble() < 0.10) {
                values[i] = 0d;
                nulls.set(i);
            } else {
                values[i] = (rng.nextDouble() * 2_000d) - 1_000d;
            }
        }

        DoubleSegmentVector array =
            new DoubleArraySegmentVector(values.clone(), (BitSet) nulls.clone());
        DoubleSegmentVector arrow =
            new ArrowDoubleSegmentVector(values.clone(), (BitSet) nulls.clone());

        for (int i = 0; i < N; i++) {
            assertEquals(array.isNull(i), arrow.isNull(i));
            if (array.isNull(i)) {
                assertNull(arrow.getObject(i));
            } else {
                assertEquals(array.getDouble(i), arrow.getDouble(i), 0d);
            }
        }
    }

    /**
     * Spike-scope: serialization is not yet implemented. Must fail
     * loudly with a clear message rather than silently corrupting
     * external {@code SegmentCache} state.
     */
    @Test
    public void serializationStubRefusesWithClearMessage() throws Exception {
        ArrowDoubleSegmentVector vec = new ArrowDoubleSegmentVector(
            new double[] {1.0, 2.0, 3.0}, new BitSet());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(vec);
            fail("expected NotSerializableException for spike-scope impl");
        } catch (NotSerializableException expected) {
            assertTrue(
                "message should explain the spike-scope limitation — got: "
                    + expected.getMessage(),
                expected.getMessage().contains("spike"));
        }
    }

    /**
     * Edge case: empty vector. Both implementations must handle a
     * zero-length segment without throwing.
     */
    @Test
    public void emptyVectorIsValid() {
        ArrowDoubleSegmentVector vec =
            new ArrowDoubleSegmentVector(new double[0], new BitSet());
        assertEquals(0, vec.size());
        assertArrayEquals(new double[0], vec.toDoubleArray(), 0d);
    }

    @Test
    public void rejectsNullConstructorArgs() {
        try {
            new ArrowDoubleSegmentVector(null, new BitSet());
            fail("expected IAE for null values");
        } catch (IllegalArgumentException expected) { }
        try {
            new ArrowDoubleSegmentVector(new double[0], null);
            fail("expected IAE for null nullValues");
        } catch (IllegalArgumentException expected) { }
    }

    /**
     * Perf sanity floor (not a benchmark — surrogate JMH would be
     * preferred but adds dep weight for a spike).
     *
     * <p>Reads 100M doubles from a 100k-cell vector through each
     * backing. The Arrow path is expected to be slower per cell — JIT
     * inlines {@code double[i]} better than {@code Float8Vector.get(i)}
     * which crosses off-heap memory and checks the validity bit. The
     * floor: Arrow must be within <strong>10×</strong> the array path.
     * Anything worse than 10× means we've hit a JIT pessimisation
     * (e.g. boxing, JNI per access, off-heap fence) and the spike
     * should report "Phase 1 not perf-viable as-is".
     *
     * <p>Logs both timings so the test output captures the actual ratio
     * for the spike report — even when the floor passes.
     */
    @Test
    public void perfSanityFloor_arrowWithin10xOfArray() {
        final int N = 100_000;
        final int iterations = 1_000;
        Random rng = new Random(0xC0FFEEL);
        double[] values = new double[N];
        BitSet nulls = new BitSet(N);
        for (int i = 0; i < N; i++) {
            values[i] = rng.nextDouble();
        }

        DoubleSegmentVector array =
            new DoubleArraySegmentVector(values.clone(), (BitSet) nulls.clone());
        DoubleSegmentVector arrow =
            new ArrowDoubleSegmentVector(values.clone(), (BitSet) nulls.clone());

        // Warm-up: JIT both paths before measuring.
        sumGetDouble(array, 10);
        sumGetDouble(arrow, 10);

        long arrayStart = System.nanoTime();
        double arraySum = sumGetDouble(array, iterations);
        long arrayElapsed = System.nanoTime() - arrayStart;

        long arrowStart = System.nanoTime();
        double arrowSum = sumGetDouble(arrow, iterations);
        long arrowElapsed = System.nanoTime() - arrowStart;

        // Sums should agree (both reads come from identical input)
        assertEquals(
            "sums diverged between array and arrow — equivalence broken",
            arraySum, arrowSum, 0d);

        double ratio = (double) arrowElapsed / (double) arrayElapsed;
        // Surface the timing for the spike report
        System.err.printf(
            "[ArrowDoubleSegmentVector perf-sanity] N=%d iter=%d "
                + "array=%.1fms arrow=%.1fms ratio=%.2fx%n",
            N, iterations,
            arrayElapsed / 1e6, arrowElapsed / 1e6, ratio);

        assertTrue(
            String.format(
                "Arrow per-cell read too slow vs array (ratio %.2fx > 10x)",
                ratio),
            ratio < 10.0);
        // Floor only — no upper-bound floor on the array path.
        assertFalse("array path produced NaN", Double.isNaN(arraySum));
    }

    /** Sums via {@code getDouble(i)} — the cell-evaluator hot path. */
    private static double sumGetDouble(DoubleSegmentVector v, int iterations) {
        double total = 0d;
        int n = v.size();
        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < n; i++) {
                total += v.getDouble(i);
            }
        }
        return total;
    }
}

// End ArrowDoubleSegmentVectorTest.java
