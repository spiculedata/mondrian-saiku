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

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.function.Supplier;

/**
 * Consistency laws between MDX's aggregate functions.
 *
 * <p>{@code Sum}, {@code Count}, {@code Avg}, {@code Min}, {@code Max} and {@code Aggregate} are
 * separate implementations that must nevertheless agree about the same data. Each law below relates
 * two or more of them, so a disagreement localises the fault without anyone having to know the
 * right total — which matters because these are the numbers users actually read off a report.
 *
 * <p>All comparisons use a relative tolerance. Aggregates over doubles reassociate — summing a set
 * in a different order, or via a different code path, gives answers that differ in the last bits —
 * so exact equality would fail for reasons that are arithmetic rather than logical.
 */
class MdxAggregationPropertyTest {

    private static final double EPSILON = 1e-9;

    /** A generated set together with its cardinality, drawn once so every law sees the same set. */
    private static String drawNonEmptySet(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        tc.assume(!FoodMart.membersOf(set).isEmpty());
        return set;
    }

    // ------------------------------------------------------------------
    // Ordering between the aggregates
    // ------------------------------------------------------------------

    /**
     * {@code Min <= Avg <= Max} over a non-empty set.
     *
     * <p>The most basic sanity law there is, and it fails loudly if any of the three uses a
     * different notion of which cells are empty — an average that divides by the wrong count drifts
     * outside the range its own extremes define.
     */
    @HegelTest(testCases = 80)
    void minIsAtMostAvgIsAtMostMax(TestCase tc) {
        String set = drawNonEmptySet(tc);
        String m = FoodMart.ADDITIVE_MEASURE;

        Double min = FoodMart.scalar("Min(" + set + ", " + m + ")");
        Double avg = FoodMart.scalar("Avg(" + set + ", " + m + ")");
        Double max = FoodMart.scalar("Max(" + set + ", " + m + ")");

        // All three are null exactly when every cell is empty; that is consistent, not a failure.
        tc.assume(min != null && avg != null && max != null);

        assertTrue(
                min <= avg + tolerance(min, avg),
                () -> "Min (" + min + ") exceeded Avg (" + avg + ") for S = " + set);
        assertTrue(
                avg <= max + tolerance(avg, max),
                () -> "Avg (" + avg + ") exceeded Max (" + max + ") for S = " + set);
    }

    /** {@code Sum == Avg * Count} over the cells that contribute. */
    @HegelTest(testCases = 80)
    void sumEqualsAverageTimesContributingCount(TestCase tc) {
        String set = drawNonEmptySet(tc);
        String m = FoodMart.ADDITIVE_MEASURE;

        Double sum = FoodMart.scalar("Sum(" + set + ", " + m + ")");
        Double avg = FoodMart.scalar("Avg(" + set + ", " + m + ")");
        // Avg divides by the number of NON-EMPTY cells, so the matching count must exclude
        // empties too; using the plain cardinality here would be comparing different denominators.
        Double count = FoodMart.scalar("Count(Filter(" + set + ", NOT IsEmpty(" + m + ")))");

        tc.assume(sum != null && avg != null && count != null && count > 0);

        double reconstructed = avg * count;
        assertTrue(
                Math.abs(sum - reconstructed) <= tolerance(sum, reconstructed),
                () -> "Sum (" + sum + ") != Avg * Count (" + avg + " * " + count + " = " + reconstructed
                        + ") for S = " + set);
    }

    /**
     * {@code Aggregate} matches {@code Sum} for a measure whose aggregator is {@code sum}.
     *
     * <p>{@code Aggregate} dispatches on the measure's declared aggregator while {@code Sum} always
     * adds, so for an additive measure the two must coincide. They take different routes to get
     * there — {@code Aggregate} is the one that can be satisfied from a rolled-up cache segment —
     * which is exactly what makes the comparison worth making.
     */
    @HegelTest(testCases = 80)
    void aggregateMatchesSumForAnAdditiveMeasure(TestCase tc) {
        String set = drawNonEmptySet(tc);
        String m = FoodMart.ADDITIVE_MEASURE;

        Double sum = FoodMart.scalar("Sum(" + set + ", " + m + ")");
        Double aggregate = FoodMart.scalar("Aggregate(" + set + ", " + m + ")");

        if (sum == null || aggregate == null) {
            assertTrue(
                    sum == null && aggregate == null,
                    () -> "Sum and Aggregate disagree about emptiness (" + sum + " vs " + aggregate + ") for S = " + set);
            return;
        }
        assertTrue(
                Math.abs(sum - aggregate) <= tolerance(sum, aggregate),
                () -> "Sum (" + sum + ") != Aggregate (" + aggregate + ") for S = " + set);
    }

    // ------------------------------------------------------------------
    // Structural laws
    // ------------------------------------------------------------------

    /**
     * Inclusion-exclusion for sums: {@code Sum(a) + Sum(b) == Sum(a ∪ b) + Sum(a ∩ b)}.
     *
     * <p>The aggregate analogue of the cardinality law, and a much stronger check on
     * {@code Union}/{@code Intersect} than counting is: it requires the operators to agree about
     * <em>which</em> members overlap, not merely how many.
     */
    @HegelTest(testCases = 80)
    void sumsObeyInclusionExclusion(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String a = operands.get(1);
        String b = operands.get(2);
        String m = FoodMart.ADDITIVE_MEASURE;

        double sumA = zero(FoodMart.scalar("Sum(" + a + ", " + m + ")"));
        double sumB = zero(FoodMart.scalar("Sum(" + b + ", " + m + ")"));
        double sumUnion = zero(FoodMart.scalar("Sum(Union(" + a + ", " + b + "), " + m + ")"));
        double sumIntersect = zero(FoodMart.scalar("Sum(Intersect(" + a + ", " + b + "), " + m + ")"));

        double left = sumA + sumB;
        double right = sumUnion + sumIntersect;
        assertTrue(
                Math.abs(left - right) <= tolerance(left, right),
                () -> "Sum(a) + Sum(b) = " + left + " but Sum(a∪b) + Sum(a∩b) = " + right + " for\n  a = " + a
                        + "\n  b = " + b);
    }

    /**
     * Aggregates do not depend on the order of the set.
     *
     * <p>Reordering is a no-op for every function here, so this catches an aggregator whose
     * accumulator carries order-dependent state — and it does so without needing to know the total.
     */
    @HegelTest(testCases = 80)
    void aggregatesAreOrderIndependent(TestCase tc) {
        String set = drawNonEmptySet(tc);
        String m = FoodMart.ADDITIVE_MEASURE;
        String reordered = "Order(" + set + ", " + m + ", BDESC)";

        for (String fn : List.of("Sum", "Min", "Max", "Avg", "Count")) {
            String direct = fn.equals("Count") ? "Count(" + set + ")" : fn + "(" + set + ", " + m + ")";
            String viaOrder = fn.equals("Count") ? "Count(" + reordered + ")" : fn + "(" + reordered + ", " + m + ")";

            Double before = FoodMart.scalar(direct);
            Double after = FoodMart.scalar(viaOrder);
            assertClose(before, after, () -> fn + " changed under reordering for S = " + set);
        }
    }

    /**
     * A superset's {@code Sum} is at least a subset's, for a non-negative measure.
     *
     * <p>Monotonicity is what a user implicitly assumes when they drill down: a part cannot exceed
     * the whole. {@code Unit Sales} is non-negative throughout FoodMart, which is what licenses the
     * inequality — the property would not hold for a measure that can go negative.
     */
    @HegelTest(testCases = 80)
    void sumIsMonotonicOverSubsets(TestCase tc) {
        String set = drawNonEmptySet(tc);
        String m = FoodMart.ADDITIVE_MEASURE;
        int size = FoodMart.membersOf(set).size();
        int n = tc.draw(dev.hegel.Generators.integers().min(0).max(size), "n");
        tc.assume(n <= size);

        double whole = zero(FoodMart.scalar("Sum(" + set + ", " + m + ")"));
        double part = zero(FoodMart.scalar("Sum(Head(" + set + ", " + n + "), " + m + ")"));

        assertTrue(
                part <= whole + tolerance(part, whole),
                () -> "a subset of " + n + " summed to " + part + ", more than the whole set's " + whole
                        + ", for S = " + set);
    }

    // ------------------------------------------------------------------

    private static double zero(Double d) {
        return d == null ? 0.0 : d;
    }

    private static double tolerance(double a, double b) {
        return EPSILON * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static void assertClose(Double expected, Double actual, Supplier<String> message) {
        if (expected == null || actual == null) {
            assertTrue(
                    expected == null && actual == null,
                    () -> message.get() + " (one side was null: " + expected + " vs " + actual + ")");
            return;
        }
        assertTrue(
                Math.abs(expected - actual) <= tolerance(expected, actual),
                () -> message.get() + " (" + expected + " vs " + actual + ")");
    }
}
