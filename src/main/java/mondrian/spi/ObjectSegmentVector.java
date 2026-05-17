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
 * A {@link SegmentVector} specialised for boxed-{@link Object}-valued
 * cells. Used for String, BigDecimal, and other non-primitive measures
 * that flow through Mondrian's dense-segment path. See
 * {@link DoubleSegmentVector} for the access-pattern rationale.
 */
public interface ObjectSegmentVector extends SegmentVector {

    /**
     * Returns the cell values as an {@code Object[]} of length
     * {@link #size()}. Implementations backed by a native array MAY
     * return the underlying array directly; implementations backed by
     * off-heap storage MUST materialise a copy.
     */
    Object[] toObjectArray();
}

// End ObjectSegmentVector.java
