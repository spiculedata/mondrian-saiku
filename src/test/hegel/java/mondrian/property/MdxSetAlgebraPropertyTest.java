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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The laws of set algebra, checked against {@code Union}, {@code Intersect} and {@code Except}.
 *
 * <p>These three functions are the backbone of every non-trivial MDX query a BI tool emits, and
 * they obey the ordinary algebra of sets. That algebra is a free, exhaustive specification: each law
 * relates two queries whose results must match for <em>every</em> input, so it can be checked
 * against generated operands without anyone deciding what the right answer is.
 *
 * <p><strong>Order versus membership.</strong> MDX set operators preserve order, so
 * {@code Union(a, b)} and {@code Union(b, a)} legitimately differ in sequence. Commutativity is
 * therefore asserted on membership, and the tests that care about order say so. Getting this
 * distinction wrong is the classic way to write a set-algebra test that fails for a reason that
 * is not a bug.
 *
 * <p>Operands come from {@link MdxGenerator#sameLevelPair()} so both sides share a hierarchy —
 * MDX requires matching dimensionality, and mismatched operands would test the validator rather
 * than the algebra.
 */
class MdxSetAlgebraPropertyTest {

    // ------------------------------------------------------------------
    // Commutativity and associativity
    // ------------------------------------------------------------------

    /** {@code Union} is commutative up to order. */
    @HegelTest(testCases = 80)
    void unionIsCommutative(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String a = operands.get(1);
        String b = operands.get(2);

        assertEquals(
                asSet(FoodMart.membersOf("Union(" + a + ", " + b + ")")),
                asSet(FoodMart.membersOf("Union(" + b + ", " + a + ")")),
                () -> "Union is not commutative for\n  a = " + a + "\n  b = " + b);
    }

    /** {@code Intersect} is commutative up to order. */
    @HegelTest(testCases = 80)
    void intersectIsCommutative(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String a = operands.get(1);
        String b = operands.get(2);

        assertEquals(
                asSet(FoodMart.membersOf("Intersect(" + a + ", " + b + ")")),
                asSet(FoodMart.membersOf("Intersect(" + b + ", " + a + ")")),
                () -> "Intersect is not commutative for\n  a = " + a + "\n  b = " + b);
    }

    /**
     * {@code Union} is associative, in order as well as membership.
     *
     * <p>Order is asserted here because both groupings visit the operands left to right, so the
     * sequences genuinely must match — a difference would mean the deduplication is dropping the
     * wrong occurrence.
     */
    @HegelTest(testCases = 80)
    void unionIsAssociative(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String level = operands.get(0);
        String a = operands.get(1);
        String b = operands.get(2);
        String c = level + ".Members";

        assertEquals(
                FoodMart.membersOf("Union(Union(" + a + ", " + b + "), " + c + ")"),
                FoodMart.membersOf("Union(" + a + ", Union(" + b + ", " + c + "))"),
                () -> "Union is not associative for\n  a = " + a + "\n  b = " + b);
    }

    // ------------------------------------------------------------------
    // Identities and annihilators
    // ------------------------------------------------------------------

    /** The empty set is an identity for {@code Union} and for {@code Except}, and kills {@code Intersect}. */
    @HegelTest(testCases = 80)
    void emptySetActsAsIdentityAndAnnihilator(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String level = operands.get(0);
        String a = operands.get(1);
        String empty = MdxGenerator.emptySetOver(level);

        List<String> expected = FoodMart.membersOf(a);

        assertEquals(expected, FoodMart.membersOf("Union(" + a + ", " + empty + ")"), () -> "Union with the empty set changed " + a);
        assertEquals(expected, FoodMart.membersOf("Except(" + a + ", " + empty + ")"), () -> "Except the empty set changed " + a);
        assertEquals(
                List.of(),
                FoodMart.membersOf("Intersect(" + a + ", " + empty + ")"),
                () -> "Intersect with the empty set was not empty for " + a);
    }

    // ------------------------------------------------------------------
    // Relations between the operators
    // ------------------------------------------------------------------

    /**
     * {@code Except(a, b)} shares no member with {@code b}.
     *
     * <p>The defining property of set difference, and the one that catches a comparison using the
     * wrong notion of member identity — unique name versus object identity, say.
     */
    @HegelTest(testCases = 80)
    void exceptRemovesEveryMemberOfTheSecondOperand(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String a = operands.get(1);
        String b = operands.get(2);

        Set<String> difference = asSet(FoodMart.membersOf("Except(" + a + ", " + b + ")"));
        Set<String> right = asSet(FoodMart.membersOf(b));

        Set<String> overlap = new LinkedHashSet<>(difference);
        overlap.retainAll(right);
        assertTrue(overlap.isEmpty(), () -> "Except(a, b) still contains " + overlap + " for\n  a = " + a + "\n  b = " + b);
    }

    /** Absorption: {@code Intersect(a, Union(a, b))} is {@code a}. */
    @HegelTest(testCases = 80)
    void intersectAbsorbsUnion(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String a = operands.get(1);
        String b = operands.get(2);

        assertEquals(
                asSet(FoodMart.membersOf(a)),
                asSet(FoodMart.membersOf("Intersect(" + a + ", Union(" + a + ", " + b + "))")),
                () -> "absorption failed for\n  a = " + a + "\n  b = " + b);
    }

    /** Partition: {@code Union(Except(a, b), Intersect(a, b))} reconstructs {@code a}. */
    @HegelTest(testCases = 80)
    void exceptAndIntersectPartitionTheFirstOperand(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String a = operands.get(1);
        String b = operands.get(2);

        assertEquals(
                asSet(FoodMart.membersOf(a)),
                asSet(FoodMart.membersOf("Union(Except(" + a + ", " + b + "), Intersect(" + a + ", " + b + "))")),
                () -> "Except and Intersect do not partition a for\n  a = " + a + "\n  b = " + b);
    }

    /**
     * De Morgan, relative to the level's full member set:
     * {@code U \ (a ∪ b) == (U \ a) ∩ (U \ b)}.
     *
     * <p>The most demanding law here — it exercises all three operators in one query and fails if
     * any of them disagrees with the others about membership.
     */
    @HegelTest(testCases = 80)
    void deMorgansLawHolds(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String universe = operands.get(0) + ".Members";
        String a = operands.get(1);
        String b = operands.get(2);

        String left = "Except(" + universe + ", Union(" + a + ", " + b + "))";
        String right = "Intersect(Except(" + universe + ", " + a + "), Except(" + universe + ", " + b + "))";

        assertEquals(
                asSet(FoodMart.membersOf(left)),
                asSet(FoodMart.membersOf(right)),
                () -> "De Morgan failed for\n  a = " + a + "\n  b = " + b);
    }

    /**
     * Inclusion-exclusion on cardinality:
     * {@code |a| + |b| == |a ∪ b| + |a ∩ b|}.
     *
     * <p>A numeric law rather than a membership one, so it catches a {@code Union} that
     * deduplicates too eagerly or an {@code Intersect} that double-counts — errors that a
     * set-equality assertion cannot see.
     */
    @HegelTest(testCases = 80)
    void cardinalityObeysInclusionExclusion(TestCase tc) {
        List<String> operands = tc.draw(MdxGenerator.sameLevelPair(), "operands");
        String a = operands.get(1);
        String b = operands.get(2);

        int sizeA = FoodMart.membersOf(a).size();
        int sizeB = FoodMart.membersOf(b).size();
        int union = FoodMart.membersOf("Union(" + a + ", " + b + ")").size();
        int intersection = FoodMart.membersOf("Intersect(" + a + ", " + b + ")").size();

        assertEquals(
                sizeA + sizeB,
                union + intersection,
                () -> "|a| + |b| = " + (sizeA + sizeB) + " but |a∪b| + |a∩b| = " + (union + intersection)
                        + " for\n  a = " + a + "\n  b = " + b);
    }

    /** Membership as an insertion-ordered set, for the laws that are about content rather than order. */
    private static Set<String> asSet(List<String> members) {
        return new LinkedHashSet<>(new ArrayList<>(members));
    }
}
