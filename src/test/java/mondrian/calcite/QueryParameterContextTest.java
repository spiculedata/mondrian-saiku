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

import mondrian.olap.MondrianException;
import mondrian.rolap.RolapQueryParameterDef;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #105 (TDD #2): per-request context resolution from session.&lt;name&gt;
 * values — supplied value coerced to type, missing falls to default, illegal
 * throws.
 */
public class QueryParameterContextTest {

    private static Map<String, RolapQueryParameterDef> regionDefs() {
        Map<String, RolapQueryParameterDef> defs = new LinkedHashMap<>();
        defs.put("region", RolapQueryParameterDef.create(
            "region", "String", "EAST", Arrays.asList("EAST", "WEST")));
        return defs;
    }

    @Test
    public void suppliedValueResolvesToTypedValue() {
        QueryParameterContext ctx = QueryParameterContext.resolveAll(
            regionDefs(),
            Collections.singletonMap("region", "WEST"));
        assertEquals("WEST", ctx.resolve("region"));
        assertEquals(RolapQueryParameterDef.Datatype.STRING,
            ctx.datatypeOf("region"));
        assertTrue(ctx.isDeclared("region"));
    }

    @Test
    public void missingValueFallsToDefault() {
        QueryParameterContext ctx = QueryParameterContext.resolveAll(
            regionDefs(),
            Collections.emptyMap());
        assertEquals("EAST", ctx.resolve("region"));
    }

    @Test
    public void illegalValueThrows() {
        assertThrows(MondrianException.class, () ->
            QueryParameterContext.resolveAll(
                regionDefs(),
                Collections.singletonMap("region", "NORTH")));
    }

    @Test
    public void missingWithoutDefaultThrows() {
        Map<String, RolapQueryParameterDef> defs = new LinkedHashMap<>();
        defs.put("region", RolapQueryParameterDef.create(
            "region", "String", null, Arrays.asList("EAST", "WEST")));
        assertThrows(MondrianException.class, () ->
            QueryParameterContext.resolveAll(defs, Collections.emptyMap()));
    }

    @Test
    public void undeclaredResolveThrows() {
        QueryParameterContext ctx = QueryParameterContext.resolveAll(
            regionDefs(), Collections.singletonMap("region", "EAST"));
        assertThrows(MondrianException.class, () -> ctx.resolve("bogus"));
    }

    @Test
    public void emptyDefsYieldEmptyContext() {
        QueryParameterContext ctx = QueryParameterContext.resolveAll(
            Collections.emptyMap(), Collections.emptyMap());
        assertTrue(ctx.isEmpty());
    }
}
