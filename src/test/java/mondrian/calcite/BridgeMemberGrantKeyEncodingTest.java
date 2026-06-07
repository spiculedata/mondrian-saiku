/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.calcite;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Issue #107 (SECURITY): the bridge member-grant segment cache key must encode a
 * role's visible member-key set so that two DISTINCT visible sets never collide
 * onto the same key — otherwise a segment computed under role A could be served
 * to role B (cross-role bleed). The previous {@code String.valueOf} +
 * {@code List.toString} encoding had two collision modes, both closed here.
 */
public class BridgeMemberGrantKeyEncodingTest {

    /** Type collision: a numeric key {@code 1L} and the string key {@code "1"}
     *  both stringify to "1". They are DIFFERENT visible sets and must encode
     *  differently. */
    @Test public void numericAndStringKeysDoNotCollide() {
        String numeric = CalcitePlannerAdapters.encodeVisibleKeySet(
            Arrays.asList((Object) Long.valueOf(1)));
        String string = CalcitePlannerAdapters.encodeVisibleKeySet(
            Arrays.asList((Object) "1"));
        assertNotEquals(numeric, string,
            "Long 1 and String \"1\" are different visible sets and must not"
            + " share a cache identity");
    }

    /** Delimiter collision: the two-element set {@code {"a","b"}} and the
     *  single-element set {@code {"a, b"}} both render as the list "[a, b]"
     *  under the old encoding. They are DIFFERENT visible sets and must encode
     *  differently. */
    @Test public void delimiterContainingKeyDoesNotCollide() {
        String twoKeys = CalcitePlannerAdapters.encodeVisibleKeySet(
            Arrays.asList((Object) "a", (Object) "b"));
        String oneKeyWithDelimiter = CalcitePlannerAdapters.encodeVisibleKeySet(
            Arrays.asList((Object) "a, b"));
        assertNotEquals(twoKeys, oneKeyWithDelimiter,
            "{\"a\",\"b\"} and {\"a, b\"} are different visible sets and must"
            + " not share a cache identity");
    }

    /** Identity is independent of member enumeration order. */
    @Test public void encodingIsOrderIndependent() {
        String ab = CalcitePlannerAdapters.encodeVisibleKeySet(
            Arrays.asList((Object) "a", (Object) "b"));
        String ba = CalcitePlannerAdapters.encodeVisibleKeySet(
            Arrays.asList((Object) "b", (Object) "a"));
        assertEquals(ab, ba,
            "encoding must be deterministic regardless of enumeration order");
    }
}

// End BridgeMemberGrantKeyEncodingTest.java
