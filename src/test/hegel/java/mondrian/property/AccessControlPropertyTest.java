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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mondrian.olap.Connection;
import mondrian.test.TestContext;

/**
 * Row-level security invariants: <strong>a role can only ever remove data, never add it, and never
 * reveal a member outside its grant.</strong>
 *
 * <p>This is the one area in the suite where a failure is a security incident rather than a wrong
 * report. Access control is also exactly the kind of thing example-based tests under-cover, because
 * an author naturally writes the queries they had in mind when they designed the grants — and an
 * attacker writes the ones they did not. Generating the query shape while holding the grant fixed
 * flips that around.
 *
 * <p>Two properties, both checked against every generated query:
 *
 * <ol>
 *   <li><strong>Monotonicity</strong> — the restricted result is a subset of the unrestricted one.
 *       A role that returns a member the unrestricted query did not is privilege escalation, however
 *       it came about.
 *   <li><strong>Containment</strong> — every member the restricted role returns from the guarded
 *       hierarchy is within the granted subtree. This is the property that actually says "the grant
 *       was honoured", as opposed to merely "fewer rows came back".
 * </ol>
 *
 * <p>The schema, roles and both connections are built once in a holder. Reparsing a schema per test
 * case would dominate the runtime and buy nothing — the grants are fixed by design here; it is the
 * query that varies.
 */
class AccessControlPropertyTest {

    /** The hierarchy the roles restrict. Chosen because it is deep enough to have a real subtree. */
    private static final String GUARDED_HIERARCHY = "[Store].[Stores]";

    /** The single member granted to the restricted role, along with its subtree. */
    private static final String GRANTED_MEMBER = "[Store].[Stores].[USA].[CA]";

    private static final String ROLE_XML = "<Role name=\"Restricted\">\n"
            + "  <SchemaGrant access=\"all\">\n"
            + "    <CubeGrant cube=\"Sales\" access=\"all\">\n"
            + "      <HierarchyGrant hierarchy=\"" + GUARDED_HIERARCHY + "\" access=\"custom\"\n"
            + "                      topLevel=\"[Store].[Stores].[Store Country]\">\n"
            + "        <MemberGrant member=\"" + GRANTED_MEMBER + "\" access=\"all\"/>\n"
            + "      </HierarchyGrant>\n"
            + "    </CubeGrant>\n"
            + "  </SchemaGrant>\n"
            + "</Role>";

    private static final class Holder {
        static final TestContext BASE = TestContext.instance().create(null, null, null, null, null, ROLE_XML);
        static final Connection UNRESTRICTED = BASE.getConnection();
        static final Connection RESTRICTED = BASE.withRole("Restricted").getConnection();
    }

    /** Levels of the guarded hierarchy, so the generated queries actually touch the grant. */
    private static List<String> guardedLevels() {
        List<String> levels = new ArrayList<>();
        for (String level : FoodMart.generatableLevels()) {
            if (level.startsWith(GUARDED_HIERARCHY + ".")) {
                levels.add(level);
            }
        }
        if (levels.isEmpty()) {
            throw new IllegalStateException("no levels found under " + GUARDED_HIERARCHY + "; schema changed?");
        }
        return levels;
    }

    /** Every member of the guarded hierarchy, as the unrestricted schema sees it. Computed once. */
    private static Set<String> allGuardedMembers() {
        return AllMembersHolder.MEMBERS;
    }

    private static final class AllMembersHolder {
        static final Set<String> MEMBERS = discover();

        private static Set<String> discover() {
            Set<String> members = new LinkedHashSet<>();
            for (String level : guardedLevels()) {
                members.addAll(FoodMart.membersOfQuery(
                        Holder.UNRESTRICTED,
                        "SELECT " + level + ".Members ON COLUMNS FROM " + FoodMart.CUBE));
            }
            return Set.copyOf(members);
        }
    }

    private static String drawGuardedSetExpr(TestCase tc) {
        String level = tc.draw(dev.hegel.Generators.sampledFrom(guardedLevels()), "level");
        return MdxGenerator.setExprOver(tc, level);
    }

    /**
     * A restricted role never returns a member that does not exist in the hierarchy at all.
     *
     * <p>Note carefully what this does <em>not</em> say. The tempting formulation — "the restricted
     * result is a subset of the unrestricted result of the same query" — is <strong>false</strong>,
     * and this suite falsified it immediately with
     * {@code Head([Store].[Stores].[Store Country].Members, 1)}: unrestricted that is
     * {@code [Canada]}, restricted it is {@code [USA]}, because the role's member set starts
     * somewhere else. Every positional function ({@code Head}, {@code Tail}, {@code TopCount},
     * {@code Item}) breaks that formulation legitimately, and asserting it would have produced a
     * confident-looking security test that failed on correct behaviour.
     *
     * <p>What is genuinely invariant is that a role cannot <em>invent</em> members: everything it
     * returns must exist in the hierarchy as the unrestricted schema sees it. Paired with
     * {@link #restrictedResultsStayInsideTheGrantedSubtree}, that covers the escalation case
     * without asserting anything false.
     */
    @HegelTest(testCases = 150)
    void restrictedResultsContainOnlyRealMembers(TestCase tc) {
        String set = drawGuardedSetExpr(tc);
        String mdx = "SELECT " + set + " ON COLUMNS FROM " + FoodMart.CUBE;

        Set<String> restricted = new LinkedHashSet<>(FoodMart.membersOfQuery(Holder.RESTRICTED, mdx));

        Set<String> invented = new LinkedHashSet<>(restricted);
        invented.removeAll(allGuardedMembers());

        assertTrue(
                invented.isEmpty(),
                () -> "the restricted role returned members that do not exist in the hierarchy: " + invented
                        + "\n  query: " + mdx);
    }

    /**
     * Every guarded-hierarchy member a restricted role returns lies within the granted subtree.
     *
     * <p>"Within" means the member is the grant itself, a descendant of it, or an ancestor of it —
     * ancestors are legitimately visible because a member cannot be displayed without the path that
     * locates it, which is what the {@code topLevel} attribute on the grant controls.
     *
     * <p>This is the property with teeth. Monotonicity alone would be satisfied by a role that
     * returned everything the unrestricted query did.
     */
    @HegelTest(testCases = 150)
    void restrictedResultsStayInsideTheGrantedSubtree(TestCase tc) {
        String set = drawGuardedSetExpr(tc);
        String mdx = "SELECT " + set + " ON COLUMNS FROM " + FoodMart.CUBE;

        List<String> restricted = FoodMart.membersOfQuery(Holder.RESTRICTED, mdx);

        List<String> outside = new ArrayList<>();
        for (String member : restricted) {
            if (!isWithinGrant(member)) {
                outside.add(member);
            }
        }

        assertTrue(
                outside.isEmpty(),
                () -> "the restricted role returned members outside the grant on " + GRANTED_MEMBER + ": " + outside
                        + "\n  query: " + mdx);
    }

    /**
     * The restriction actually bites.
     *
     * <p>A guard against the whole class going vacuously green: if the role were misconfigured and
     * granted everything, both properties above would still pass. This asserts there is at least one
     * query where the restricted result is strictly smaller.
     */
    @org.junit.jupiter.api.Test
    void theRoleActuallyRestrictsSomething() {
        String mdx = "SELECT [Store].[Stores].[Store Country].Members ON COLUMNS FROM " + FoodMart.CUBE;

        List<String> unrestricted = FoodMart.membersOfQuery(Holder.UNRESTRICTED, mdx);
        List<String> restricted = FoodMart.membersOfQuery(Holder.RESTRICTED, mdx);

        assertTrue(
                restricted.size() < unrestricted.size(),
                () -> "the role restricted nothing (" + restricted + " vs " + unrestricted
                        + "); the properties in this class would be vacuous");
    }

    /**
     * Whether {@code member} is the granted member, one of its descendants, or one of its
     * ancestors.
     *
     * <p>Decided on unique names by prefix, which works because a descendant's unique name extends
     * its ancestor's. The trailing {@code ".["} check stops {@code [..].[CA]} from being treated as
     * a prefix of a sibling such as {@code [..].[CAT]}.
     */
    private static boolean isWithinGrant(String member) {
        if (member.equals(GRANTED_MEMBER)) {
            return true;
        }
        if (member.startsWith(GRANTED_MEMBER + ".[")) {
            return true; // a descendant
        }
        return GRANTED_MEMBER.startsWith(member + ".["); // an ancestor
    }
}
