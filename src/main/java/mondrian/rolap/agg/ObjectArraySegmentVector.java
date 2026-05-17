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

import mondrian.spi.ObjectSegmentVector;

/**
 * Reference {@link ObjectSegmentVector} implementation backed by a
 * native {@code Object[]}. Nulls are represented by a {@code null}
 * entry in the array (no separate validity bitmap needed for boxed
 * values). See {@link DoubleArraySegmentVector} for the access-pattern
 * rationale.
 */
public final class ObjectArraySegmentVector implements ObjectSegmentVector {
    private static final long serialVersionUID = 1L;

    private final Object[] values;

    public ObjectArraySegmentVector(Object[] values) {
        if (values == null) {
            throw new IllegalArgumentException("values is null");
        }
        this.values = values;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Object getObject(int i) {
        return values[i];
    }

    @Override
    public boolean isNull(int i) {
        return values[i] == null;
    }

    @Override
    public Object[] toObjectArray() {
        return values;
    }
}

// End ObjectArraySegmentVector.java
