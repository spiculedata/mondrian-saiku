/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2011-2014 Pentaho and others
// All Rights Reserved.
*/
package mondrian.spi;

import mondrian.rolap.CellKey;

import java.io.Serializable;
import java.util.*;

/**
 * SegmentBody is the object which contains the cached data of a
 * Segment. They are stored inside a {@link mondrian.spi.SegmentCache}
 * and can be retrieved by a {@link SegmentHeader} key.
 *
 * <p>The segment body objects are immutable and fully serializable.
 *
 * @author LBoudreau
 */
public interface SegmentBody extends Serializable {
    /**
     * Converts contents of this segment into a cellkey/value map. Use only
     * for sparse segments.
     *
     * @return Map containing cell values keyed by their coordinates
     */
    Map<CellKey, Object> getValueMap();

    /**
     * Returns the value vector for this dense segment.
     *
     * <p>The concrete vector subtype reflects the underlying value
     * type — {@link DoubleSegmentVector} for double-valued measures,
     * {@link IntSegmentVector} for int, {@link ObjectSegmentVector}
     * for boxed objects (String, BigDecimal, ...). Callers narrow with
     * {@code instanceof} before calling type-specific accessors such
     * as {@link DoubleSegmentVector#toDoubleArray()}.
     *
     * <p>Use only for dense segments. Sparse segments return their
     * payload via {@link #getValueMap()}.
     *
     * @return A type-specific {@link SegmentVector}
     */
    SegmentVector getValues();

    /**
     * Returns a bit-set indicating whether values are null. The ordinals in
     * the bit-set correspond to the indexes in the array returned from
     * {@link #getValueArray()}.
     *
     * <p>Use only for dense segments of native values.</p>
     *
     * @return Indicators
     */
    BitSet getNullValueIndicators();

    /**
     * Returns the cached axis value sets to be used as an
     * initializer for the segment's axis.
     *
     * @return An array of SortedSets which was cached previously.
     */
    SortedSet<Comparable>[] getAxisValueSets();

    /**
     * Returns an array of boolean values which identify which
     * axis of the cached segment contained null values.
     *
     * @return An array of boolean values.
     */
    boolean[] getNullAxisFlags();
}

// End SegmentBody.java
