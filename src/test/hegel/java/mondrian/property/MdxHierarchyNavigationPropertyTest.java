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
import java.util.List;

/**
 * Navigation laws for the member tree: {@code Parent}, {@code Children}, {@code Ancestor},
 * {@code Descendants}, {@code Lag}/{@code Lead} and {@code PrevMember}/{@code NextMember}.
 *
 * <p>Drill-down and drill-up are the two interactions every OLAP client is built around, and they
 * are implemented by these functions. Each law below is an inverse relationship — go down then up,
 * step forward then back — which makes them checkable against any member in any hierarchy without
 * an oracle.
 *
 * <p>Members are drawn from the live schema by {@link MdxGenerator#memberWithLevel()}, so the
 * properties cover ragged and multi-level hierarchies as they actually exist in FoodMart rather
 * than a tidy subset someone chose.
 */
class MdxHierarchyNavigationPropertyTest {

    // ------------------------------------------------------------------
    // Parent / Children
    // ------------------------------------------------------------------

    /**
     * Every child of a member reports that member as its parent.
     *
     * <p>Drill down one level, then ask each result to drill back up: the round trip must return
     * where it started. A hierarchy that fails this shows a user rows that do not belong under the
     * heading they were expanded from.
     */
    @HegelTest(testCases = 120)
    void childrenReportTheirParent(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String member = memberAndLevel.get(1);

        List<String> children = FoodMart.membersOf(member + ".Children");
        tc.assume(!children.isEmpty());

        // Bounded to the first few children: this costs one query each, and a member with
        // hundreds of children would turn a single test case into hundreds of round trips.
        // The generator supplies the breadth across members instead.
        for (String child : children.subList(0, Math.min(3, children.size()))) {
            assertEquals(
                    List.of(member),
                    FoodMart.membersOf("{" + child + ".Parent}"),
                    () -> "child " + child + " does not report " + member + " as its parent");
        }
    }

    /** A member with a parent appears among that parent's children. */
    @HegelTest(testCases = 120)
    void everyMemberAppearsAmongItsParentsChildren(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String member = memberAndLevel.get(1);

        List<String> parent = FoodMart.membersOf("{" + member + ".Parent}");
        tc.assume(!parent.isEmpty());

        List<String> siblings = FoodMart.membersOf(member + ".Parent.Children");
        assertTrue(
                siblings.contains(member),
                () -> member + " is missing from its own parent's children " + siblings);
    }

    // ------------------------------------------------------------------
    // Ancestor / Descendants
    // ------------------------------------------------------------------

    /** {@code Ancestor(m, m.Level)} is {@code m} itself. */
    @HegelTest(testCases = 120)
    void ancestorAtOwnLevelIsTheMemberItself(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String level = memberAndLevel.get(0);
        String member = memberAndLevel.get(1);

        assertEquals(
                List.of(member),
                FoodMart.membersOf("{Ancestor(" + member + ", " + level + ")}"),
                () -> "Ancestor(m, m.Level) was not m for " + member);
    }

    /** {@code Descendants(m, m.Level)} is exactly {@code m}. */
    @HegelTest(testCases = 120)
    void descendantsAtOwnLevelIsTheMemberItself(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String level = memberAndLevel.get(0);
        String member = memberAndLevel.get(1);

        assertEquals(
                List.of(member),
                FoodMart.membersOf("Descendants(" + member + ", " + level + ")"),
                () -> "Descendants(m, m.Level) was not {m} for " + member);
    }

    /**
     * Every descendant at the child level is a child.
     *
     * <p>{@code Descendants} and {@code Children} reach the next level down by different routes,
     * so making them agree is a genuine cross-check rather than a restatement.
     */
    @HegelTest(testCases = 100)
    void descendantsOneLevelDownAreTheChildren(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String member = memberAndLevel.get(1);

        List<String> children = FoodMart.membersOf(member + ".Children");
        tc.assume(!children.isEmpty());

        // The child level is whatever level the children are on; ask MDX rather than deriving it.
        String childLevel = member + ".Children.Item(0).Level";
        List<String> descendants = FoodMart.membersOf("Descendants(" + member + ", " + childLevel + ")");

        assertEquals(children, descendants, () -> "Descendants to the child level differed from Children for " + member);
    }

    // ------------------------------------------------------------------
    // Lag / Lead / PrevMember / NextMember
    // ------------------------------------------------------------------

    /**
     * {@code Lag} and {@code Lead} are inverses: {@code m.Lead(n).Lag(n)} is {@code m}.
     *
     * <p>Stepping along a level and back is the operation behind every period-over-period
     * calculation, so an off-by-one here silently compares the wrong two periods.
     */
    @HegelTest(testCases = 120)
    void lagAndLeadAreInverse(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String member = memberAndLevel.get(1);
        int n = tc.draw(dev.hegel.Generators.integers().min(-5).max(5), "n");

        // Only meaningful where the intermediate step stays inside the level; otherwise Lead
        // returns the null member and stepping back from nothing is legitimately nothing.
        List<String> stepped = FoodMart.membersOf("{" + member + ".Lead(" + n + ")}");
        tc.assume(!stepped.isEmpty());

        assertEquals(
                List.of(member),
                FoodMart.membersOf("{" + member + ".Lead(" + n + ").Lag(" + n + ")}"),
                () -> "Lead(" + n + ") then Lag(" + n + ") did not return " + member);
    }

    /** {@code Lead(1)} is {@code NextMember}, and {@code Lag(1)} is {@code PrevMember}. */
    @HegelTest(testCases = 120)
    void leadOneAgreesWithNextMember(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String member = memberAndLevel.get(1);

        assertEquals(
                FoodMart.membersOf("{" + member + ".NextMember}"),
                FoodMart.membersOf("{" + member + ".Lead(1)}"),
                () -> "Lead(1) differed from NextMember for " + member);
        assertEquals(
                FoodMart.membersOf("{" + member + ".PrevMember}"),
                FoodMart.membersOf("{" + member + ".Lag(1)}"),
                () -> "Lag(1) differed from PrevMember for " + member);
    }

    /** {@code Lag(0)} and {@code Lead(0)} are the identity. */
    @HegelTest(testCases = 80)
    void steppingByZeroIsTheIdentity(TestCase tc) {
        List<String> memberAndLevel = tc.draw(MdxGenerator.memberWithLevel(), "member");
        String member = memberAndLevel.get(1);

        assertEquals(List.of(member), FoodMart.membersOf("{" + member + ".Lag(0)}"), () -> "Lag(0) moved " + member);
        assertEquals(List.of(member), FoodMart.membersOf("{" + member + ".Lead(0)}"), () -> "Lead(0) moved " + member);
    }

    // ------------------------------------------------------------------
    // Hierarchize
    // ------------------------------------------------------------------

    /**
     * {@code Hierarchize} is idempotent.
     *
     * <p>Sorting an already-sorted set must be a no-op. This is the standard check that a
     * comparator defines a genuine total order — an inconsistent one reorders on every pass.
     */
    @HegelTest(testCases = 100)
    void hierarchizeIsIdempotent(TestCase tc) {
        String set = tc.draw(MdxGenerator.hierarchizableSetExpr(), "set");

        List<String> once = FoodMart.membersOf("Hierarchize(" + set + ")");
        List<String> twice = FoodMart.membersOf("Hierarchize(Hierarchize(" + set + "))");

        assertEquals(once, twice, () -> "Hierarchize is not idempotent for S = " + set);
    }

    /** {@code Hierarchize} is a permutation: it reorders without adding or dropping members. */
    @HegelTest(testCases = 100)
    void hierarchizeIsAPermutation(TestCase tc) {
        String set = tc.draw(MdxGenerator.hierarchizableSetExpr(), "set");

        List<String> before = FoodMart.membersOf(set);
        List<String> after = FoodMart.membersOf("Hierarchize(" + set + ")");

        assertEquals(before.size(), after.size(), () -> "Hierarchize changed the member count for S = " + set);
        assertEquals(
                before.stream().sorted().toList(),
                after.stream().sorted().toList(),
                () -> "Hierarchize changed which members are present for S = " + set);
    }
}
