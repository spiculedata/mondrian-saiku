/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule / Saiku community
// All Rights Reserved.
*/
package mondrian.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;

/**
 * Laws relating MDX's time-series functions.
 *
 * <p>{@code Ytd}, {@code Qtd}, {@code Mtd}, {@code PeriodsToDate}, {@code ParallelPeriod},
 * {@code OpeningPeriod}, {@code ClosingPeriod} and {@code LastPeriods} are the functions behind
 * every period-over-period figure a business actually looks at — year to date, same quarter last
 * year, month-end balance. An off-by-one in any of them produces a number that is wrong by exactly
 * one period, which is the hardest kind of wrong to notice and the most damaging to trust.
 *
 * <p>Several of them are defined in terms of the others, which gives free oracles:
 * {@code Ytd(m)} is specified as {@code PeriodsToDate([Year], m)}, and
 * {@code OpeningPeriod}/{@code ClosingPeriod} are the first and last of {@code Descendants}. Those
 * identities are asserted directly, so a divergence localises to one function rather than to "the
 * time logic".
 */
class MdxTimeSeriesPropertyTest {

    /** The time hierarchy with a Year/Quarter/Month chain — the one the Xtd functions are defined over. */
    private static final String YEAR_LEVEL = "[Time].[Time].[Year]";
    private static final String QUARTER_LEVEL = "[Time].[Time].[Quarter]";
    private static final String MONTH_LEVEL = "[Time].[Time].[Month]";

    private static String drawMonth(TestCase tc) {
        List<String> months = FoodMart.membersOfLevel(MONTH_LEVEL);
        return months.get(tc.draw(dev.hegel.Generators.integers().min(0).max(months.size() - 1), "monthIndex"));
    }

    private static String drawQuarter(TestCase tc) {
        List<String> quarters = FoodMart.membersOfLevel(QUARTER_LEVEL);
        return quarters.get(tc.draw(dev.hegel.Generators.integers().min(0).max(quarters.size() - 1), "quarterIndex"));
    }

    // ------------------------------------------------------------------
    // Xtd is PeriodsToDate at a fixed level
    // ------------------------------------------------------------------

    /** {@code Ytd(m) == PeriodsToDate([Year], m)} — the documented definition. */
    @HegelTest(testCases = 60)
    void ytdIsPeriodsToDateAtTheYearLevel(TestCase tc) {
        String month = drawMonth(tc);

        assertEquals(
                FoodMart.membersOf("PeriodsToDate(" + YEAR_LEVEL + ", " + month + ")"),
                FoodMart.membersOf("Ytd(" + month + ")"),
                () -> "Ytd differed from PeriodsToDate at the year level for " + month);
    }

    /** {@code Qtd(m) == PeriodsToDate([Quarter], m)}. */
    @HegelTest(testCases = 60)
    void qtdIsPeriodsToDateAtTheQuarterLevel(TestCase tc) {
        String month = drawMonth(tc);

        assertEquals(
                FoodMart.membersOf("PeriodsToDate(" + QUARTER_LEVEL + ", " + month + ")"),
                FoodMart.membersOf("Qtd(" + month + ")"),
                () -> "Qtd differed from PeriodsToDate at the quarter level for " + month);
    }

    /**
     * A to-date set ends at the member it was asked about, and stays inside that member's parent.
     *
     * <p>"Ends at m" is the off-by-one guard — a to-date set that runs one period long or one short
     * is the classic period bug. "Stays inside the parent" is the containment guard: year-to-date
     * must not leak into the previous year.
     */
    @HegelTest(testCases = 60)
    void yearToDateEndsAtTheMemberAndStaysInsideItsYear(TestCase tc) {
        String month = drawMonth(tc);

        List<String> ytd = FoodMart.membersOf("Ytd(" + month + ")");
        assertTrue(!ytd.isEmpty(), () -> "Ytd was empty for " + month);
        assertEquals(month, ytd.get(ytd.size() - 1), () -> "Ytd did not end at " + month + ": " + ytd);

        List<String> siblings = FoodMart.membersOf("Descendants(Ancestor(" + month + ", " + YEAR_LEVEL + "), "
                + MONTH_LEVEL + ")");
        assertTrue(
                siblings.containsAll(ytd),
                () -> "Ytd(" + month + ") leaked outside its own year: " + ytd);
    }

    /** {@code Ytd} grows monotonically through a year. */
    @HegelTest(testCases = 50)
    void yearToDateGrowsThroughTheYear(TestCase tc) {
        String month = drawMonth(tc);

        List<String> here = FoodMart.membersOf("Ytd(" + month + ")");
        List<String> next = FoodMart.membersOf("Ytd(" + month + ".NextMember)");
        // Only meaningful while the next month is in the same year; at a year boundary Ytd resets,
        // which is correct behaviour rather than a violation.
        tc.assume(!next.isEmpty() && next.size() > here.size());

        assertTrue(
                next.containsAll(here),
                () -> "Ytd at the next month did not contain Ytd at " + month + "\n  here: " + here + "\n  next: "
                        + next);
    }

    // ------------------------------------------------------------------
    // OpeningPeriod / ClosingPeriod
    // ------------------------------------------------------------------

    /** {@code OpeningPeriod(L, m)} is the first of {@code Descendants(m, L)}. */
    @HegelTest(testCases = 60)
    void openingPeriodIsTheFirstDescendant(TestCase tc) {
        String quarter = drawQuarter(tc);

        List<String> descendants = FoodMart.membersOf("Descendants(" + quarter + ", " + MONTH_LEVEL + ")");
        tc.assume(!descendants.isEmpty());

        assertEquals(
                List.of(descendants.get(0)),
                FoodMart.membersOf("{OpeningPeriod(" + MONTH_LEVEL + ", " + quarter + ")}"),
                () -> "OpeningPeriod was not the first descendant of " + quarter + ": " + descendants);
    }

    /** {@code ClosingPeriod(L, m)} is the last of {@code Descendants(m, L)}. */
    @HegelTest(testCases = 60)
    void closingPeriodIsTheLastDescendant(TestCase tc) {
        String quarter = drawQuarter(tc);

        List<String> descendants = FoodMart.membersOf("Descendants(" + quarter + ", " + MONTH_LEVEL + ")");
        tc.assume(!descendants.isEmpty());

        assertEquals(
                List.of(descendants.get(descendants.size() - 1)),
                FoodMart.membersOf("{ClosingPeriod(" + MONTH_LEVEL + ", " + quarter + ")}"),
                () -> "ClosingPeriod was not the last descendant of " + quarter + ": " + descendants);
    }

    // ------------------------------------------------------------------
    // ParallelPeriod
    // ------------------------------------------------------------------

    /** {@code ParallelPeriod(L, 0, m)} is {@code m}. */
    @HegelTest(testCases = 60)
    void parallelPeriodOfZeroIsTheMemberItself(TestCase tc) {
        String month = drawMonth(tc);

        assertEquals(
                List.of(month),
                FoodMart.membersOf("{ParallelPeriod(" + YEAR_LEVEL + ", 0, " + month + ")}"),
                () -> "ParallelPeriod(.., 0, m) was not m for " + month);
    }

    /**
     * {@code ParallelPeriod} is invertible: stepping back {@code n} then forward {@code n} returns
     * the original member.
     *
     * <p>This is the "same period last year" calculation, and an asymmetry here means the comparison
     * figure on a report is drawn from the wrong period.
     */
    @HegelTest(testCases = 60)
    void parallelPeriodIsInvertible(TestCase tc) {
        String month = drawMonth(tc);
        int n = tc.draw(dev.hegel.Generators.integers().min(-2).max(2), "n");

        String back = "ParallelPeriod(" + YEAR_LEVEL + ", " + n + ", " + month + ")";
        // Only where the intermediate step exists; stepping off the end of the hierarchy gives the
        // null member, and coming back from nothing is legitimately nothing.
        tc.assume(!FoodMart.membersOf("{" + back + "}").isEmpty());

        assertEquals(
                List.of(month),
                FoodMart.membersOf("{ParallelPeriod(" + YEAR_LEVEL + ", " + (-n) + ", " + back + ")}"),
                () -> "ParallelPeriod by " + n + " then " + (-n) + " did not return " + month);
    }

    // ------------------------------------------------------------------
    // LastPeriods
    // ------------------------------------------------------------------

    /** {@code LastPeriods(1, m)} is {@code m}. */
    @HegelTest(testCases = 60)
    void lastPeriodsOfOneIsTheMemberItself(TestCase tc) {
        String month = drawMonth(tc);

        assertEquals(
                List.of(month),
                FoodMart.membersOf("LastPeriods(1, " + month + ")"),
                () -> "LastPeriods(1, m) was not {m} for " + month);
    }

    /** {@code LastPeriods(n, m)} ends at {@code m} and never exceeds {@code n} members. */
    @HegelTest(testCases = 60)
    void lastPeriodsEndsAtTheMemberAndIsBounded(TestCase tc) {
        String month = drawMonth(tc);
        int n = tc.draw(dev.hegel.Generators.integers().min(1).max(6), "n");

        List<String> periods = FoodMart.membersOf("LastPeriods(" + n + ", " + month + ")");
        tc.assume(!periods.isEmpty());

        assertEquals(month, periods.get(periods.size() - 1), () -> "LastPeriods did not end at " + month + ": " + periods);
        assertTrue(
                periods.size() <= n,
                () -> "LastPeriods(" + n + ", " + month + ") returned " + periods.size() + " members: " + periods);
    }
}
