/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.spi;

import java.io.Serializable;

/**
 * Columnar value vector underpinning a {@link SegmentBody}'s storage.
 * Vendor-neutral abstraction: today's reference implementations wrap Java
 * primitive arrays; future implementations may wrap Apache Arrow vectors,
 * memory-mapped buffers, or other columnar formats without breaking the
 * {@link SegmentBody} SPI.
 *
 * <p>SegmentVector is the boundary at which a body is serialized into /
 * deserialized from the {@link SegmentCache} and at which alternative
 * storage backends plug in. Mondrian's internal cell-read hot path does
 * NOT go through this interface — that path uses {@code
 * SegmentDataset.getDouble(CellKey)} with a native {@code double[]} for
 * JIT-friendliness, populated once per cache hit by calling
 * {@link DoubleSegmentVector#toDoubleArray()} (or the int / object
 * peers) and never re-querying the vector per cell.
 *
 * <p>Implementations must be {@link Serializable} so they round-trip
 * cleanly through external {@link SegmentCache} implementations
 * (Infinispan, Hazelcast, etc.).
 *
 * @see DoubleSegmentVector
 * @see IntSegmentVector
 * @see ObjectSegmentVector
 */
public interface SegmentVector extends Serializable {

    /** Number of cells in this vector (product of axis cardinalities). */
    int size();

    /** Returns the value at offset {@code i}, or {@code null} if absent. */
    Object getObject(int i);

    /** Returns {@code true} iff the cell at offset {@code i} is null. */
    boolean isNull(int i);
}

// End SegmentVector.java
