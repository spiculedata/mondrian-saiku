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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Metamorphic properties of the MDX query engine, executed against FoodMart.
 *
 * <p>This is the class that tests "the service" rather than its parts, and it works around the
 * problem that makes an OLAP engine hard to test: <em>there is no oracle</em>. Nobody can say what
 * {@code Order(Filter(Head([Store].[Stores].[Store City].Members, 7), ...), ...)} should return
 * without reimplementing Mondrian, and an expected-value test can only ever cover the handful of
 * queries someone typed out by hand.
 *
 * <p>A metamorphic property sidesteps the oracle entirely. Instead of asking "what is the right
 * answer for this query?", it asks "what must be true of the answers to <em>these two</em>
 * queries?" — and those relations hold for every query, so they can be checked against generated
 * ones. If {@code Filter(S, TRUE)} ever differs from {@code S}, the engine is wrong, and no one
 * needed to know what {@code S} was.
 *
 * <p>Every relation below is a mathematical identity of the MDX semantics, not a FoodMart fact, so
 * none of them bakes in data that a schema change could invalidate.
 */
class MdxEngineMetamorphicPropertyTest {

    /** Relative tolerance for aggregate comparisons; sums of doubles reassociate under regrouping. */
    private static final double EPSILON = 1e-9;

    // ------------------------------------------------------------------
    // Identity relations: a no-op operation must be a no-op.
    // ------------------------------------------------------------------

    /**
     * Characterisation test for a CONFIRMED DEFECT found by this suite:
     * <strong>{@code Filter(S, <constant true>)} silently drops members that have no rows in the
     * fact table.</strong> Tracked as issue #137.
     *
     * <p>This started life as the generated property "filtering by TRUE changes nothing" — the
     * simplest relation in the file — and Hegel falsified it immediately, shrinking to
     * {@code [Store].[Stores].[Store Country].Members}.
     *
     * <p>What is established:
     *
     * <ul>
     *   <li>It is not the predicate. {@code 1 = 1} references no measure and is constantly true, so
     *       no member can legitimately fail it. {@code NOT (1 = 0)} behaves identically.
     *   <li>It is not one hierarchy. {@code [Store].[Stores].[Store Country]} loses Canada and
     *       Mexico (3 → 1), {@code [Time].[Time].[Year]} loses 1998 (2 → 1), and
     *       {@code [Store].[Store Type]} loses HeadQuarters (6 → 5). In every case the dropped
     *       members are exactly those with no rows in the Sales fact table.
     *   <li>It is native evaluation. With {@code EnableNativeFilter}, {@code EnableNativeCrossJoin},
     *       {@code EnableNativeNonEmpty} and {@code EnableNativeTopCount} all off, the query
     *       returns all three countries — the correct answer. Disabling any <em>one</em> of the
     *       four is not enough, so more than one native path reaches the same wrong result.
     * </ul>
     *
     * <p>Severity: the query does not ask for {@code NON EMPTY}, and Mondrian ships with all four
     * flags on by default, so this is silently wrong output in the default configuration rather
     * than a crash or a slow path. Left unfixed here because the fix is inside the native
     * evaluation layer and needs an owner who can judge the cache and push-down consequences —
     * see the findings section of {@code src/test/hegel/README.md}.
     *
     * <p>This test pins the current behaviour so the defect cannot be quietly lost. When it is
     * fixed, this test fails; delete it and restore the property below it.
     */
    @Test
    void filterByConstantTrueDropsEmptyMembers() {
        String set = "[Store].[Stores].[Store Country].Members";
        String filtered = "Filter(" + set + ", 1 = 1)";

        assertEquals(3, FoodMart.membersOf(set).size(), "precondition: the unfiltered level has three members");
        assertEquals(
                List.of("[Store].[Stores].[USA]"),
                FoodMart.membersOf(filtered),
                "witness changed — Filter may have been fixed; if so, delete this test and drop the "
                        + "withoutNativeEvaluation wrapper from "
                        + "filteringByTrueChangesNothingWithoutNativeEvaluation");
    }

    /**
     * The relation that <em>should</em> hold, verified against the non-native evaluation path.
     *
     * <p>Keeping this alongside the characterisation test above is what distinguishes "Mondrian
     * cannot filter" from "Mondrian's native short-cut is wrong". The in-memory path satisfies the
     * identity for every generated set; only the native path does not.
     *
     * <p>The native flags are process-global, so this test sets and restores them around the two
     * queries. That is safe here only because the {@code hegel} profile runs this package alone in
     * its JVM (see the surefire {@code includes} in the profile) — do not copy this pattern into a
     * test that shares a JVM with the main suite.
     */
    @HegelTest(testCases = 60)
    void filteringByTrueChangesNothingWithoutNativeEvaluation(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");

        FoodMart.withoutNativeEvaluation(() -> assertEquals(
                FoodMart.membersOf(set),
                FoodMart.membersOf("Filter(" + set + ", 1 = 1)"),
                () -> "Filter(S, TRUE) differed from S for S = " + set));
    }

    /**
     * Characterisation test for a CONFIRMED DEFECT found by this suite:
     * <strong>native {@code TopCount(S, 0, M)} returns every member instead of none.</strong>
     * Tracked as issue #141.
     *
     * <p>A count of zero means "take the top nothing", so the result must be the empty set — which
     * is exactly what the in-memory path returns. Native evaluation appears to treat zero as
     * "no limit".
     *
     * <p>Established, and distinct from the {@code Filter} defect above:
     *
     * <ul>
     *   <li>Three hierarchies, three different member counts, same shape: Store Country returns 1
     *       instead of 0, Time Year 1 instead of 0, Store Type 5 instead of 0. In each case the
     *       native result is every member with fact data.
     *   <li>It is not stale caching. Running {@code n = 3} first in a fresh JVM gives the same
     *       answer as running {@code n = 0} first, and {@code RolapNativeTopCount.getCacheKey}
     *       does include the count.
     *   <li>{@code BottomCount}, {@code Head} and {@code Tail} all handle zero correctly, so it is
     *       specific to {@code TopCount} rather than general to the limit rewrite.
     * </ul>
     *
     * <p>Severity: a "top N" panel whose N is computed and happens to reach zero renders the entire
     * result set instead of nothing. Left unfixed for the same reason as the {@code Filter} defect —
     * the fix is inside native evaluation and needs an owner who can weigh the push-down
     * consequences. See {@code src/test/hegel/README.md}.
     */
    @Test
    void nativeTopCountOfZeroReturnsEverything() {
        String set = "[Store].[Stores].[Store Country].Members";
        String topZero = "TopCount(" + set + ", 0, " + FoodMart.ADDITIVE_MEASURE + ")";

        assertEquals(
                List.of(),
                FoodMart.withoutNativeEvaluation(() -> FoodMart.membersOf(topZero)),
                "precondition: the in-memory path correctly returns the empty set");
        assertEquals(
                List.of("[Store].[Stores].[USA]"),
                FoodMart.membersOf(topZero),
                "witness changed — native TopCount(.., 0, ..) may have been fixed; if so, restore the "
                        + "zero to MdxGenerator.nonZeroCount and delete this test");
    }

    /**
     * Characterisation test for a CONFIRMED DEFECT found by this suite:
     * <strong>{@code Hierarchize(TopCount(...))} and {@code Hierarchize(BottomCount(...))} throw
     * {@link UnsupportedOperationException} for most values of {@code n}.</strong> Tracked as issue #139.
     *
     * <p>"Top N, displayed in hierarchy order" is a bread-and-butter BI query, and this is a crash
     * rather than a wrong number — the statement fails with an internal error.
     *
     * <p>Root cause, traced to the line: {@code TopBottomCountFunDef.partiallySortList} returns the
     * list produced by {@code FunUtil.stablePartialSort}, which picks one of four algorithms by the
     * ratio {@code n / size}:
     *
     * <ul>
     *   <li>ratio &gt; 0.35 → {@code stablePartialSortArray}, returns {@code ArrayList.subList} —
     *       sortable, no crash;
     *   <li>0.05 &lt; ratio ≤ 0.35 → {@code stablePartialSortMarc}, returns an anonymous
     *       {@code AbstractList} overriding only {@code get} and {@code size}, so {@code set} throws
     *       — <strong>this is the crash</strong>;
     *   <li>ratio ≤ 0.05 → {@code stablePartialSortJulian}, returns {@code Arrays.asList} — fixed
     *       size but {@code set} works, no crash.
     * </ul>
     *
     * <p>{@code Hierarchize} then calls {@code FunUtil.hierarchizeMemberList}, which sorts in place.
     * So the exact rule is: <em>crash if and only if marc's algorithm is selected and the result has
     * more than one element</em> — the size-1 case escapes because {@code hierarchizeMemberList}
     * returns early for lists of length ≤ 1.
     *
     * <p>Confirmed against three levels and 22 values of {@code n}, with 21 matching the predicted
     * ratio rule and the 22nd being exactly the documented size-1 exemption.
     *
     * <p>That data-dependence is why this has survived: whether a query crashes depends on how many
     * rows you ask for <em>relative to the level's size</em>, so a report that works on a small
     * dimension breaks when the dimension grows. Top 10 of 100 is a ratio of 0.1 — squarely in the
     * crashing band.
     *
     * <p><strong>The read-only list can survive an intervening function.</strong>
     * {@code Hierarchize(Tail(TopCount(Hierarchize(..), 2), 2))} throws too, so the blast radius is
     * wider than "{@code Hierarchize} applied directly to {@code TopCount}" — the generator found
     * that shape on its own after a first guard that only inspected the outermost call let it
     * through. It is <em>not</em> the case that every wrapper propagates it, though: a constructed
     * {@code Hierarchize(Tail(BottomCount(.., 6), 4))} does not throw. The exact condition under
     * which the immutability survives is not established here; what is established is that
     * excluding only the direct form is insufficient.
     *
     * <p>Left unfixed: {@code stablePartialSort} is the hot path for every ranked query in the
     * engine, and the candidate fixes (wrap in a mutable list here, change marc's return type, or
     * copy in {@code Hierarchize}) trade off differently against the allocation cost this code was
     * evidently written to avoid. That is a call for someone who can benchmark it.
     */
    @Test
    void hierarchizeOfRankedSetThrowsForMidRatioCounts() {
        String level = "[Store].[Stores].[Store City]"; // 24 members
        assertEquals(24, FoodMart.membersOf(level + ".Members").size(), "precondition: level size");

        // ratio 6/24 = 0.25 -> marc's algorithm -> crash
        assertThrows(
                RuntimeException.class,
                () -> FoodMart.withoutNativeEvaluation(
                        () -> FoodMart.membersOf("Hierarchize(BottomCount(" + level + ".Members, 6, "
                                + FoodMart.ADDITIVE_MEASURE + "))")),
                "witness changed — Hierarchize of a ranked set may have been fixed; if so, delete this "
                        + "test and drop the isRanked guard from MdxGenerator");

        // ratio 9/24 = 0.375 -> array algorithm -> fine, so the defect really is ratio-selected
        assertEquals(
                9,
                FoodMart.withoutNativeEvaluation(() -> FoodMart.membersOf("Hierarchize(BottomCount(" + level
                                + ".Members, 9, " + FoodMart.ADDITIVE_MEASURE + "))"))
                        .size(),
                "above the marc/array threshold the same query is expected to work");

        // ratio 1/24 = 0.042 -> julian algorithm, and size 1 is exempt from sorting anyway
        assertEquals(
                1,
                FoodMart.withoutNativeEvaluation(() -> FoodMart.membersOf("Hierarchize(BottomCount(" + level
                                + ".Members, 1, " + FoodMart.ADDITIVE_MEASURE + "))"))
                        .size(),
                "below the julian/marc threshold the same query is expected to work");

        // An intervening set function does not always rescue the query: this expression, produced
        // by the generator, still throws. Note it is the exact shape observed rather than one
        // constructed by analogy -- an invented Tail(BottomCount(..)) witness did NOT throw, so
        // the propagation is real but narrower than "any wrapper preserves immutability", and the
        // precise condition is not established here.
        assertThrows(
                RuntimeException.class,
                () -> FoodMart.membersOf("Hierarchize(Tail(TopCount(Hierarchize("
                        + "[Store].[Stores].[Store State].Members), 2, " + FoodMart.ADDITIVE_MEASURE + "), 2))"),
                "witness changed — the immutability may no longer survive an intervening set function");
    }

    /** {@code Union(S, S)} returns {@code S} — union deduplicates. */
    @HegelTest(testCases = 100)
    void unionWithItselfChangesNothing(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");

        assertEquals(
                FoodMart.membersOf(set),
                FoodMart.membersOf("Union(" + set + ", " + set + ")"),
                () -> "Union(S, S) differed from S for S = " + set);
    }

    /** {@code Except(S, S)} is empty. */
    @HegelTest(testCases = 100)
    void exceptWithItselfIsEmpty(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");

        assertEquals(
                List.of(),
                FoodMart.membersOf("Except(" + set + ", " + set + ")"),
                () -> "Except(S, S) was not empty for S = " + set);
    }

    // ------------------------------------------------------------------
    // Structural relations
    // ------------------------------------------------------------------

    /**
     * {@code Head(S, n)} followed by {@code Tail(S, count - n)} reconstructs {@code S}.
     *
     * <p>Splitting a set and putting it back together is the relation that catches off-by-one
     * errors at either end — the classic failure of a paging implementation.
     */
    @HegelTest(testCases = 120)
    void headAndTailPartitionTheSet(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        List<String> whole = FoodMart.membersOf(set);
        int n = tc.draw(dev.hegel.Generators.integers().min(0).max(Math.max(whole.size(), 1)), "n");
        tc.assume(n <= whole.size());

        List<String> head = FoodMart.membersOf("Head(" + set + ", " + n + ")");
        List<String> tail = FoodMart.membersOf("Tail(" + set + ", " + (whole.size() - n) + ")");

        List<String> rejoined = new ArrayList<>(head);
        rejoined.addAll(tail);

        assertEquals(whole, rejoined, () -> "Head(S, " + n + ") + Tail(S, " + (whole.size() - n)
                + ") did not reconstruct S for S = " + set);
    }

    /**
     * {@code Order} permutes a set: same members, same count, possibly different sequence.
     *
     * <p>Sorting that drops or duplicates a row is a silently wrong report, not a crash — a
     * top-N panel simply shows the wrong rows and nobody can tell from the output.
     */
    @HegelTest(testCases = 120)
    void orderIsAPermutation(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        String direction = tc.draw(dev.hegel.Generators.sampledFrom("ASC", "DESC", "BASC", "BDESC"), "direction");

        List<String> before = FoodMart.membersOf(set);
        List<String> after =
                FoodMart.membersOf("Order(" + set + ", " + FoodMart.ADDITIVE_MEASURE + ", " + direction + ")");

        assertEquals(before.size(), after.size(), () -> "Order changed the member count for S = " + set);
        assertEquals(
                sorted(before),
                sorted(after),
                () -> "Order changed which members are present (not a permutation) for S = " + set);
    }

    /**
     * {@code Crossjoin} has multiplicative cardinality.
     *
     * <p>Levels are drawn from different hierarchies so the crossjoin is well formed; the two are
     * independent, so no tuple can be eliminated and the count is exactly the product.
     */
    @HegelTest(testCases = 80)
    void crossjoinCardinalityIsTheProduct(TestCase tc) {
        List<String> levels = tc.draw(MdxGenerator.twoIndependentLevels(), "levels");
        String a = levels.get(0) + ".Members";
        String b = levels.get(1) + ".Members";

        int sizeA = FoodMart.membersOf(a).size();
        int sizeB = FoodMart.membersOf(b).size();
        int sizeCross = FoodMart.membersOf("Crossjoin(" + a + ", " + b + ")").size();

        assertEquals(sizeA * sizeB, sizeCross, () -> "Crossjoin(" + a + ", " + b + ") had " + sizeCross
                + " tuples, expected " + sizeA + " * " + sizeB);
    }

    // ------------------------------------------------------------------
    // Aggregation relations
    // ------------------------------------------------------------------

    /**
     * Summing an additive measure over a partition of a set equals summing over the whole set.
     *
     * <p>This is the property that actually tests the aggregation engine — segment loading, cache
     * reuse, SQL {@code GROUP BY} push-down and in-memory roll-up all have to agree, because the
     * two sides of the equation take different paths through them for the same numbers.
     *
     * <p>Deliberately phrased over an arbitrary {@code Head}/{@code Tail} split rather than over a
     * hierarchy's parent-child roll-up: a split is a mathematical identity that holds in any
     * schema, whereas parent-child roll-up is a claim about the data (and is false for FoodMart's
     * {@code [Time].[Weekly]} hierarchy, whose weeks straddle year boundaries).
     */
    @HegelTest(testCases = 100)
    void sumIsAdditiveOverAPartition(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        List<String> whole = FoodMart.membersOf(set);
        tc.assume(!whole.isEmpty());
        int n = tc.draw(dev.hegel.Generators.integers().min(0).max(Math.max(whole.size(), 1)), "n");
        tc.assume(n <= whole.size());

        String head = "Head(" + set + ", " + n + ")";
        String tail = "Tail(" + set + ", " + (whole.size() - n) + ")";

        double total = zeroIfNull(FoodMart.scalar("Sum(" + set + ", " + FoodMart.ADDITIVE_MEASURE + ")"));
        double parts = zeroIfNull(FoodMart.scalar("Sum(" + head + ", " + FoodMart.ADDITIVE_MEASURE + ")"))
                + zeroIfNull(FoodMart.scalar("Sum(" + tail + ", " + FoodMart.ADDITIVE_MEASURE + ")"));

        assertClose(total, parts, () -> "Sum over S (" + total + ") differed from Sum(Head) + Sum(Tail) (" + parts
                + ") splitting at " + n + " for S = " + set);
    }

    /**
     * {@code Count} agrees with the number of positions the same set puts on an axis.
     *
     * <p>{@code Count} is evaluated as a scalar — often without materialising the set at all —
     * while the axis path materialises it fully. The two are separate implementations of the same
     * question, so making them answer together is a real cross-check rather than a tautology.
     */
    @HegelTest(testCases = 100)
    void countAgreesWithAxisCardinality(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");

        int onAxis = FoodMart.membersOf(set).size();
        double counted = zeroIfNull(FoodMart.scalar("Count(" + set + ")"));

        assertEquals((double) onAxis, counted, () -> "Count(S) = " + counted + " but S put " + onAxis
                + " positions on an axis, for S = " + set);
    }

    /**
     * Removing empty cells can only shrink a set, never grow it, and never invents members.
     *
     * <p>The inequality is the point: {@code NON EMPTY} has a native SQL implementation that is
     * chosen or rejected by the optimiser, so this compares the optimised path against the plain
     * one without needing to know how many rows are genuinely non-empty.
     */
    @HegelTest(testCases = 100)
    void nonEmptyIsASubsetOfTheFullSet(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");

        List<String> all = FoodMart.membersOf(set);
        List<String> nonEmpty =
                FoodMart.membersOf("Filter(" + set + ", NOT IsEmpty(" + FoodMart.ADDITIVE_MEASURE + "))");

        assertTrue(
                nonEmpty.size() <= all.size(),
                () -> "non-empty filter grew the set from " + all.size() + " to " + nonEmpty.size() + " for S = " + set);
        assertTrue(
                all.containsAll(nonEmpty),
                () -> "non-empty filter produced members absent from S, for S = " + set);
    }

    // ------------------------------------------------------------------

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        out.sort(null);
        return out;
    }

    private static double zeroIfNull(Double d) {
        return d == null ? 0.0 : d;
    }

    private static void assertClose(double expected, double actual, java.util.function.Supplier<String> message) {
        double scale = Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
        if (Math.abs(expected - actual) > EPSILON * scale) {
            throw new AssertionError(message.get());
        }
    }
}
