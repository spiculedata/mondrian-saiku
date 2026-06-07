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

/**
 * #112 Phase 2: desugars a declarative {@code <TimeCalc>} into the MDX formula
 * of an equivalent calculated member. Pure and side-effect free — all schema
 * resolution (measure name, time hierarchy/level unique names) happens in
 * {@link RolapSchemaLoader} and is passed in, so this is trivially testable.
 */
public final class TimeCalcDesugarer {
    private TimeCalcDesugarer() {}

    /**
     * @param type one of yoy|pop|ytd|rolling
     * @param measure the base measure unique name, e.g. {@code [Measures].[Revenue]}
     * @param timeHierarchy the time hierarchy unique name, e.g. {@code [Calendar].[Calendar]}
     * @param yearLevel the TimeYears level unique name (used by yoy/ytd)
     * @param window number of periods (rolling only; required for rolling)
     * @param function rolling aggregation: sum (default) or avg
     * @return the MDX formula string
     * @throws IllegalArgumentException on an unknown type or rolling w/o window
     */
    public static String formula(
        String type, String measure, String timeHierarchy,
        String yearLevel, Integer window, String function)
    {
        switch (type) {
        case "yoy": {
            String prior = "(" + measure + ", ParallelPeriod("
                + yearLevel + ", 1))";
            return "(" + measure + " - " + prior + ") / " + prior;
        }
        case "pop": {
            String prior = "(" + measure + ", "
                + timeHierarchy + ".CurrentMember.PrevMember)";
            return "(" + measure + " - " + prior + ") / " + prior;
        }
        case "ytd":
            return "Aggregate(Ytd(" + timeHierarchy + ".CurrentMember), "
                + measure + ")";
        case "rolling": {
            if (window == null) {
                throw new IllegalArgumentException(
                    "TimeCalc type='rolling' requires a 'window'");
            }
            String range = "LastPeriods(" + window + ", "
                + timeHierarchy + ".CurrentMember)";
            boolean avg = "avg".equals(function);
            return (avg ? "Avg(" : "Aggregate(")
                + range + ", " + measure + ")";
        }
        default:
            throw new IllegalArgumentException(
                "unknown TimeCalc type '" + type + "'"
                + " (expected yoy|pop|ytd|rolling)");
        }
    }
}
