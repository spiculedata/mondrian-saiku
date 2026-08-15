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

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;

/**
 * Differential properties: <strong>native evaluation must agree with in-memory evaluation.</strong>
 *
 * <p>Mondrian has two implementations of much of MDX. The in-memory one walks members and evaluates
 * expressions; the native one rewrites the query into SQL and pushes it into the database. Which
 * runs depends on the {@code EnableNative*} flags, on the shape of the query, and on what the
 * optimiser decides — so a given query may take either path, and the user has no way to tell which.
 *
 * <p>That makes "the two agree" the single most valuable property in this suite. It needs no oracle
 * (each path is the other's oracle), it covers the whole surface at once, and it targets the
 * failure mode that matters most in practice: a fast path that returns <em>different numbers</em>
 * from the slow path, silently, in the configuration everyone actually runs.
 *
 * <p>It is not hypothetical. The {@code Filter(S, TRUE)} defect recorded in
 * {@code MdxEngineMetamorphicPropertyTest} is exactly this shape, and it is why these properties
 * exist as a class of their own rather than as one more relation among the metamorphic ones.
 *
 * <p><strong>Scoping.</strong> Native evaluation applies non-empty semantics that the in-memory path
 * does not (the {@code Filter} defect above). On a level containing fact-less members the two paths
 * therefore differ for that reason alone, and every property here would fail on it whatever function
 * was under test. So these properties draw from {@link FoodMart#fullyNonEmptyLevels()} — levels
 * whose members all have fact data — which removes exactly that one confound and leaves ordering,
 * limits, cardinality and aggregation genuinely compared.
 *
 * <p>That is deliberately better than excluding the affected <em>functions</em>: excluding
 * {@code Filter} and {@code TopCount} would have removed the two most heavily used set functions in
 * Mondrian from the differential. Scoping the data instead keeps them in.
 */
class NativeEvaluationEquivalencePropertyTest {

    /**
     * The two paths return the same members, in the same order, for a generated set expression.
     *
     * <p>Order is compared as well as content because a native rewrite emits {@code ORDER BY} while
     * the in-memory path sorts in Java; the two can agree on membership and still disagree on
     * sequence, which is a wrong report rather than a wrong number and is just as user-visible.
     */
    @HegelTest(testCases = 120)
    void nativeAndInMemoryAgreeOnMembers(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExprOverPopulatedLevel(), "set");

        List<String> nativeResult = FoodMart.membersOf(set);
        List<String> inMemoryResult = FoodMart.withoutNativeEvaluation(() -> FoodMart.membersOf(set));

        assertEquals(
                inMemoryResult,
                nativeResult,
                () -> "native and in-memory evaluation disagree for S = " + set);
    }

    /** The two paths agree on the aggregate of an additive measure over the set. */
    @HegelTest(testCases = 100)
    void nativeAndInMemoryAgreeOnSums(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExprOverPopulatedLevel(), "set");

        String expr = "Sum(" + set + ", " + FoodMart.ADDITIVE_MEASURE + ")";
        Double nativeSum = FoodMart.scalar(expr);
        Double inMemorySum = FoodMart.withoutNativeEvaluation(() -> FoodMart.scalar(expr));

        assertEquals(inMemorySum, nativeSum, () -> "native and in-memory Sum disagree for S = " + set);
    }

    /** The two paths agree on cardinality. */
    @HegelTest(testCases = 100)
    void nativeAndInMemoryAgreeOnCounts(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExprOverPopulatedLevel(), "set");

        String expr = "Count(" + set + ")";
        Double nativeCount = FoodMart.scalar(expr);
        Double inMemoryCount = FoodMart.withoutNativeEvaluation(() -> FoodMart.scalar(expr));

        assertEquals(inMemoryCount, nativeCount, () -> "native and in-memory Count disagree for S = " + set);
    }

    /**
     * The two paths agree for crossjoins, which have their own native rewrite.
     *
     * <p>{@code EnableNativeCrossJoin} is a separate code path from the filter and top-count ones,
     * and it is the one most likely to be chosen for the two-axis queries a BI tool generates.
     */
    @HegelTest(testCases = 60)
    void nativeAndInMemoryAgreeOnCrossjoins(TestCase tc) {
        List<String> levels = tc.draw(MdxGenerator.twoIndependentLevels(), "levels");
        String crossjoin = "Crossjoin(" + levels.get(0) + ".Members, " + levels.get(1) + ".Members)";

        List<String> nativeResult = FoodMart.membersOf(crossjoin);
        List<String> inMemoryResult = FoodMart.withoutNativeEvaluation(() -> FoodMart.membersOf(crossjoin));

        assertEquals(inMemoryResult, nativeResult, () -> "native and in-memory disagree for " + crossjoin);
    }

    /**
     * The two paths agree for {@code NON EMPTY} axes.
     *
     * <p>{@code NON EMPTY} is the one construct where non-emptiness is genuinely requested, so both
     * paths <em>should</em> drop the same members — unlike the {@code Filter} case, where only one
     * path does. Comparing them here checks the native rewrite drops exactly the right ones rather
     * than merely dropping some.
     */
    @HegelTest(testCases = 60)
    void nativeAndInMemoryAgreeOnNonEmptyAxes(TestCase tc) {
        String level = tc.draw(MdxGenerator.populatedLevel(), "level");
        String mdx = "SELECT NON EMPTY " + level + ".Members ON COLUMNS FROM " + FoodMart.CUBE;

        List<String> nativeResult = FoodMart.membersOfQuery(mdx);
        List<String> inMemoryResult = FoodMart.withoutNativeEvaluation(() -> FoodMart.membersOfQuery(mdx));

        assertEquals(inMemoryResult, nativeResult, () -> "native and in-memory disagree for " + mdx);
    }

}
