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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeCalcDesugarerTest {

    private static final String M = "[Measures].[Revenue]";
    private static final String TH = "[Calendar].[Calendar]";
    private static final String YL = "[Calendar].[Calendar].[Year]";

    @Test public void yoyIsYearOverYearGrowthPercent() {
        String f = TimeCalcDesugarer.formula("yoy", M, TH, YL, null, null);
        assertEquals(
            "(" + M + " - (" + M + ", ParallelPeriod(" + YL + ", 1)))"
            + " / (" + M + ", ParallelPeriod(" + YL + ", 1))",
            f);
    }

    @Test public void popIsPeriodOverPeriodGrowthPercent() {
        String f = TimeCalcDesugarer.formula("pop", M, TH, YL, null, null);
        assertEquals(
            "(" + M + " - (" + M + ", " + TH + ".CurrentMember.PrevMember))"
            + " / (" + M + ", " + TH + ".CurrentMember.PrevMember)",
            f);
    }

    @Test public void ytdIsCumulative() {
        String f = TimeCalcDesugarer.formula("ytd", M, TH, YL, null, null);
        assertEquals("Aggregate(Ytd(" + TH + ".CurrentMember), " + M + ")", f);
    }

    @Test public void rollingSumUsesLastPeriodsAggregate() {
        String f = TimeCalcDesugarer.formula("rolling", M, TH, YL, 3, "sum");
        assertEquals(
            "Aggregate(LastPeriods(3, " + TH + ".CurrentMember), " + M + ")", f);
    }

    @Test public void rollingAvgUsesAvg() {
        String f = TimeCalcDesugarer.formula("rolling", M, TH, YL, 3, "avg");
        assertEquals(
            "Avg(LastPeriods(3, " + TH + ".CurrentMember), " + M + ")", f);
    }

    @Test public void rollingDefaultsToSumWhenFunctionNull() {
        String f = TimeCalcDesugarer.formula("rolling", M, TH, YL, 3, null);
        assertTrue(f.startsWith("Aggregate(LastPeriods(3,"), f);
    }

    @Test public void unknownTypeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> TimeCalcDesugarer.formula("bogus", M, TH, YL, null, null));
    }

    @Test public void rollingWithoutWindowThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> TimeCalcDesugarer.formula("rolling", M, TH, YL, null, "sum"));
    }
}
