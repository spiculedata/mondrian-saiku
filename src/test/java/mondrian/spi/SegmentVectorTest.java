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

import mondrian.rolap.agg.DoubleArraySegmentVector;
import mondrian.rolap.agg.IntArraySegmentVector;
import mondrian.rolap.agg.ObjectArraySegmentVector;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.BitSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link SegmentVector} reference implementations
 * ({@link DoubleArraySegmentVector}, {@link IntArraySegmentVector},
 * {@link ObjectArraySegmentVector}). The vector types are the
 * vendor-neutral abstraction that replaced the old
 * {@code SegmentBody.getValueArray()} cast site — these tests pin the
 * contract every future implementation (Arrow-backed, mmap-backed,
 * etc.) must honour.
 */
public class SegmentVectorTest {

    // ====== double ======

    @Test
    public void doubleVectorBasicAccess() {
        BitSet nulls = new BitSet();
        nulls.set(2);
        DoubleArraySegmentVector v = new DoubleArraySegmentVector(
            new double[] {1.5, 2.5, 0.0, 4.5}, nulls);

        assertEquals(4, v.size());
        assertEquals(1.5, v.getDouble(0), 0d);
        assertEquals(2.5, v.getDouble(1), 0d);
        assertEquals(4.5, v.getDouble(3), 0d);
        assertEquals(Double.valueOf(1.5), v.getObject(0));
        assertNull(
            "offset 2 has value 0 and is in nullValues bitset → null",
            v.getObject(2));
        assertTrue(v.isNull(2));
        assertFalse(v.isNull(0));
    }

    /**
     * Reference impl backed by a native array MUST return the raw
     * underlying array from {@code toDoubleArray()} — no defensive
     * copy. SegmentBuilder relies on this for zero-allocation transition
     * from body to dataset.
     */
    @Test
    public void doubleVectorToDoubleArrayReturnsRawBacking() {
        double[] backing = new double[] {10.0, 20.0, 30.0};
        DoubleArraySegmentVector v =
            new DoubleArraySegmentVector(backing, new BitSet());
        assertSame(
            "DoubleArraySegmentVector#toDoubleArray must return the raw "
                + "backing array (zero-copy contract for reference impl)",
            backing, v.toDoubleArray());
    }

    /**
     * SegmentBody and its vector must round-trip through Java
     * serialization — external SegmentCache impls (Infinispan, Hazelcast)
     * depend on this.
     */
    @Test
    public void doubleVectorRoundTripsThroughJavaSerialization()
        throws Exception
    {
        BitSet nulls = new BitSet();
        nulls.set(1);
        DoubleArraySegmentVector original = new DoubleArraySegmentVector(
            new double[] {3.14, 0.0, 2.71}, nulls);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(original);
        }
        try (ObjectInputStream ois =
                 new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray())))
        {
            DoubleArraySegmentVector restored =
                (DoubleArraySegmentVector) ois.readObject();
            assertEquals(3, restored.size());
            assertEquals(3.14, restored.getDouble(0), 0d);
            assertTrue(restored.isNull(1));
            assertEquals(2.71, restored.getDouble(2), 0d);
        }
    }

    /**
     * Null guards: constructor must reject null array / null bitset
     * instead of NPE'ing on first access.
     */
    @Test
    public void doubleVectorRejectsNullConstructorArgs() {
        try {
            new DoubleArraySegmentVector(null, new BitSet());
            fail("expected IAE for null values");
        } catch (IllegalArgumentException expected) { }
        try {
            new DoubleArraySegmentVector(new double[0], null);
            fail("expected IAE for null nullValues");
        } catch (IllegalArgumentException expected) { }
    }

    // ====== int ======

    @Test
    public void intVectorBasicAccess() {
        BitSet nulls = new BitSet();
        nulls.set(1);
        IntArraySegmentVector v =
            new IntArraySegmentVector(new int[] {7, 0, 9}, nulls);

        assertEquals(3, v.size());
        assertEquals(7, v.getInt(0));
        assertEquals(9, v.getInt(2));
        assertNull(
            "offset 1 is 0 and in nullValues → null",
            v.getObject(1));
        assertTrue(v.isNull(1));
    }

    @Test
    public void intVectorToIntArrayReturnsRawBacking() {
        int[] backing = new int[] {1, 2, 3};
        IntArraySegmentVector v =
            new IntArraySegmentVector(backing, new BitSet());
        assertSame(backing, v.toIntArray());
    }

    // ====== object ======

    @Test
    public void objectVectorBasicAccess() {
        ObjectArraySegmentVector v = new ObjectArraySegmentVector(
            new Object[] {"a", null, "c"});

        assertEquals(3, v.size());
        assertEquals("a", v.getObject(0));
        assertNull(v.getObject(1));
        assertEquals("c", v.getObject(2));
        assertTrue(v.isNull(1));
        assertFalse(v.isNull(0));
    }

    @Test
    public void objectVectorToObjectArrayReturnsRawBacking() {
        Object[] backing = new Object[] {"x", "y"};
        ObjectArraySegmentVector v = new ObjectArraySegmentVector(backing);
        assertSame(backing, v.toObjectArray());
    }

    @Test
    public void objectVectorRejectsNullConstructorArg() {
        try {
            new ObjectArraySegmentVector(null);
            fail("expected IAE for null values");
        } catch (IllegalArgumentException expected) { }
    }

    // ====== SPI assertion ======

    /**
     * Reference impls must be assignable through their type-specific
     * super-interface, so {@code SegmentBuilder} can narrow via {@code
     * instanceof} on body type → cast to the matching vector subtype.
     */
    @Test
    public void referenceImplsImplementTypeSpecificInterfaces() {
        SegmentVector dv =
            new DoubleArraySegmentVector(new double[0], new BitSet());
        SegmentVector iv =
            new IntArraySegmentVector(new int[0], new BitSet());
        SegmentVector ov = new ObjectArraySegmentVector(new Object[0]);

        assertTrue(dv instanceof DoubleSegmentVector);
        assertTrue(iv instanceof IntSegmentVector);
        assertTrue(ov instanceof ObjectSegmentVector);

        // Cross-type check: not double + int + object on same instance.
        assertFalse(dv instanceof IntSegmentVector);
        assertFalse(iv instanceof DoubleSegmentVector);

        // The bulk-array contract: types must round-trip without copy
        assertArrayEquals(new double[0],
            ((DoubleSegmentVector) dv).toDoubleArray(), 0d);
        assertArrayEquals(new int[0],
            ((IntSegmentVector) iv).toIntArray());
        assertArrayEquals(new Object[0],
            ((ObjectSegmentVector) ov).toObjectArray());
    }
}

// End SegmentVectorTest.java
