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

import mondrian.olap.PredicateGrant;
import mondrian.rolap.RolapQueryParameterDef;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #106 unit tests for the IN multi-value resolution and the {@link
 * PredicateGrant} value model — the parts that are pure logic and need no
 * database. The end-to-end injection / cache-isolation is proven in
 * {@link PredicateGrantH2EndToEndTest}.
 */
public class PredicateGrantResolveListTest {

    private static QueryParameterContext ctxWith(String name, String raw) {
        Map<String, RolapQueryParameterDef> defs = new LinkedHashMap<>();
        defs.put(name,
            RolapQueryParameterDef.create(
                name, "String", null, Collections.<String>emptyList()));
        Map<String, String> session = new LinkedHashMap<>();
        session.put(name, raw);
        return QueryParameterContext.resolveAll(defs, session);
    }

    @Test
    public void resolveListSplitsAndValidatesEachToken() {
        QueryParameterContext ctx = ctxWith("regions", "EAST,WEST");
        List<Object> values = ctx.resolveList("regions");
        assertEquals(2, values.size());
        assertEquals("EAST", values.get(0));
        assertEquals("WEST", values.get(1));
    }

    @Test
    public void resolveListTrimsAndDropsBlankTokens() {
        QueryParameterContext ctx = ctxWith("regions", " EAST , , WEST ");
        List<Object> values = ctx.resolveList("regions");
        assertEquals(2, values.size());
        assertEquals("EAST", values.get(0));
        assertEquals("WEST", values.get(1));
    }

    @Test
    public void resolveListEmptyForCommaOnly() {
        // A comma-only value -> zero tokens -> caller must fail closed.
        QueryParameterContext ctx = ctxWith("regions", " , ");
        assertTrue(ctx.resolveList("regions").isEmpty());
    }

    @Test
    public void resolveListUndeclaredThrows() {
        QueryParameterContext ctx = ctxWith("regions", "EAST");
        assertThrows(RuntimeException.class,
            () -> ctx.resolveList("nope"));
    }

    @Test
    public void resolveListEnforcesAllowedSetPerToken() {
        // A String-typed IN parameter with a closed allowed-value set: a
        // comma-separated value passes connect-time validation as long as the
        // WHOLE string is in the set; per-token resolveList then splits and
        // re-validates each token against the same set. (Numeric IN is not
        // expressible because #105 validates the whole "1,2" as one Numeric at
        // connect — IN parameters are String-typed by design.)
        Map<String, RolapQueryParameterDef> defs = new LinkedHashMap<>();
        defs.put("r",
            RolapQueryParameterDef.create(
                "r", "String", null,
                java.util.Arrays.asList("EAST", "WEST")));
        Map<String, String> session = new LinkedHashMap<>();
        session.put("r", "EAST");
        QueryParameterContext ctx =
            QueryParameterContext.resolveAll(defs, session);
        List<Object> values = ctx.resolveList("r");
        assertEquals(1, values.size());
        assertEquals("EAST", values.get(0));
    }

    @Test
    public void operatorParseDefaultsToEqAndRejectsUnknown() {
        assertEquals(PredicateGrant.Operator.EQ,
            PredicateGrant.Operator.parse(null));
        assertEquals(PredicateGrant.Operator.EQ,
            PredicateGrant.Operator.parse(""));
        assertEquals(PredicateGrant.Operator.EQ,
            PredicateGrant.Operator.parse("eq"));
        assertEquals(PredicateGrant.Operator.IN,
            PredicateGrant.Operator.parse("IN"));
        assertThrows(RuntimeException.class,
            () -> PredicateGrant.Operator.parse("like"));
    }

    @Test
    public void predicateGrantRejectsNulls() {
        assertThrows(RuntimeException.class,
            () -> new PredicateGrant(
                null, "c", PredicateGrant.Operator.EQ, "p"));
        assertThrows(RuntimeException.class,
            () -> new PredicateGrant(
                "mg", null, PredicateGrant.Operator.EQ, "p"));
        assertThrows(RuntimeException.class,
            () -> new PredicateGrant(
                "mg", "c", PredicateGrant.Operator.EQ, null));
    }
}
