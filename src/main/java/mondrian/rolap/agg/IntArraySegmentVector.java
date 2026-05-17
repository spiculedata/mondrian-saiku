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

import mondrian.spi.IntSegmentVector;

import java.util.BitSet;

/**
 * Reference {@link IntSegmentVector} implementation backed by a native
 * {@code int[]} and a null-indicator {@link BitSet}. See
 * {@link DoubleArraySegmentVector} for the access-pattern rationale.
 */
public final class IntArraySegmentVector implements IntSegmentVector {
    private static final long serialVersionUID = 1L;

    private final int[] values;
    private final BitSet nullValues;

    public IntArraySegmentVector(int[] values, BitSet nullValues) {
        if (values == null) {
            throw new IllegalArgumentException("values is null");
        }
        if (nullValues == null) {
            throw new IllegalArgumentException("nullValues is null");
        }
        this.values = values;
        this.nullValues = nullValues;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public int getInt(int i) {
        return values[i];
    }

    @Override
    public Object getObject(int i) {
        int v = values[i];
        if (v == 0 && nullValues.get(i)) {
            return null;
        }
        return v;
    }

    @Override
    public boolean isNull(int i) {
        return values[i] == 0 && nullValues.get(i);
    }

    @Override
    public int[] toIntArray() {
        return values;
    }
}

// End IntArraySegmentVector.java
