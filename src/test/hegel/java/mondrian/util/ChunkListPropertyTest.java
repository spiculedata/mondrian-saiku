/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule / Saiku community
// All Rights Reserved.
*/
package mondrian.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Model-based properties for {@link ChunkList}, using {@link ArrayList} as the oracle.
 *
 * <p>{@code ChunkList} is a hand-written list that stores elements in linked fixed-size chunks
 * rather than one backing array, to avoid the copy-on-grow cost when Mondrian accumulates large
 * result sets. That structure — a doubly-linked chain of arrays, each with a header holding its
 * previous link, next link and size — is exactly the kind of thing that goes subtly wrong at chunk
 * boundaries, and it does so only after a specific <em>sequence</em> of operations. A single-operation
 * test cannot reach those states.
 *
 * <p>So these are <strong>stateful</strong> properties: each test case generates a random sequence
 * of mutations, applies each one to both a {@code ChunkList} and an {@code ArrayList}, and checks
 * after every step that
 *
 * <ol>
 *   <li>the contents still match the oracle, and
 *   <li>{@link ChunkList#isValid} still holds — the internal chunk links and sizes are consistent.
 * </ol>
 *
 * <p>Checking after <em>every</em> step rather than only at the end is what makes a failure
 * diagnosable: the shrunk counterexample names the shortest operation sequence that breaks it, and
 * the step index says which operation did the damage.
 *
 * <p>This class lives in {@code mondrian.util} rather than {@code mondrian.property} precisely so it
 * can call the package-private {@code isValid}. Structural corruption that has not yet surfaced as a
 * wrong answer is still a bug, and it is the one a black-box test would miss.
 */
class ChunkListPropertyTest {

    /**
     * Enough elements to fill several chunks. The default chunk holds a few dozen entries, so a
     * sequence has to be long enough to cross boundaries — a 5-element list would only ever
     * exercise the first chunk and would prove nothing about the linking.
     */
    private static final int MAX_OPERATIONS = 120;

    /** One mutation, as a tag plus its arguments. */
    private record Operation(String kind, int index, int value) {}

    private static Generator<Operation> operation() {
        return dev.hegel.Generators.composite(tc -> new Operation(
                tc.draw(dev.hegel.Generators.sampledFrom(
                        // Weighted towards adding, so the list grows to a size where the chunk
                        // structure matters instead of hovering around empty.
                        "add", "add", "add", "add", "addAt", "removeAt", "set", "clear")),
                tc.draw(dev.hegel.Generators.integers().min(0).max(1000)),
                tc.draw(dev.hegel.Generators.integers().min(-100).max(100))));
    }

    @HegelTest(testCases = 300)
    void matchesArrayListUnderArbitraryOperationSequences(TestCase tc) {
        List<Operation> operations =
                tc.draw(dev.hegel.Generators.lists(operation()).maxSize(MAX_OPERATIONS), "operations");

        ChunkList<Integer> subject = new ChunkList<>();
        List<Integer> model = new ArrayList<>();

        for (int step = 0; step < operations.size(); step++) {
            Operation op = operations.get(step);
            applyTo(subject, model, op);

            final int at = step;
            assertTrue(
                    subject.isValid(false, false),
                    () -> "ChunkList structure is corrupt after step " + at + " (" + op.kind() + ")");
            assertEquals(model.size(), subject.size(), () -> "size diverged after step " + at + " (" + op.kind() + ")");
            assertEquals(model, new ArrayList<>(subject), () -> "contents diverged after step " + at + " ("
                    + op.kind() + ")");
        }
    }

    /**
     * Iteration and random access agree.
     *
     * <p>{@code ChunkList} extends {@code AbstractSequentialList}, so {@code get(i)} is implemented
     * by walking an iterator. Making the two agree checks the walk lands on the right element after
     * crossing a chunk boundary — an off-by-one in the chunk header arithmetic shows up here and
     * nowhere else.
     */
    @HegelTest(testCases = 200)
    void randomAccessAgreesWithIteration(TestCase tc) {
        List<Integer> values = tc.draw(
                dev.hegel.Generators.lists(dev.hegel.Generators.integers().min(-100).max(100))
                        .maxSize(MAX_OPERATIONS),
                "values");

        ChunkList<Integer> subject = new ChunkList<>(values);

        int i = 0;
        for (Iterator<Integer> it = subject.iterator(); it.hasNext(); i++) {
            Integer viaIterator = it.next();
            Integer viaIndex = subject.get(i);
            final int index = i;
            assertEquals(viaIterator, viaIndex, () -> "get(" + index + ") disagreed with iteration order");
        }
        assertEquals(values.size(), i, "iteration visited the wrong number of elements");
    }

    /** {@code indexOf}, {@code lastIndexOf} and {@code contains} agree with the oracle. */
    @HegelTest(testCases = 200)
    void searchAgreesWithArrayList(TestCase tc) {
        List<Integer> values = tc.draw(
                // A narrow value range so duplicates are common -- otherwise indexOf and
                // lastIndexOf would almost always coincide and the distinction would go untested.
                dev.hegel.Generators.lists(dev.hegel.Generators.integers().min(0).max(5)).maxSize(60),
                "values");
        int probe = tc.draw(dev.hegel.Generators.integers().min(-1).max(6), "probe");

        ChunkList<Integer> subject = new ChunkList<>(values);
        List<Integer> model = new ArrayList<>(values);

        assertEquals(model.contains(probe), subject.contains(probe), () -> "contains(" + probe + ")");
        assertEquals(model.indexOf(probe), subject.indexOf(probe), () -> "indexOf(" + probe + ")");
        assertEquals(model.lastIndexOf(probe), subject.lastIndexOf(probe), () -> "lastIndexOf(" + probe + ")");
    }

    /** {@code toArray} round-trips through the oracle. */
    @HegelTest(testCases = 200)
    void toArrayMatchesArrayList(TestCase tc) {
        List<Integer> values = tc.draw(
                dev.hegel.Generators.lists(dev.hegel.Generators.integers().min(-100).max(100)).maxSize(80),
                "values");

        ChunkList<Integer> subject = new ChunkList<>(values);

        assertEquals(List.of(new ArrayList<>(values).toArray()), List.of(subject.toArray()), "toArray()");
        assertEquals(
                values,
                List.of(subject.toArray(new Integer[0])),
                "toArray(T[]) did not reproduce the contents");
    }

    private static void applyTo(ChunkList<Integer> subject, List<Integer> model, Operation op) {
        switch (op.kind()) {
            case "add" -> {
                subject.add(op.value());
                model.add(op.value());
            }
            case "addAt" -> {
                int index = model.isEmpty() ? 0 : op.index() % (model.size() + 1);
                subject.add(index, op.value());
                model.add(index, op.value());
            }
            case "removeAt" -> {
                if (!model.isEmpty()) {
                    int index = op.index() % model.size();
                    subject.remove(index);
                    model.remove(index);
                }
            }
            case "set" -> {
                if (!model.isEmpty()) {
                    int index = op.index() % model.size();
                    subject.set(index, op.value());
                    model.set(index, op.value());
                }
            }
            case "clear" -> {
                subject.clear();
                model.clear();
            }
            default -> throw new IllegalStateException("unknown operation " + op.kind());
        }
    }
}
