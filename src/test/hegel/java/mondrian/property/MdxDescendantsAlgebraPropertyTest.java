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
 * The algebra of {@code Descendants}'s eight flags.
 *
 * <p>{@code DescendantsFunDef.Flag} is defined by three independent booleans plus a special case:
 *
 * <pre>{@code   SELF              (self)
 *   AFTER             (after)
 *   BEFORE            (before)
 *   BEFORE_AND_AFTER  (before, after)
 *   SELF_AND_AFTER    (self, after)
 *   SELF_AND_BEFORE   (self, before)
 *   SELF_BEFORE_AFTER (self, before, after)
 *   LEAVES            (leaves)}</pre>
 *
 * <p>That decomposition <em>is</em> the specification, and it is complete: every compound flag must
 * be exactly the union of its parts, and the three parts must be pairwise disjoint. So the whole
 * function can be pinned by generated relations without writing down a single expected member list —
 * which matters, because {@code Descendants} is the function behind every drill-down and its
 * expected output for an arbitrary member and level is not something anyone can state by hand.
 *
 * <p>Members and levels come from {@link FoodMart#levelChains()}, so a member is always paired with
 * a level that genuinely sits above, at, or below it in the same hierarchy.
 */
class MdxDescendantsAlgebraPropertyTest {

    /** A member drawn from one level of a hierarchy, together with a target level from the same chain. */
    private record Target(String member, String level) {}

    private static Target drawTarget(TestCase tc) {
        List<List<String>> chains = FoodMart.levelChains();
        List<String> chain = tc.draw(dev.hegel.Generators.sampledFrom(chains), "chain");

        int memberDepth = tc.draw(dev.hegel.Generators.integers().min(0).max(chain.size() - 1), "memberDepth");
        int levelDepth = tc.draw(dev.hegel.Generators.integers().min(0).max(chain.size() - 1), "levelDepth");

        List<String> members = FoodMart.membersOfLevel(chain.get(memberDepth));
        int index = tc.draw(dev.hegel.Generators.integers().min(0).max(members.size() - 1), "memberIndex");

        return new Target(members.get(index), chain.get(levelDepth));
    }

    private static Set<String> descendants(Target target, String flag) {
        return new LinkedHashSet<>(FoodMart.membersOf(
                "Descendants(" + target.member() + ", " + target.level() + ", " + flag + ")"));
    }

    // ------------------------------------------------------------------
    // Compound flags are unions of their parts
    // ------------------------------------------------------------------

    /** {@code SELF_AND_AFTER == SELF ∪ AFTER}. */
    @HegelTest(testCases = 60)
    void selfAndAfterIsTheUnionOfSelfAndAfter(TestCase tc) {
        Target target = drawTarget(tc);
        assertEquals(
                union(descendants(target, "SELF"), descendants(target, "AFTER")),
                descendants(target, "SELF_AND_AFTER"),
                () -> describe(target, "SELF_AND_AFTER != SELF u AFTER"));
    }

    /** {@code SELF_AND_BEFORE == SELF ∪ BEFORE}. */
    @HegelTest(testCases = 60)
    void selfAndBeforeIsTheUnionOfSelfAndBefore(TestCase tc) {
        Target target = drawTarget(tc);
        assertEquals(
                union(descendants(target, "SELF"), descendants(target, "BEFORE")),
                descendants(target, "SELF_AND_BEFORE"),
                () -> describe(target, "SELF_AND_BEFORE != SELF u BEFORE"));
    }

    /** {@code BEFORE_AND_AFTER == BEFORE ∪ AFTER}. */
    @HegelTest(testCases = 60)
    void beforeAndAfterIsTheUnionOfBeforeAndAfter(TestCase tc) {
        Target target = drawTarget(tc);
        assertEquals(
                union(descendants(target, "BEFORE"), descendants(target, "AFTER")),
                descendants(target, "BEFORE_AND_AFTER"),
                () -> describe(target, "BEFORE_AND_AFTER != BEFORE u AFTER"));
    }

    /** {@code SELF_BEFORE_AFTER == SELF ∪ BEFORE ∪ AFTER}. */
    @HegelTest(testCases = 60)
    void selfBeforeAfterIsTheUnionOfAllThree(TestCase tc) {
        Target target = drawTarget(tc);
        Set<String> expected = union(
                descendants(target, "SELF"), union(descendants(target, "BEFORE"), descendants(target, "AFTER")));
        assertEquals(
                expected,
                descendants(target, "SELF_BEFORE_AFTER"),
                () -> describe(target, "SELF_BEFORE_AFTER != SELF u BEFORE u AFTER"));
    }

    // ------------------------------------------------------------------
    // The three parts are pairwise disjoint
    // ------------------------------------------------------------------

    /**
     * {@code SELF}, {@code BEFORE} and {@code AFTER} share no member.
     *
     * <p>Disjointness is the other half of the decomposition. Without it the union laws above could
     * be satisfied by flags that overlap — which would mean a drill-down showing the same row twice.
     */
    @HegelTest(testCases = 60)
    void selfBeforeAndAfterArePairwiseDisjoint(TestCase tc) {
        Target target = drawTarget(tc);

        Set<String> self = descendants(target, "SELF");
        Set<String> before = descendants(target, "BEFORE");
        Set<String> after = descendants(target, "AFTER");

        assertDisjoint(self, before, target, "SELF", "BEFORE");
        assertDisjoint(self, after, target, "SELF", "AFTER");
        assertDisjoint(before, after, target, "BEFORE", "AFTER");
    }

    // ------------------------------------------------------------------
    // LEAVES
    // ------------------------------------------------------------------

    /**
     * {@code LEAVES} is contained in {@code SELF_BEFORE_AFTER}.
     *
     * <p>A leaf is still a descendant, so the leaf set cannot contain anything the full set does
     * not. Stated as containment rather than equality because a leaf below the named level is
     * legitimately included by {@code LEAVES} and excluded by the level-relative flags.
     */
    @HegelTest(testCases = 60)
    void leavesAreContainedInTheFullDescendantSet(TestCase tc) {
        Target target = drawTarget(tc);

        Set<String> leaves = descendants(target, "LEAVES");
        Set<String> all = descendants(target, "SELF_BEFORE_AFTER");

        Set<String> extra = new LinkedHashSet<>(leaves);
        extra.removeAll(all);
        assertTrue(extra.isEmpty(), () -> describe(target, "LEAVES contained members outside SELF_BEFORE_AFTER: " + extra));
    }

    /**
     * {@code SELF} at a member's own level is the member itself.
     *
     * <p>The anchor case: it ties the flag algebra to a concrete, checkable value, so the laws above
     * cannot all be satisfied vacuously by a function that returns nothing.
     */
    @HegelTest(testCases = 60)
    void selfAtTheMembersOwnLevelIsTheMember(TestCase tc) {
        List<List<String>> chains = FoodMart.levelChains();
        List<String> chain = tc.draw(dev.hegel.Generators.sampledFrom(chains), "chain");
        int depth = tc.draw(dev.hegel.Generators.integers().min(0).max(chain.size() - 1), "depth");
        List<String> members = FoodMart.membersOfLevel(chain.get(depth));
        int index = tc.draw(dev.hegel.Generators.integers().min(0).max(members.size() - 1), "memberIndex");

        Target target = new Target(members.get(index), chain.get(depth));

        assertEquals(
                Set.of(target.member()),
                descendants(target, "SELF"),
                () -> describe(target, "Descendants(m, m.Level, SELF) was not {m}"));
    }

    /**
     * The whole set is non-empty for at least some inputs.
     *
     * <p>A guard against the class passing vacuously: every law above is trivially true if
     * {@code Descendants} always returned the empty set.
     */
    @org.junit.jupiter.api.Test
    void descendantsReturnsSomethingForATypicalDrillDown() {
        List<String> chain = FoodMart.levelChains().get(0);
        String topMember = FoodMart.membersOfLevel(chain.get(0)).get(0);
        Target target = new Target(topMember, chain.get(chain.size() - 1));

        assertTrue(
                descendants(target, "SELF_BEFORE_AFTER").size() > 1,
                () -> describe(target, "a typical drill-down returned nothing; the laws would be vacuous"));
    }

    // ------------------------------------------------------------------

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static void assertDisjoint(Set<String> a, Set<String> b, Target target, String nameA, String nameB) {
        Set<String> overlap = new LinkedHashSet<>(a);
        overlap.retainAll(b);
        assertTrue(
                overlap.isEmpty(),
                () -> describe(target, nameA + " and " + nameB + " both contain " + new ArrayList<>(overlap)));
    }

    private static String describe(Target target, String message) {
        return message + "\n  member: " + target.member() + "\n  level:  " + target.level();
    }
}
