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

/**
 * A {@link SegmentVector} specialised for {@code double}-valued cells.
 *
 * <p>Provides a type-specific {@link #getDouble(int)} accessor for
 * per-cell reads and a {@link #toDoubleArray()} accessor for callers
 * that need to populate a native {@code double[]} (Mondrian's
 * {@code DenseDoubleSegmentDataset} does this once per cache hit to
 * keep its cell-read hot path JIT-friendly).
 */
public interface DoubleSegmentVector extends SegmentVector {

    /** Returns the double value at offset {@code i}. */
    double getDouble(int i);

    /**
     * Returns the cell values as a {@code double[]} of length
     * {@link #size()}. Implementations backed by a native array MAY
     * return the underlying array directly (no defensive copy);
     * implementations backed by off-heap storage MUST materialise a
     * copy. Caller treats the returned array as owned and may mutate it.
     */
    double[] toDoubleArray();
}

// End DoubleSegmentVector.java
