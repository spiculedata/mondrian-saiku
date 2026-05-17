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

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.NullCheckingForGet;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;

/**
 * Diagnostic micro-benchmark for #37 Phase 1. Pinpoints which layer of
 * the {@code Float8Vector.get(i)} call chain accounts for the ~2.6×
 * slowdown vs {@code double[i]} surfaced by
 * {@link ArrowDoubleSegmentVectorTest#perfSanityFloor_arrowWithin10xOfArray}.
 *
 * <h3>What this measures</h3>
 *
 * <p>Same dataset, four read paths:
 * <ol>
 *   <li><strong>Baseline:</strong> {@code double[i]} — on-heap array, JIT-vectorisable.</li>
 *   <li><strong>Float8Vector.get(i):</strong> the wrapper API. Bytecode shows
 *       it does (a) static field read of {@code NullCheckingForGet.NULL_CHECKING_ENABLED},
 *       (b) virtual call to {@code isSet(i)} + validity-bitmap lookup,
 *       (c) throws on null, (d) {@code valueBuffer.getDouble(i*8)}.</li>
 *   <li><strong>Direct ArrowBuf.getDouble:</strong> bypasses the
 *       Float8Vector wrapper entirely. Still off-heap via Unsafe, but
 *       no per-call null check, no virtual {@code isSet} dispatch.</li>
 *   <li><strong>Bulk copy + on-heap iterate:</strong> one off-heap →
 *       on-heap copy via {@code ArrowBuf.getBytes()}, then sum from a
 *       Java {@code double[]}. Tests the hypothesis that per-call
 *       off-heap overhead dominates and bulk-copy-then-iterate wins.</li>
 * </ol>
 *
 * <h3>How to read the output</h3>
 *
 * <p>Each scenario prints elapsed time and a ratio vs baseline. Run with:
 * <pre>
 *   mvn test -Dtest=ArrowReadPerfDiagnosticTest
 *   mvn test -Dtest=ArrowReadPerfDiagnosticTest \
 *     -DargLine="-Darrow.enable_null_check_for_get=false"
 * </pre>
 *
 * <p>Comparing the two runs isolates the null-check overhead. The
 * baseline / direct-ArrowBuf / bulk-copy numbers are independent of
 * the flag.
 *
 * <h3>Not a JMH benchmark</h3>
 *
 * <p>This is a quick directional probe. JIT effects, GC pauses, and
 * single-pass timing can skew results by 10-20%. Treat ratios as
 * ballpark, not exact. JMH would be preferred for shipped perf claims;
 * not warranted for a diagnostic.
 */
public class ArrowReadPerfDiagnosticTest {

    private static final int N = 100_000;
    private static final int ITERATIONS = 1_000;
    private static final long SEED = 0xC0FFEEL;

    private static BufferAllocator allocator;
    private static double[] heapArray;
    private static Float8Vector arrowVector;
    private static ArrowBuf arrowBuf;
    private static double[] bulkCopyBuffer;

    @BeforeClass
    public static void setUp() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        heapArray = new double[N];
        arrowVector = new Float8Vector("v", allocator);
        arrowVector.allocateNew(N);

        Random rng = new Random(SEED);
        for (int i = 0; i < N; i++) {
            double v = rng.nextDouble();
            heapArray[i] = v;
            arrowVector.setSafe(i, v);
        }
        arrowVector.setValueCount(N);
        arrowBuf = arrowVector.getDataBuffer();
        bulkCopyBuffer = new double[N];

        // Warm-up: JIT every path before measuring.
        warmup();
    }

    @AfterClass
    public static void tearDown() {
        if (arrowVector != null) {
            arrowVector.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    private static void warmup() {
        for (int w = 0; w < 20; w++) {
            sumBaseline(10);
            sumFloat8VectorGet(10);
            sumArrowBufDirect(10);
            sumBulkCopy(10);
        }
    }

    /** Scenario 1: heap {@code double[]} — the bar everything else is measured against. */
    private static double sumBaseline(int iter) {
        double t = 0;
        for (int it = 0; it < iter; it++) {
            for (int i = 0; i < N; i++) {
                t += heapArray[i];
            }
        }
        return t;
    }

    /** Scenario 2: full Float8Vector.get(i) — current ArrowDoubleSegmentVector hot path. */
    private static double sumFloat8VectorGet(int iter) {
        double t = 0;
        for (int it = 0; it < iter; it++) {
            for (int i = 0; i < N; i++) {
                t += arrowVector.get(i);
            }
        }
        return t;
    }

    /**
     * Scenario 3: direct off-heap read via ArrowBuf — bypasses the
     * Float8Vector wrapper's null check + isSet() call.
     */
    private static double sumArrowBufDirect(int iter) {
        double t = 0;
        for (int it = 0; it < iter; it++) {
            for (int i = 0; i < N; i++) {
                t += arrowBuf.getDouble((long) i << 3);
            }
        }
        return t;
    }

    /**
     * Scenario 4: one bulk off-heap → on-heap copy via ArrowBuf.getBytes(),
     * then sum from the heap copy. Tests whether per-call off-heap
     * overhead dominates and bulk-then-iterate wins.
     */
    private static double sumBulkCopy(int iter) {
        double t = 0;
        for (int it = 0; it < iter; it++) {
            // Materialise the off-heap buffer into a heap double[] in one shot.
            // ArrowBuf doesn't expose getDoubles(double[]) directly, so we
            // round-trip via byte[] → ByteBuffer → asDoubleBuffer().
            byte[] bytes = new byte[N * 8];
            arrowBuf.getBytes(0L, bytes);
            java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.nativeOrder())
                .asDoubleBuffer()
                .get(bulkCopyBuffer);
            for (int i = 0; i < N; i++) {
                t += bulkCopyBuffer[i];
            }
        }
        return t;
    }

    @Test
    public void diagnose() {
        long base = time(() -> sumBaseline(ITERATIONS));
        long vget = time(() -> sumFloat8VectorGet(ITERATIONS));
        long buf = time(() -> sumArrowBufDirect(ITERATIONS));
        long bulk = time(() -> sumBulkCopy(ITERATIONS));

        System.err.println();
        System.err.println(
            "=== Arrow per-cell read diagnostic (N=" + N
                + " cells, " + ITERATIONS + " iters) ===");
        System.err.println(
            "NULL_CHECKING_ENABLED = "
                + NullCheckingForGet.NULL_CHECKING_ENABLED
                + " (set -Darrow.enable_null_check_for_get=false to disable "
                + "before the first Arrow class loads)");
        System.err.println();
        System.err.printf(
            "  [1] heap double[i]               %7.1f ms   (baseline = 1.00x)%n",
            base / 1e6);
        System.err.printf(
            "  [2] Float8Vector.get(i)          %7.1f ms   ratio = %.2fx%n",
            vget / 1e6, (double) vget / base);
        System.err.printf(
            "  [3] ArrowBuf.getDouble(i << 3)   %7.1f ms   ratio = %.2fx%n",
            buf / 1e6, (double) buf / base);
        System.err.printf(
            "  [4] bulk-copy + heap sum         %7.1f ms   ratio = %.2fx%n",
            bulk / 1e6, (double) bulk / base);
        System.err.println();
        System.err.println(
            "Interpretation: [3] - [1] is off-heap-access overhead. "
                + "[2] - [3] is the Float8Vector wrapper overhead (null "
                + "check + isSet dispatch). [4] - [1] is the bulk-copy "
                + "pattern's net cost — if [4] is close to [1], "
                + "bulk-then-iterate is the right access pattern for "
                + "ArrowDoubleSegmentVector's hot path.");
    }

    private static long time(Runnable r) {
        long s = System.nanoTime();
        r.run();
        return System.nanoTime() - s;
    }
}

// End ArrowReadPerfDiagnosticTest.java
