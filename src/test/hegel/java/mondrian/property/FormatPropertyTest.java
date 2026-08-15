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

import static dev.hegel.Generators.datetimes;
import static dev.hegel.Generators.doubles;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Locale;
import mondrian.util.Format;
import org.junit.jupiter.api.Test;

/**
 * Properties of {@link Format}, Mondrian's Visual-Basic-compatible value formatter.
 *
 * <p>{@code Format} is on the path of every cell Mondrian renders — a cube's {@code formatString}
 * runs through it for each value in the result set — and it is a hand-written reimplementation of
 * VB's formatter, roughly three thousand lines of hand-rolled digit handling. That combination
 * (high traffic, no reference implementation to defer to) is what makes it worth generating inputs
 * for rather than listing them.
 *
 * <p>The properties here are deliberately <em>not</em> "this format string produces this string".
 * VB's formatting rules differ from {@code java.text.DecimalFormat}'s in enough small ways that any
 * such oracle would encode my guess at the spec rather than the spec. What is asserted instead are
 * the invariants that must hold whatever the spec says: the formatter is a pure function, it is
 * safe to reuse, and its output for a plain number can be read back.
 */
class FormatPropertyTest {

    private static final Locale LOCALE = Locale.US;

    /** Format strings drawn from what FoodMart and real schemas actually use. */
    private static Generator<String> formatString() {
        return sampledFrom(
                "Standard",
                "Currency",
                "Fixed",
                "Percent",
                "General Number",
                "#,##0",
                "#,##0.00",
                "0.###",
                "0.00%",
                "$#,##0.00;($#,##0.00)",
                "#",
                "0");
    }

    /** Finite values only: NaN and the infinities are covered separately. */
    private static Generator<Double> finiteValue() {
        return doubles().allowNan(false).allowInfinity(false).min(-1e12).max(1e12);
    }

    // ------------------------------------------------------------------
    // Purity and reuse
    // ------------------------------------------------------------------

    /**
     * A {@link Format} instance reused across values behaves exactly like a fresh instance per
     * value.
     *
     * <p>This is the property that matters most in production and is the hardest to reach by
     * example. Mondrian compiles a cube's {@code formatString} once and reuses the resulting
     * {@code Format} for every cell in the result — millions of calls against one object that
     * carries internal digit buffers. Any leftover state between calls shows up as a cell formatted
     * correctly in isolation and wrongly in a report, which is precisely the bug nobody can
     * reproduce.
     *
     * <p>Formatting the <em>same</em> value twice would not catch it; the values have to differ, so
     * the first call gets a chance to leave residue the second would pick up.
     */
    @HegelTest(testCases = 500)
    void reusingAFormatMatchesAFreshFormat(TestCase tc) {
        String pattern = tc.draw(formatString(), "pattern");
        double first = tc.draw(finiteValue(), "first");
        double second = tc.draw(finiteValue(), "second");

        Format reused = new Format(pattern, LOCALE);
        String reusedFirst = reused.format(first);
        String reusedSecond = reused.format(second);

        String freshFirst = new Format(pattern, LOCALE).format(first);
        String freshSecond = new Format(pattern, LOCALE).format(second);

        assertEquals(freshFirst, reusedFirst, () -> "reuse changed the first value under " + pattern);
        assertEquals(
                freshSecond,
                reusedSecond,
                () -> "reuse changed the second value under " + pattern + " (formatted " + first + " first)");
    }

    /** Formatting is deterministic: the same instance and value always give the same string. */
    @HegelTest(testCases = 300)
    void formattingIsDeterministic(TestCase tc) {
        String pattern = tc.draw(formatString(), "pattern");
        double value = tc.draw(finiteValue(), "value");

        Format format = new Format(pattern, LOCALE);

        assertEquals(format.format(value), format.format(value), () -> "non-deterministic for " + pattern);
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    /** Half of the last decimal place "General Number" retains; see the characterisation below. */
    private static final double GENERAL_NUMBER_TOLERANCE = 0.0005;

    /**
     * "General Number" output parses back to the original value, to within the rounding it applies.
     *
     * <p>Stated as a tolerance rather than as exact equality because "General Number" is not
     * lossless — it rounds to three decimal places, as
     * {@link #generalNumberRoundsToThreeDecimalPlacesAndGroups} records. Within that, the property
     * still has teeth: it catches a formatter that drops a digit, misplaces the decimal point,
     * loses the sign, or emits something unparseable, for every value in the range.
     *
     * <p>Grouping separators are stripped before parsing for the same reason — their presence is a
     * separate, characterised deviation, and leaving them in would make this test fail for that
     * reason rather than for the one it is about.
     */
    @HegelTest(testCases = 400)
    void generalNumberRoundTripsWithinItsRounding(TestCase tc) {
        double value = tc.draw(finiteValue(), "value");

        String formatted = new Format("General Number", LOCALE).format(value);

        double parsed;
        try {
            parsed = Double.parseDouble(formatted.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new AssertionError(
                    "General Number produced unparseable output for " + value + ": \"" + formatted + "\"", e);
        }

        // Absolute for small magnitudes (where the 3dp cap bites), relative for large ones (where
        // a double has fewer than three decimal places of precision left to print).
        double tolerance = Math.max(GENERAL_NUMBER_TOLERANCE, Math.abs(value) * 1e-9);
        assertEquals(value, parsed, tolerance, () -> "round trip changed " + value + " via \"" + formatted + "\"");
    }

    /**
     * Characterisation test for a KNOWN DEFECT (issue #144): "General Number" neither shows numbers
     * as entered nor omits thousands separators.
     *
     * <p>{@code Format}'s own table describes {@code General Number} as "Shows numbers as entered",
     * and the Visual Basic format it is compatible with is specified as "display number with no
     * thousand separator". Mondrian does two things that contradict that:
     *
     * <ul>
     *   <li>it inserts grouping separators — {@code 1000} formats as {@code "1,000"};
     *   <li>it rounds to three decimal places — {@code 0.5625} formats as {@code "0.562"}, so the
     *       entered value is not recoverable from the output.
     * </ul>
     *
     * <p>The existing {@code FormatTest} checks {@code General Number} against 6, -6, 0 and 0.6.
     * Every one of those is under a thousand and has at most one decimal place, so neither
     * deviation could show up. Hegel shrank to 1000 and to 0.5625 — the smallest witnesses for each.
     *
     * <p>Left unfixed: {@code General Number} is reachable from any schema's {@code formatString},
     * so changing it changes rendered output for existing users — a product call, not a test-suite
     * call. This test pins both behaviours and fails when either is addressed.
     */
    @Test
    void generalNumberRoundsToThreeDecimalPlacesAndGroups() {
        Format format = new Format("General Number", LOCALE);

        assertEquals("999", format.format(999.0), "below the grouping threshold, as expected");
        assertEquals("1,000", format.format(1000.0), "grouping separator inserted");
        assertEquals("1,000,000,000", format.format(1.0e9), "grouping scales with magnitude");

        assertEquals("0.6", format.format(0.6), "within three decimal places, as expected");
        assertEquals("0.562", format.format(0.5625), "rounded to three decimal places");
        assertEquals("0.123", format.format(0.1234567), "further digits discarded");
    }

    // ------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------

    /**
     * Formatting never fails with a low-level fault.
     *
     * <p>A schema's {@code formatString} is author-supplied, so this code is reachable with values
     * an author never anticipated. Rejecting a bad format with a clear exception is fine;
     * {@link NullPointerException}, an index fault, or a {@link StackOverflowError} is the
     * formatter walking off its own buffers, and each would surface to a user as an opaque failure
     * in the middle of rendering a report.
     */
    @HegelTest(testCases = 500)
    void formattingNeverFaults(TestCase tc) {
        String pattern = tc.draw(formatString(), "pattern");
        Object value = tc.draw(
                dev.hegel.Generators.oneOf(
                        finiteValue().map(d -> (Object) d),
                        doubles().map(d -> (Object) d), // includes NaN and the infinities
                        integers().map(i -> (Object) i),
                        // just(null), not sampledFrom(null, ...): sampledFrom builds a List.of
                        // internally, which rejects null elements with an NPE from inside the
                        // generator rather than from the code under test.
                        dev.hegel.Generators.just((Object) null),
                        sampledFrom((Object) "", "text", Long.MIN_VALUE, Long.MAX_VALUE)),
                "value");

        try {
            new Format(pattern, LOCALE).format(value);
        } catch (NullPointerException | IndexOutOfBoundsException | StackOverflowError e) {
            throw new AssertionError(
                    "format(" + value + ") under \"" + pattern + "\" faulted with " + e.getClass().getName(), e);
        } catch (RuntimeException e) {
            // A deliberate, described rejection is acceptable behaviour.
            if (e.getMessage() == null) {
                throw new AssertionError(
                        "format(" + value + ") under \"" + pattern + "\" threw " + e.getClass().getName()
                                + " with no message",
                        e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Dates
    // ------------------------------------------------------------------

    /** Date formatting is reusable and deterministic on the same terms as number formatting. */
    @HegelTest(testCases = 300)
    void reusingADateFormatMatchesAFreshFormat(TestCase tc) {
        String pattern = tc.draw(
                sampledFrom("Short Date", "Long Date", "yyyy-mm-dd", "dd/mm/yyyy", "mmm d, yyyy", "General Date"),
                "pattern");
        Date first = toDate(tc.draw(datetimes(), "first"));
        Date second = toDate(tc.draw(datetimes(), "second"));

        Format reused = new Format(pattern, LOCALE);
        String reusedFirst = reused.format(first);
        String reusedSecond = reused.format(second);

        assertEquals(
                new Format(pattern, LOCALE).format(first), reusedFirst, () -> "reuse changed the first date under "
                        + pattern);
        assertEquals(
                new Format(pattern, LOCALE).format(second),
                reusedSecond,
                () -> "reuse changed the second date under " + pattern + " (formatted " + first + " first)");
    }

    private static Date toDate(LocalDateTime dateTime) {
        return Date.from(dateTime.toInstant(ZoneOffset.UTC));
    }
}
