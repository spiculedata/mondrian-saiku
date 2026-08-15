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

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;
import mondrian.util.ArraySortedSet;
import org.junit.jupiter.api.Test;

/**
 * Model-based properties for {@link ArraySortedSet}, using {@link TreeSet} as the oracle.
 *
 * <p>{@code ArraySortedSet} is a hand-rolled, array-backed {@link SortedSet} that Mondrian keeps in
 * every {@code SegmentColumn} — it holds the set of column values a cached segment covers, so its
 * answers decide whether a cached segment can serve a query. A wrong {@code contains} or a wrong
 * {@code merge} does not throw; it returns the wrong <em>cells</em>, which is the worst failure
 * mode a cache has.
 *
 * <p>Because it reimplements an interface the JDK already implements correctly, every method has a
 * free oracle. That is the ideal shape for property-based testing: no hand-written expectations, no
 * judgement about what the answer should be — just "agree with {@link TreeSet}".
 */
class ArraySortedSetPropertyTest {

    /** Small alphabet, so generated sets actually overlap and the merge paths get exercised. */
    private static Generator<String> element() {
        return dev.hegel.Generators.sampledFrom("a", "b", "c", "d", "e", "f", "g", "h");
    }

    private static Generator<SortedSet<String>> model() {
        return dev.hegel.Generators.sets(element()).maxSize(8).map(TreeSet::new);
    }

    private static ArraySortedSet<String> subject(SortedSet<String> model) {
        return new ArraySortedSet<>(model.toArray(new String[0]));
    }

    // ------------------------------------------------------------------
    // Whole-set behaviour
    // ------------------------------------------------------------------

    /** Size, iteration order and {@code contains} agree with the oracle. */
    @HegelTest(testCases = 300)
    void agreesWithTreeSetOnBasicQueries(TestCase tc) {
        SortedSet<String> model = tc.draw(model(), "model");
        ArraySortedSet<String> subject = subject(model);

        assertEquals(model.size(), subject.size(), "size");
        assertEquals(new ArrayList<>(model), new ArrayList<>(subject), "iteration order");

        // contains() is checked over the whole alphabet, not just over members, so absent
        // elements are probed as hard as present ones — a binary search that is wrong at the
        // boundaries is wrong about absence, and absence is what the cache asks about.
        for (String probe : ALPHABET) {
            assertEquals(model.contains(probe), subject.contains(probe), () -> "contains(" + probe + ") on " + model);
        }
    }

    /** {@code first} and {@code last} agree, including how they fail when the set is empty. */
    @HegelTest(testCases = 200)
    void agreesWithTreeSetOnFirstAndLast(TestCase tc) {
        SortedSet<String> model = tc.draw(model(), "model");
        ArraySortedSet<String> subject = subject(model);

        if (model.isEmpty()) {
            assertThrows(NoSuchElementException.class, subject::first, "first() on empty set");
            assertThrows(NoSuchElementException.class, subject::last, "last() on empty set");
        } else {
            assertEquals(model.first(), subject.first(), "first");
            assertEquals(model.last(), subject.last(), "last");
        }
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    /**
     * {@code headSet}, {@code tailSet} and {@code subSet} agree with the oracle.
     *
     * <p>Bounds are drawn from the alphabet rather than from the set's own members, so the search
     * lands both on and between elements — an off-by-one in the "not found, use the insertion
     * point" arithmetic only shows up for a bound that is absent.
     */
    @HegelTest(testCases = 400)
    void viewsAgreeWithTreeSet(TestCase tc) {
        SortedSet<String> model = tc.draw(model(), "model");
        String lo = tc.draw(element(), "lo");
        String hi = tc.draw(element(), "hi");
        ArraySortedSet<String> subject = subject(model);

        assertEquals(list(model.headSet(hi)), list(subject.headSet(hi)), () -> "headSet(" + hi + ") of " + model);
        assertEquals(list(model.tailSet(lo)), list(subject.tailSet(lo)), () -> "tailSet(" + lo + ") of " + model);

        if (lo.compareTo(hi) <= 0) {
            assertEquals(
                    list(model.subSet(lo, hi)),
                    list(subject.subSet(lo, hi)),
                    () -> "subSet(" + lo + ", " + hi + ") of " + model);
        }
    }

    // ------------------------------------------------------------------
    // merge
    // ------------------------------------------------------------------

    /**
     * {@code merge} computes the union.
     *
     * <p>This is the operation the segment cache relies on when it widens the set of values a
     * cached segment covers.
     */
    @HegelTest(testCases = 400)
    void mergeComputesTheUnion(TestCase tc) {
        SortedSet<String> left = tc.draw(model(), "left");
        SortedSet<String> right = tc.draw(model(), "right");

        SortedSet<String> expected = new TreeSet<>(left);
        expected.addAll(right);

        ArraySortedSet<String> merged = subject(left).merge(subject(right));

        assertEquals(new ArrayList<>(expected), list(merged), () -> "merge of " + left + " and " + right);
    }

    /**
     * Characterisation test for a KNOWN DEFECT (issue #143): {@code merge} ignores the offsets of a view.
     *
     * <p>{@link ArraySortedSet#subSet}, {@code headSet} and {@code tailSet} return a new
     * {@code ArraySortedSet} that shares the backing array and narrows it with {@code start}/
     * {@code end} offsets. {@code merge} then reads {@code data1[0]} upward while using
     * {@code size()} — which is {@code end - start} — as the bound. For any view whose
     * {@code start} is non-zero it therefore merges the wrong slice: the first {@code size()}
     * elements of the whole array rather than the view's own elements.
     *
     * <p>Below, {@code tailSet("c")} is {@code [c, d]}, so merging it with {@code [e]} should give
     * {@code [c, d, e]}. It gives {@code [a, b, e]} — the first two elements of the backing array.
     *
     * <p>Not fixed here because the fix is a product change with a live caller: {@code
     * SegmentColumn.merge} is the one place this is reached from, and whether it can pass a view
     * determines whether this is a latent bug or an active cache-correctness bug. That assessment
     * belongs with the segment-cache owners, not with a test suite. This test pins the current
     * behaviour so the defect cannot be lost, and fails the moment someone fixes it.
     */
    @Test
    void mergeIgnoresViewOffsetsOfTheReceiver() {
        ArraySortedSet<String> whole = new ArraySortedSet<>(new String[] {"a", "b", "c", "d"});
        SortedSet<String> tail = whole.tailSet("c"); // [c, d]
        assertEquals(List.of("c", "d"), list(tail), "precondition: the view itself is correct");

        ArraySortedSet<String> merged = ((ArraySortedSet<String>) tail).merge(new ArraySortedSet<>(new String[] {"e"}));

        assertEquals(
                List.of("a", "b", "e"),
                list(merged),
                "witness changed — merge may have been fixed; if so, delete this test and rely on "
                        + "mergeComputesTheUnion extended to views");
    }

    private static final List<String> ALPHABET = List.of("a", "b", "c", "d", "e", "f", "g", "h", "z");

    private static <T> List<T> list(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        it.forEach(out::add);
        return out;
    }
}
