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
 * A {@link SegmentVector} specialised for {@code int}-valued cells.
 * See {@link DoubleSegmentVector} for the access-pattern rationale.
 */
public interface IntSegmentVector extends SegmentVector {

    /** Returns the int value at offset {@code i}. */
    int getInt(int i);

    /**
     * Returns the cell values as an {@code int[]} of length
     * {@link #size()}. Implementations backed by a native array MAY
     * return the underlying array directly; implementations backed by
     * off-heap storage MUST materialise a copy.
     */
    int[] toIntArray();
}

// End IntSegmentVector.java
