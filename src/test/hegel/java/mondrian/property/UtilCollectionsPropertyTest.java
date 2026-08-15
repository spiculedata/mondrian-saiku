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

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import mondrian.olap.Util;
import mondrian.util.ArraySortedSet;
import mondrian.util.Pair;

/**
 * Oracle-based properties for the small collection helpers in {@link Util}, and the
 * {@link Pair} contracts.
 *
 * <p>These are the functions that every other part of Mondrian leans on without thinking about —
 * a binary search, a set intersection, a flatten. They are short enough that nobody writes many
 * tests for them, and central enough that a boundary bug in one propagates everywhere. Generated
 * inputs cost nothing here (no database, no schema) and reach the empty, singleton, duplicate and
 * boundary cases that hand-written examples skip.
 */
class UtilCollectionsPropertyTest {

    private static Generator<List<Integer>> integers() {
        return dev.hegel.Generators.lists(dev.hegel.Generators.integers().min(-20).max(20))
                .maxSize(20);
    }

    /** Sorted, distinct integers — the precondition {@code binarySearch} and {@code intersect} assume. */
    private static Generator<List<Integer>> sortedDistinct() {
        return dev.hegel.Generators.sets(dev.hegel.Generators.integers().min(-20).max(20))
                .maxSize(15)
                .map(set -> new ArrayList<>(new TreeSet<>(set)));
    }

    // ------------------------------------------------------------------
    // binarySearch
    // ------------------------------------------------------------------

    /**
     * {@link Util#binarySearch} agrees with {@link Arrays#binarySearch} over a sorted range.
     *
     * <p>Both the found and not-found cases matter: the JDK defines the miss result as
     * {@code -(insertionPoint) - 1}, and {@code ArraySortedSet} does index arithmetic on that value
     * to build its views. A miss encoded differently would silently shift every {@code headSet} and
     * {@code tailSet} boundary by one.
     */
    @HegelTest(testCases = 400)
    void binarySearchAgreesWithTheJdk(TestCase tc) {
        List<Integer> sorted = tc.draw(sortedDistinct(), "sorted");
        int probe = tc.draw(dev.hegel.Generators.integers().min(-25).max(25), "probe");

        Integer[] array = sorted.toArray(new Integer[0]);

        assertEquals(
                Arrays.binarySearch(array, 0, array.length, probe),
                Util.binarySearch(array, 0, array.length, probe),
                () -> "binarySearch(" + sorted + ", " + probe + ")");
    }

    /** Searching a sub-range only ever reports positions inside that range. */
    @HegelTest(testCases = 300)
    void binarySearchRespectsItsRange(TestCase tc) {
        List<Integer> sorted = tc.draw(sortedDistinct().filter(l -> l.size() >= 2), "sorted");
        int probe = tc.draw(dev.hegel.Generators.integers().min(-25).max(25), "probe");
        int start = tc.draw(dev.hegel.Generators.integers().min(0).max(sorted.size() - 1), "start");
        int end = tc.draw(dev.hegel.Generators.integers().min(start).max(sorted.size()), "end");
        tc.assume(start <= end);

        Integer[] array = sorted.toArray(new Integer[0]);
        int result = Util.binarySearch(array, start, end, probe);

        int position = result >= 0 ? result : -(result + 1);
        assertTrue(
                position >= start && position <= end,
                () -> "binarySearch reported position " + position + " outside [" + start + ", " + end + "] for "
                        + sorted);
    }

    // ------------------------------------------------------------------
    // intersect
    // ------------------------------------------------------------------

    /**
     * {@link Util#intersect} agrees with {@link TreeSet#retainAll}.
     *
     * <p>It has a fast path for two {@link ArraySortedSet} operands and a general path for anything
     * else, and both must produce the same answer — this is the function behind deciding which
     * cached segment values a query can reuse.
     */
    @HegelTest(testCases = 400)
    void intersectAgreesWithRetainAll(TestCase tc) {
        List<Integer> left = tc.draw(sortedDistinct(), "left");
        List<Integer> right = tc.draw(sortedDistinct(), "right");

        SortedSet<Integer> expected = new TreeSet<>(left);
        expected.retainAll(new TreeSet<>(right));

        SortedSet<Integer> actual = Util.intersect(
                new ArraySortedSet<>(left.toArray(new Integer[0])),
                new ArraySortedSet<>(right.toArray(new Integer[0])));

        assertEquals(
                new ArrayList<>(expected),
                new ArrayList<>(actual),
                () -> "intersect(" + left + ", " + right + ")");
    }

    /** Intersection is commutative and idempotent. */
    @HegelTest(testCases = 300)
    void intersectIsCommutativeAndIdempotent(TestCase tc) {
        List<Integer> left = tc.draw(sortedDistinct(), "left");
        List<Integer> right = tc.draw(sortedDistinct(), "right");

        ArraySortedSet<Integer> a = new ArraySortedSet<>(left.toArray(new Integer[0]));
        ArraySortedSet<Integer> b = new ArraySortedSet<>(right.toArray(new Integer[0]));

        assertEquals(
                new ArrayList<>(Util.intersect(a, b)),
                new ArrayList<>(Util.intersect(b, a)),
                () -> "intersect is not commutative for " + left + " and " + right);
        assertEquals(
                new ArrayList<>(a),
                new ArrayList<>(Util.intersect(a, a)),
                () -> "intersect is not idempotent for " + left);
    }

    // ------------------------------------------------------------------
    // Predicates and flatteners
    // ------------------------------------------------------------------

    /**
     * {@link Util#isSorted} agrees with checking each adjacent pair for <em>strict</em> increase.
     *
     * <p>Strict is the documented contract — "returns whether a list is strictly sorted" — so a
     * list with a repeated element is not sorted by this definition. Worth stating explicitly,
     * because the name reads as though it means the ordinary non-decreasing sense, and this test
     * initially asserted that weaker version and failed on the minimal counterexample {@code [0, 0]}.
     */
    @HegelTest(testCases = 400)
    void isSortedAgreesWithStrictPairwiseComparison(TestCase tc) {
        List<Integer> values = tc.draw(integers(), "values");

        boolean expected = true;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i - 1) >= values.get(i)) {
                expected = false;
                break;
            }
        }

        final boolean sorted = expected;
        assertEquals(sorted, Util.isSorted(values), () -> "isSorted(" + values + ")");
    }

    /** {@link Util#isDistinct} agrees with comparing the size of a {@link Set} of the elements. */
    @HegelTest(testCases = 400)
    void isDistinctAgreesWithASet(TestCase tc) {
        List<Integer> values = tc.draw(integers(), "values");

        Set<Integer> unique = new LinkedHashSet<>(values);
        assertEquals(unique.size() == values.size(), Util.isDistinct(values), () -> "isDistinct(" + values + ")");
    }

    /**
     * Sorting a list of distinct values makes {@link Util#isSorted} true.
     *
     * <p>Distinct, because the contract is strict sortedness: sorting a list with duplicates leaves
     * adjacent equal elements, which strict sortedness rejects by design.
     */
    @HegelTest(testCases = 200)
    void sortingDistinctValuesMakesIsSortedTrue(TestCase tc) {
        List<Integer> values = tc.draw(sortedDistinct(), "values");

        List<Integer> shuffled = new ArrayList<>(values);
        java.util.Collections.reverse(shuffled);
        shuffled.sort(null);

        assertTrue(Util.isSorted(shuffled), () -> "isSorted was false for the sorted form of " + values);
    }

    /** {@link Util#flatList} preserves contents and order. */
    @HegelTest(testCases = 300)
    void flatListPreservesContents(TestCase tc) {
        List<Integer> values = tc.draw(integers(), "values");

        assertEquals(values, Util.flatList(values), () -> "flatList changed " + values);
    }

    /** {@link Util#appendArrays} concatenates. */
    @HegelTest(testCases = 300)
    void appendArraysConcatenates(TestCase tc) {
        List<Integer> first = tc.draw(integers(), "first");
        List<Integer> second = tc.draw(integers(), "second");
        List<Integer> third = tc.draw(integers(), "third");

        Integer[] result = Util.appendArrays(
                first.toArray(new Integer[0]),
                second.toArray(new Integer[0]),
                third.toArray(new Integer[0]));

        List<Integer> expected = new ArrayList<>(first);
        expected.addAll(second);
        expected.addAll(third);

        assertEquals(expected, Arrays.asList(result), () -> "appendArrays(" + first + ", " + second + ", " + third + ")");
    }

    // ------------------------------------------------------------------
    // Pair
    // ------------------------------------------------------------------

    /** Equal components imply equal pairs and equal hash codes. */
    @HegelTest(testCases = 400)
    void pairEqualityFollowsComponents(TestCase tc) {
        int left = tc.draw(dev.hegel.Generators.integers().min(-5).max(5), "left");
        int right = tc.draw(dev.hegel.Generators.integers().min(-5).max(5), "right");

        Pair<Integer, Integer> a = Pair.of(left, right);
        Pair<Integer, Integer> b = Pair.of(left, right);

        assertEquals(a, b, "pairs with equal components must be equal");
        assertEquals(a.hashCode(), b.hashCode(), "equal pairs must have equal hash codes");
        assertEquals(0, a.compareTo(b), "equal pairs must compare equal");
    }

    /**
     * {@link Pair#compareTo} is a lexicographic total order consistent with {@code equals}.
     *
     * <p>Pairs are used as map and cache keys throughout the ROLAP layer, so a comparator that
     * disagrees with {@code equals} produces sorted structures whose lookups miss entries they
     * demonstrably contain.
     */
    @HegelTest(testCases = 400)
    void pairComparisonIsLexicographicAndConsistent(TestCase tc) {
        Pair<Integer, Integer> a = drawPair(tc, "a");
        Pair<Integer, Integer> b = drawPair(tc, "b");

        int ab = a.compareTo(b);
        int ba = b.compareTo(a);

        assertEquals(Integer.signum(ab), -Integer.signum(ba), () -> "antisymmetry broken for " + a + " and " + b);
        assertEquals(ab == 0, a.equals(b), () -> "compareTo and equals disagree for " + a + " and " + b);

        int expected = a.left.equals(b.left)
                ? Integer.compare(a.right, b.right)
                : Integer.compare(a.left, b.left);
        assertEquals(
                Integer.signum(expected),
                Integer.signum(ab),
                () -> "comparison is not lexicographic for " + a + " and " + b);
    }

    /** {@link Pair#compareTo} is transitive. */
    @HegelTest(testCases = 400)
    void pairComparisonIsTransitive(TestCase tc) {
        Pair<Integer, Integer> a = drawPair(tc, "a");
        Pair<Integer, Integer> b = drawPair(tc, "b");
        Pair<Integer, Integer> c = drawPair(tc, "c");

        if (a.compareTo(b) <= 0 && b.compareTo(c) <= 0) {
            assertTrue(a.compareTo(c) <= 0, () -> "transitivity broken: " + a + " <= " + b + " <= " + c);
        }
    }

    /** {@code Map.Entry} accessors report the components. */
    @HegelTest(testCases = 200)
    void pairExposesItsComponents(TestCase tc) {
        Pair<Integer, Integer> pair = drawPair(tc, "pair");

        assertEquals(pair.left, pair.getKey(), "getKey");
        assertEquals(pair.right, pair.getValue(), "getValue");
    }

    private static Pair<Integer, Integer> drawPair(TestCase tc, String label) {
        // A narrow range so equal lefts are common; with a wide range the tie-breaking branch of
        // the lexicographic comparison would almost never be reached.
        List<Integer> components = tc.draw(
                dev.hegel.Generators.lists(dev.hegel.Generators.integers().min(-3).max(3))
                        .minSize(2)
                        .maxSize(2),
                label);
        return Pair.of(components.get(0), components.get(1));
    }
}
