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

import dev.hegel.HealthCheck;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import mondrian.rolap.RolapCell;

/**
 * Two properties about what a user can verify for themselves: the rows behind a number, and
 * getting the same answer twice under load.
 *
 * <p><strong>Drill-through</strong> is the feature that lets someone check the engine's arithmetic
 * by looking at the underlying fact rows. If the aggregate says one thing and the rows say another,
 * the number loses its credibility even when the number is right — so consistency between them is a
 * property worth pinning directly.
 *
 * <p><strong>Concurrency</strong> matters because Mondrian's caches are shared across threads by
 * design. A race in segment loading does not usually throw; it returns a partially-populated segment,
 * which is a wrong number that appears only under load and never in a single-threaded test.
 */
class DrillThroughAndConcurrencyPropertyTest {

    // ------------------------------------------------------------------
    // Drill-through
    // ------------------------------------------------------------------

    /**
     * Members with a small, non-empty set of children, precomputed once.
     *
     * <p>Drawing a member at random and then rejecting it with {@code assume} made generation
     * filter-bound: Hegel's {@code TooSlow} health check fired after producing only 8 valid inputs
     * in 32 seconds. Most members are leaves, so most draws were wasted. Selecting from a
     * pre-filtered pool instead makes every draw usable, which is both faster and a better use of
     * the case budget.
     */
    private static List<String> membersWithFewChildren() {
        return EligibleHolder.MEMBERS;
    }

    private static final int MEMBERS_SAMPLED_PER_LEVEL = 6;

    /**
     * Members at any depth that support drill-through, precomputed.
     *
     * <p>Unlike {@link #membersWithFewChildren()} this includes leaves, because a leaf cell is
     * exactly where "does the count agree with emptiness" is most interesting.
     */
    private static List<String> drillableMembers() {
        return DrillableHolder.MEMBERS;
    }

    private static final class DrillableHolder {
        static final List<String> MEMBERS = discover();

        private static List<String> discover() {
            List<String> out = new ArrayList<>();
            for (List<String> chain : FoodMart.levelChains()) {
                for (String level : chain) {
                    List<String> candidates = FoodMart.membersOfLevel(level);
                    int limit = Math.min(candidates.size(), MEMBERS_SAMPLED_PER_LEVEL);
                    for (String member : candidates.subList(0, limit)) {
                        if (isKnownSecondaryHierarchyDefect(member)) {
                            continue;
                        }
                        RolapCell cell = FoodMart.cellFor(member);
                        if (cell.canDrillThrough() && cell.getDrillThroughCount() >= 0) {
                            out.add(member);
                        }
                    }
                }
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("no drillable members found; the property would be vacuous");
            }
            return List.copyOf(out);
        }
    }

    private static final class EligibleHolder {
        static final List<String> MEMBERS = discover();

        private static List<String> discover() {
            List<String> out = new ArrayList<>();
            for (List<String> chain : FoodMart.levelChains()) {
                // Only non-leaf levels can have children at all.
                for (int depth = 0; depth < chain.size() - 1; depth++) {
                    List<String> candidates = FoodMart.membersOfLevel(chain.get(depth));
                    // Sample a bounded number per level rather than every member: building this
                    // pool costs one Children query each, and walking whole levels made class
                    // initialisation the single most expensive thing in the suite. A handful per
                    // level across every hierarchy is ample diversity for 30 test cases.
                    int limit = Math.min(candidates.size(), MEMBERS_SAMPLED_PER_LEVEL);
                    for (String member : candidates.subList(0, limit)) {
                        if (isKnownSecondaryHierarchyDefect(member)) {
                            continue;
                        }
                        int children = FoodMart.membersOf(member + ".Children").size();
                        if (children < 1 || children > 5) {
                            continue;
                        }
                        // Drill-through capability is checked HERE, once, rather than with an
                        // assume in the test body. Each rejection in the body costs a full MDX
                        // query plus a drill-through COUNT(*), and on CI hardware that was enough
                        // to trip Hegel's TooSlow health check (6 valid inputs in 30s) even though
                        // the same code was comfortable locally. Rejections belong in the pool.
                        RolapCell cell = FoodMart.cellFor(member);
                        if (cell.canDrillThrough() && cell.getDrillThroughCount() >= 0) {
                            out.add(member);
                        }
                    }
                }
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("no members with a small child set; the property would be vacuous");
            }
            return List.copyOf(out);
        }
    }

    /**
     * A cell's drill-through row count is the sum of its children's.
     *
     * <p>Metamorphic, so it needs no oracle: every fact row under a parent is under exactly one
     * child, whatever the data happens to be. It catches a drill-through predicate that is too
     * broad (rows counted twice) or too narrow (rows dropped) without anyone knowing the true count.
     */
    // Deliberately few cases: each issues a drill-through COUNT(*) per cell, and those joins
    // against an 86k-row fact table cost ~2s apiece. Measured: 30 cases took 58s locally.
    //
    // TooSlow is suppressed, not worked around: each case issues drill-through COUNT(*) queries
    // with joins against an 86k-row fact table, so cases are genuinely slow and always will be.
    // The health check exists to catch generation that is *accidentally* slow (filter-bound); the
    // rejections that caused that here have been moved into the pool above. What remains is the
    // irreducible cost of the thing being tested.
    @HegelTest(testCases = 12, suppressHealthCheck = HealthCheck.TOO_SLOW)
    void drillThroughCountIsAdditiveOverChildren(TestCase tc) {
        // Every member in this pool is already known to have 1..5 children, to sit outside the
        // characterised secondary-hierarchy defect, and to support drill-through — so there is
        // nothing left to reject in the body, and no rejection costs a SQL round trip.
        String member = tc.draw(dev.hegel.Generators.sampledFrom(membersWithFewChildren()), "member");

        RolapCell parentCell = FoodMart.cellFor(member);
        int parentCount = parentCell.getDrillThroughCount();

        // One query for every child, rather than one per child.
        List<RolapCell> childCells = FoodMart.cellsFor(member + ".Children");

        int childTotal = 0;
        for (RolapCell childCell : childCells) {
            if (!childCell.canDrillThrough()) {
                return; // cannot make the comparison for this member
            }
            int childCount = childCell.getDrillThroughCount();
            if (childCount < 0) {
                return;
            }
            childTotal += childCount;
        }

        final int total = childTotal;
        assertEquals(
                parentCount,
                total,
                () -> "drill-through count for " + member + " was " + parentCount
                        + " but its " + childCells.size() + " children total " + total);
    }

    /**
     * A cell has drill-through rows exactly when it has a value.
     *
     * <p>The two come from different places — the aggregate from the segment cache, the count from a
     * fresh {@code SELECT COUNT(*)} against the fact table — so agreeing about emptiness is a real
     * cross-check. A non-empty cell with zero rows behind it means the aggregate was computed from
     * data the drill-through cannot find, which is the shape of a stale-cache bug.
     */
    // As above, and measured more expensive still: 40 cases took 139s locally, the single slowest
    // property in the suite. Deep members produce drill-through SQL with many joins.
    @HegelTest(testCases = 15, suppressHealthCheck = HealthCheck.TOO_SLOW)
    void drillThroughCountAgreesWithCellEmptiness(TestCase tc) {
        // Pre-filtered pool, same reasoning as above: no in-body rejection, so no rejection costs
        // an MDX query plus a drill-through COUNT(*).
        String member = tc.draw(dev.hegel.Generators.sampledFrom(drillableMembers()), "member");

        RolapCell cell = FoodMart.cellFor(member);
        int count = cell.getDrillThroughCount();

        boolean hasValue = !cell.isNull();
        assertEquals(
                hasValue,
                count > 0,
                () -> "cell for " + member + " has value=" + hasValue + " but drill-through count=" + count);
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    /**
     * Running the same query on many threads gives the answer a single thread gives.
     *
     * <p>The cache is flushed of nothing and warmed by nothing beforehand on purpose: the threads
     * race to populate the same segments, which is exactly the window a segment-loading race lives
     * in. Every thread must still agree with the serial result.
     */
    @HegelTest(testCases = 15)
    void concurrentExecutionAgreesWithSerialExecution(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        int threads = tc.draw(dev.hegel.Generators.integers().min(2).max(4), "threads");

        List<String> serial = FoodMart.membersOf(set);

        List<List<String>> results = inParallel(threads, () -> FoodMart.membersOf(set));

        for (int i = 0; i < results.size(); i++) {
            final int index = i;
            assertEquals(
                    serial,
                    results.get(i),
                    () -> "thread " + index + " of " + threads + " disagreed with the serial result for S = " + set);
        }
    }

    /**
     * The same holds for aggregates, which take the segment-cache path rather than the member path.
     *
     * <p>This is the one that would actually catch a half-loaded segment: member lists can be served
     * without touching a cell, whereas a {@code Sum} has to load fact data, and that load is what
     * several threads would be racing on.
     */
    @HegelTest(testCases = 15)
    void concurrentAggregatesAgreeWithSerialAggregates(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExpr(), "set");
        int threads = tc.draw(dev.hegel.Generators.integers().min(2).max(4), "threads");
        String expr = "Sum(" + set + ", " + FoodMart.ADDITIVE_MEASURE + ")";

        Double serial = FoodMart.scalar(expr);

        List<Double> results = inParallel(threads, () -> FoodMart.scalar(expr));

        for (int i = 0; i < results.size(); i++) {
            final int index = i;
            assertEquals(
                    serial,
                    results.get(i),
                    () -> "thread " + index + " of " + threads + " computed a different sum for S = " + set);
        }
    }

    /** Runs {@code task} on {@code threads} threads and returns every result, propagating failures. */
    private static <T> List<T> inParallel(int threads, Callable<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(120, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError("a concurrent execution failed: " + e, e);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
            try {
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "thread pool did not shut down");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Whether {@code member} sits in the hierarchy affected by the confirmed drill-through defect
     * pinned by {@link #drillThroughOnAnEmptyWeeklyCellReturnsTheWholeFactTable}.
     *
     * <p>Deliberately narrow — one hierarchy, named — so every other hierarchy stays under test.
     * Delete this when the defect is fixed.
     */
    private static boolean isKnownSecondaryHierarchyDefect(String member) {
        return member.startsWith("[Time].[Weekly]");
    }

    /**
     * Characterisation test for a CONFIRMED DEFECT found by this suite:
     * <strong>drill-through on an empty cell of a secondary hierarchy returns the entire fact
     * table.</strong> Tracked as issue #138.
     *
     * <p>{@code [Time].[Weekly].[1998]} has no value — FoodMart's fact data is 1997 — yet its
     * drill-through count is <strong>86,837</strong>, which is exactly the row count of
     * {@code sales_fact_1997}. Every row in the table, returned as "the rows behind" a blank cell.
     *
     * <p>Established:
     *
     * <ul>
     *   <li>86,837 is verified against the fixture: {@code SELECT COUNT(*) FROM "sales_fact_1997"}.
     *   <li>It is not "empty cells always over-count". The parallel case on the <em>primary</em>
     *       time hierarchy, {@code [Time].[Time].[1998]}, correctly returns 0 — as does
     *       {@code [Store].[Stores].[Canada]}, also empty.
     *   <li>The same Weekly hierarchy returns the correct 86,837 for {@code [1997]}, so the count
     *       itself works; it is the 1998 constraint that is dropped rather than made unsatisfiable.
     * </ul>
     *
     * <p>{@code [Time].[Weekly]} is a second hierarchy on the Time dimension, which is what
     * distinguishes it from the working cases. Severity: a user drilling into a blank cell is shown
     * the whole fact table instead of nothing — wrong data presented as the evidence behind a
     * number, which is precisely the trust drill-through exists to provide.
     *
     * <p>Left unfixed: the fix is in the drill-through constraint construction for non-primary
     * hierarchies, and needs an owner who can judge the effect on the SQL generated for legitimate
     * secondary-hierarchy drill-throughs.
     */
    @org.junit.jupiter.api.Test
    void drillThroughOnAnEmptyWeeklyCellReturnsTheWholeFactTable() {
        RolapCell empty = FoodMart.cellFor("[Time].[Weekly].[1998]");
        assertTrue(empty.isNull(), "precondition: the 1998 weekly cell is empty");
        assertEquals(
                86837,
                empty.getDrillThroughCount(),
                "witness changed - drill-through on a secondary hierarchy may have been fixed; if so, "
                        + "delete this test and the isKnownSecondaryHierarchyDefect guard");

        RolapCell control = FoodMart.cellFor("[Time].[Time].[1998]");
        assertTrue(control.isNull(), "precondition: the 1998 cell is empty on the primary hierarchy too");
        assertEquals(
                0,
                control.getDrillThroughCount(),
                "the primary time hierarchy is expected to handle the same situation correctly");
    }
}
