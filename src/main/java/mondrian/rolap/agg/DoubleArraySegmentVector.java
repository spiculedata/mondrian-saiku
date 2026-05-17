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

import java.util.BitSet;

/**
 * Reference {@link DoubleSegmentVector} implementation backed by a
 * native {@code double[]} and a null-indicator {@link BitSet}. Zero
 * allocation overhead: {@link #toDoubleArray()} returns the wrapped
 * array directly (no defensive copy).
 *
 * <p>This is the JIT-friendly default storage for Mondrian's
 * {@code DenseDoubleSegmentBody}. Alternative {@link DoubleSegmentVector}
 * implementations (Arrow-backed, memory-mapped) plug in via the SPI
 * without touching this class.
 */
public final class DoubleArraySegmentVector implements DoubleSegmentVector {
    private static final long serialVersionUID = 1L;

    private final double[] values;
    private final BitSet nullValues;

    public DoubleArraySegmentVector(double[] values, BitSet nullValues) {
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
    public double getDouble(int i) {
        return values[i];
    }

    @Override
    public Object getObject(int i) {
        double v = values[i];
        if (v == 0d && nullValues.get(i)) {
            return null;
        }
        return v;
    }

    @Override
    public boolean isNull(int i) {
        return values[i] == 0d && nullValues.get(i);
    }

    @Override
    public double[] toDoubleArray() {
        return values;
    }
}

// End DoubleArraySegmentVector.java
