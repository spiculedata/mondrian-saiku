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

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.List;
import mondrian.util.ArrayStack;
import mondrian.util.CartesianProductList;
import mondrian.util.CompositeList;
import mondrian.util.ConcatenableList;

/**
 * Oracle-based properties for Mondrian's specialised list implementations.
 *
 * <p>Each of these exists to avoid a copy that {@code ArrayList} would make, and each is therefore a
 * lazy <em>view</em> over other lists rather than a container of its own. That laziness is the whole
 * point and also the whole risk: the arithmetic that maps an index in the view onto an index in the
 * underlying lists is easy to get wrong at a boundary, and wrong in a way that returns a plausible
 * neighbouring element rather than throwing.
 *
 * <p>Every property below therefore builds the same content eagerly with plain {@code ArrayList}
 * code and demands the two agree. There is no judgement involved and nothing to get wrong in the
 * expectation.
 */
class ListStructurePropertyTest {

    private static Generator<List<Integer>> smallList() {
        return dev.hegel.Generators.lists(dev.hegel.Generators.integers().min(0).max(9))
                .maxSize(5);
    }

    // ------------------------------------------------------------------
    // CartesianProductList
    // ------------------------------------------------------------------

    /**
     * {@link CartesianProductList} agrees, element for element, with a nested-loop product.
     *
     * <p>This backs MDX {@code Crossjoin}, so an indexing error here is a wrong tuple on a report
     * axis. The oracle is the obvious nested loop; the implementation is index arithmetic over the
     * component lists, which is a genuinely different computation.
     */
    @HegelTest(testCases = 300)
    void cartesianProductMatchesNestedLoops(TestCase tc) {
        List<List<Integer>> components = tc.draw(
                dev.hegel.Generators.lists(smallList().filter(l -> !l.isEmpty()))
                        .minSize(1)
                        .maxSize(3),
                "components");

        CartesianProductList<Integer> subject = new CartesianProductList<>(components);
        List<List<Integer>> expected = nestedLoopProduct(components);

        assertEquals(expected.size(), subject.size(), () -> "size for " + components);
        for (int i = 0; i < expected.size(); i++) {
            final int index = i;
            assertEquals(expected.get(i), subject.get(i), () -> "tuple " + index + " for " + components);
        }
        assertEquals(expected, new ArrayList<>(subject), () -> "iteration order for " + components);
    }

    /** A product with an empty component is empty. */
    @HegelTest(testCases = 100)
    void cartesianProductWithAnEmptyComponentIsEmpty(TestCase tc) {
        List<List<Integer>> components = tc.draw(
                dev.hegel.Generators.lists(smallList()).minSize(1).maxSize(3), "components");
        tc.assume(components.stream().anyMatch(List::isEmpty));

        assertEquals(0, new CartesianProductList<>(components).size(), () -> "expected empty product for " + components);
    }

    // ------------------------------------------------------------------
    // CompositeList
    // ------------------------------------------------------------------

    /** {@link CompositeList} is the concatenation of its parts. */
    @HegelTest(testCases = 300)
    @SuppressWarnings("unchecked")
    void compositeListIsTheConcatenation(TestCase tc) {
        List<List<Integer>> parts =
                tc.draw(dev.hegel.Generators.lists(smallList()).minSize(1).maxSize(4), "parts");

        CompositeList<Integer> subject = CompositeList.of(parts.toArray(new List[0]));
        List<Integer> expected = new ArrayList<>();
        parts.forEach(expected::addAll);

        assertEquals(expected.size(), subject.size(), () -> "size for " + parts);
        assertEquals(expected, new ArrayList<>(subject), () -> "contents for " + parts);
        for (int i = 0; i < expected.size(); i++) {
            final int index = i;
            assertEquals(expected.get(i), subject.get(i), () -> "get(" + index + ") for " + parts);
        }
    }

    /** Indexing outside a {@link CompositeList} throws rather than returning a neighbour. */
    @HegelTest(testCases = 150)
    @SuppressWarnings("unchecked")
    void compositeListRejectsOutOfRangeIndexes(TestCase tc) {
        List<List<Integer>> parts =
                tc.draw(dev.hegel.Generators.lists(smallList()).minSize(1).maxSize(3), "parts");

        CompositeList<Integer> subject = CompositeList.of(parts.toArray(new List[0]));
        int size = subject.size();

        assertThrows(IndexOutOfBoundsException.class, () -> subject.get(size), () -> "get(size) for " + parts);
        assertThrows(IndexOutOfBoundsException.class, () -> subject.get(-1), () -> "get(-1) for " + parts);
    }

    // ------------------------------------------------------------------
    // ConcatenableList
    // ------------------------------------------------------------------

    /**
     * {@link ConcatenableList} agrees with plain concatenation, before and after
     * {@code consolidate()}.
     *
     * <p>It has two internal representations — a list of un-merged chunks, and a single flattened
     * list once {@code consolidate} runs — and answers must not depend on which one it currently
     * holds. Checking both sides of that switch is the point; a bug that only appears in the lazy
     * representation is invisible to a test that consolidates first.
     */
    @HegelTest(testCases = 300)
    void concatenableListMatchesPlainConcatenation(TestCase tc) {
        List<List<Integer>> parts =
                tc.draw(dev.hegel.Generators.lists(smallList()).minSize(1).maxSize(4), "parts");

        ConcatenableList<Integer> subject = new ConcatenableList<>();
        List<Integer> expected = new ArrayList<>();
        for (List<Integer> part : parts) {
            subject.addAll(part);
            expected.addAll(part);
        }

        assertEquals(expected.size(), subject.size(), () -> "size before consolidate for " + parts);
        assertEquals(expected, new ArrayList<>(subject), () -> "contents before consolidate for " + parts);
        for (int i = 0; i < expected.size(); i++) {
            final int index = i;
            assertEquals(expected.get(i), subject.get(i), () -> "get(" + index + ") before consolidate for " + parts);
        }

        subject.consolidate();

        assertEquals(expected.size(), subject.size(), () -> "size after consolidate for " + parts);
        assertEquals(expected, new ArrayList<>(subject), () -> "contents after consolidate for " + parts);
        for (int i = 0; i < expected.size(); i++) {
            final int index = i;
            assertEquals(expected.get(i), subject.get(i), () -> "get(" + index + ") after consolidate for " + parts);
        }
    }

    // ------------------------------------------------------------------
    // ArrayStack
    // ------------------------------------------------------------------

    /**
     * {@link ArrayStack} is last-in-first-out and agrees with a list used as a stack.
     *
     * <p>Mondrian uses it for evaluator save/restore, so push/pop symmetry is what keeps a query's
     * evaluation context from leaking between cells.
     */
    @HegelTest(testCases = 300)
    void arrayStackIsLastInFirstOut(TestCase tc) {
        List<Integer> pushes = tc.draw(
                dev.hegel.Generators.lists(dev.hegel.Generators.integers().min(0).max(50)).maxSize(30),
                "pushes");

        ArrayStack<Integer> subject = new ArrayStack<>();
        List<Integer> model = new ArrayList<>();

        for (Integer value : pushes) {
            subject.push(value);
            model.add(value);
            assertEquals(model.get(model.size() - 1), subject.peek(), () -> "peek after pushing " + value);
            assertEquals(model.size(), subject.size(), "size after push");
        }

        while (!model.isEmpty()) {
            Integer expected = model.remove(model.size() - 1);
            assertEquals(expected, subject.pop(), () -> "pop order for " + pushes);
        }
        assertTrue(subject.isEmpty(), "stack not empty after popping everything");
    }

    /** Popping or peeking an empty {@link ArrayStack} fails rather than returning a stale value. */
    @HegelTest(testCases = 60)
    void emptyArrayStackRejectsPopAndPeek(TestCase tc) {
        int pushThenPop = tc.draw(dev.hegel.Generators.integers().min(0).max(5), "pushThenPop");

        ArrayStack<Integer> subject = new ArrayStack<>();
        for (int i = 0; i < pushThenPop; i++) {
            subject.push(i);
        }
        for (int i = 0; i < pushThenPop; i++) {
            subject.pop();
        }

        assertThrows(EmptyStackException.class, subject::pop, "pop on an empty stack");
        assertThrows(EmptyStackException.class, subject::peek, "peek on an empty stack");
    }

    // ------------------------------------------------------------------

    /** The obvious eager Cartesian product, used as the oracle. */
    private static List<List<Integer>> nestedLoopProduct(List<List<Integer>> components) {
        List<List<Integer>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<Integer> component : components) {
            List<List<Integer>> next = new ArrayList<>();
            for (List<Integer> prefix : result) {
                for (Integer value : component) {
                    List<Integer> extended = new ArrayList<>(prefix);
                    extended.add(value);
                    next.add(extended);
                }
            }
            result = next;
        }
        return result;
    }
}
