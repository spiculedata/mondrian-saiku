/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.olap.MondrianException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #105 (TDD #1): a {@code <QueryParameter>} definition coerces and validates
 * values against its declared type and closed allowed-value set.
 */
public class RolapQueryParameterDefTest {

    @Test
    public void stringWithAllowedSetValidatesMember() {
        RolapQueryParameterDef def = RolapQueryParameterDef.create(
            "region", "String", "EAST", Arrays.asList("EAST", "WEST"));
        assertEquals("EAST", def.validate("EAST"));
        assertEquals("WEST", def.validate("WEST"));
        assertEquals(RolapQueryParameterDef.Datatype.STRING,
            def.getDatatype());
        assertTrue(def.hasDefault());
        assertEquals("EAST", def.getDefaultValue());
    }

    @Test
    public void outOfSetValueThrows() {
        RolapQueryParameterDef def = RolapQueryParameterDef.create(
            "region", "String", "EAST", Arrays.asList("EAST", "WEST"));
        assertThrows(MondrianException.class, () -> def.validate("NORTH"));
    }

    @Test
    public void numericCoercionAndWrongTypeThrows() {
        RolapQueryParameterDef def = RolapQueryParameterDef.create(
            "minSales", "Numeric", null, Collections.emptyList());
        assertEquals(new BigDecimal("42"), def.validate("42"));
        assertThrows(MondrianException.class, () -> def.validate("not-a-num"));
    }

    @Test
    public void dateCoercionAndWrongTypeThrows() {
        RolapQueryParameterDef def = RolapQueryParameterDef.create(
            "asOf", "Date", null, Collections.emptyList());
        assertEquals("2026-06-03", def.validate("2026-06-03"));
        assertThrows(MondrianException.class, () -> def.validate("06/03/2026"));
    }

    @Test
    public void defaultOutsideAllowedSetFailsAtLoad() {
        assertThrows(MondrianException.class, () ->
            RolapQueryParameterDef.create(
                "region", "String", "NORTH",
                Arrays.asList("EAST", "WEST")));
    }

    @Test
    public void unknownTypeFailsAtLoad() {
        assertThrows(MondrianException.class, () ->
            RolapQueryParameterDef.create(
                "x", "Banana", null, Collections.emptyList()));
    }
}
