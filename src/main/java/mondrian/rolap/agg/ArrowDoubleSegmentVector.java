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

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.BitSet;

/**
 * Apache Arrow-backed {@link DoubleSegmentVector} implementation.
 * Wraps a {@link Float8Vector} (off-heap, columnar) instead of a Java
 * {@code double[]}. Spike target for #37 Phase 1 — proves the
 * vendor-neutral {@link mondrian.spi.SegmentVector} SPI introduced in
 * Phase 0 can host a real non-array implementation without breaking
 * any caller of {@link mondrian.spi.SegmentBody}.
 *
 * <h3>Status: SPIKE</h3>
 *
 * <p>This class is the foundation for #37 Phase 2+ work (SIMD bulk
 * rollups via Arrow Compute, memory-mapped persistence, cross-process
 * Arrow Flight sharing). It is NOT yet wired into Mondrian's segment
 * loader as a production code path. Construct it directly from tests
 * or from a future config-flag-gated path.
 *
 * <h3>Lifecycle</h3>
 *
 * <p>Arrow vectors hold off-heap memory and must be closed. This
 * implementation uses a shared static {@link RootAllocator} so the
 * spike avoids per-instance allocator lifecycle ceremony. The
 * {@link Float8Vector} is allocated at construction and never freed
 * — off-heap memory accumulates until JVM exit. <strong>Suitable for
 * benchmarking and tests; not for production use without a lifecycle
 * story (Cleaner, AutoCloseable on body, or pooled allocator).</strong>
 *
 * <h3>Serialization</h3>
 *
 * <p>{@link Float8Vector} is not {@link java.io.Serializable}. A
 * production-grade implementation would serialize via Arrow IPC. For
 * the spike, {@code writeObject}/{@code readObject} throw — making the
 * spike vector incompatible with external {@link mondrian.spi.SegmentCache}
 * implementations (Infinispan, Hazelcast). The in-JVM
 * {@code MemorySegmentCache} (default) does not invoke serialization
 * and works unchanged.
 *
 * <h3>{@code toDoubleArray()} contract</h3>
 *
 * <p>Unlike {@link DoubleArraySegmentVector#toDoubleArray()} (zero-copy,
 * returns the raw backing array), this implementation must materialise
 * a {@code double[]} by copying off-heap → heap. Cost is O(n) per call;
 * call sites that need a {@code double[]} (e.g. Mondrian's
 * {@code SegmentBuilder} for hot-path {@code SegmentDataset}
 * construction) pay this once per cache hit. Cell-by-cell reads should
 * go through {@link #getDouble(int)} or {@link #getObject(int)} to
 * avoid the copy.
 */
public final class ArrowDoubleSegmentVector implements DoubleSegmentVector {
    private static final long serialVersionUID = 1L;

    /**
     * JVM-wide allocator. Production deployments should pool allocators
     * per-cache-region with explicit close semantics; spike trades that
     * for simplicity.
     */
    private static final BufferAllocator ALLOCATOR =
        new RootAllocator(Long.MAX_VALUE);

    /** Off-heap value storage. Not serializable. */
    private transient Float8Vector vector;

    /** Cached size — kept on-heap for a faster {@link #size()} call. */
    private final int size;

    /**
     * Creates an Arrow-backed vector by copying the input array
     * element-by-element into off-heap Arrow memory, applying the
     * supplied null bitmap.
     *
     * <p>Caller retains ownership of the input arrays; we copy. The
     * allocated Arrow buffer is owned by the shared
     * {@link #ALLOCATOR}.
     */
    public ArrowDoubleSegmentVector(double[] values, BitSet nullValues) {
        if (values == null) {
            throw new IllegalArgumentException("values is null");
        }
        if (nullValues == null) {
            throw new IllegalArgumentException("nullValues is null");
        }
        this.size = values.length;
        this.vector = new Float8Vector("values", ALLOCATOR);
        this.vector.allocateNew(size);
        for (int i = 0; i < size; i++) {
            if (values[i] == 0d && nullValues.get(i)) {
                vector.setNull(i);
            } else {
                vector.setSafe(i, values[i]);
            }
        }
        this.vector.setValueCount(size);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public double getDouble(int i) {
        return vector.get(i);
    }

    @Override
    public Object getObject(int i) {
        if (vector.isNull(i)) {
            return null;
        }
        return vector.get(i);
    }

    @Override
    public boolean isNull(int i) {
        return vector.isNull(i);
    }

    /**
     * Materialises a {@code double[]} of length {@link #size()} by
     * copying values out of the off-heap Arrow buffer. Null cells are
     * written as {@code 0d} (matching the contract of the array-backed
     * reference implementation).
     */
    @Override
    public double[] toDoubleArray() {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = vector.isNull(i) ? 0d : vector.get(i);
        }
        return out;
    }

    // ---------- serialization stub ----------
    // Arrow IPC round-trip would go here for production. For the spike,
    // the in-JVM MemorySegmentCache path doesn't serialize, so a stub
    // suffices.

    private void writeObject(ObjectOutputStream oos) throws IOException {
        throw new java.io.NotSerializableException(
            "ArrowDoubleSegmentVector is a spike implementation that does "
            + "not yet support Java serialization. Production use through "
            + "external SegmentCache implementations (Infinispan, Hazelcast) "
            + "requires Arrow IPC round-trip — out of scope for #37 Phase 1.");
    }

    private void readObject(ObjectInputStream ois)
        throws IOException, ClassNotFoundException
    {
        throw new java.io.NotSerializableException(
            "ArrowDoubleSegmentVector is a spike implementation that does "
            + "not yet support Java serialization.");
    }
}

// End ArrowDoubleSegmentVector.java
