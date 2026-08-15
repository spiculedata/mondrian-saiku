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
import java.util.ArrayList;
import java.util.List;

/**
 * Cache-consistency properties: <strong>an answer must not depend on what was asked before it.</strong>
 *
 * <p>Mondrian caches aggressively — segments of cell values, native set-evaluation results, member
 * lists — and every cache is a map from a hand-built key to a result. A key that omits something the
 * result actually depends on does not fail loudly: it silently serves one query's answer to a
 * different query. That is the worst failure mode in the engine, because the wrong number is
 * perfectly plausible and there is nothing in the output to suggest anything happened.
 *
 * <p>It is also invisible to example-based testing, which runs each query in isolation and therefore
 * never populates the cache with the neighbouring entry that would collide. These properties
 * deliberately run <em>several different</em> queries and then re-ask them, which is the only way to
 * reach that state.
 *
 * <p>Both properties reduce to the same invariant, stated without needing to know any right answer:
 * asking the same question twice must give the same answer, whatever was asked in between.
 */
class SegmentCacheConsistencyPropertyTest {

    /**
     * Re-asking a query after asking others returns the same answer.
     *
     * <p>The distinct queries in between are the point: they populate neighbouring cache entries,
     * so a key that fails to distinguish them will hand back the wrong one on the second pass. The
     * second pass runs in reverse order, so the query most likely to have been evicted or overwritten
     * is re-asked first.
     */
    @HegelTest(testCases = 100)
    void repeatedQueriesReturnTheSameMembers(TestCase tc) {
        List<String> queries = drawDistinctQueries(tc);

        List<List<String>> firstPass = new ArrayList<>();
        for (String query : queries) {
            firstPass.add(FoodMart.membersOf(query));
        }

        for (int i = queries.size() - 1; i >= 0; i--) {
            final int index = i;
            assertEquals(
                    firstPass.get(i),
                    FoodMart.membersOf(queries.get(i)),
                    () -> "re-asking query " + index + " gave a different answer after "
                            + (queries.size() - 1) + " other queries\n  query: " + queries.get(index)
                            + "\n  all queries: " + queries);
        }
    }

    /**
     * The same, for aggregate values rather than member lists.
     *
     * <p>This is the one that exercises the <em>segment</em> cache specifically — member lists can
     * be served from the schema reader without touching a cell at all, whereas a {@code Sum} has to
     * load or reuse a segment of fact data.
     */
    @HegelTest(testCases = 100)
    void repeatedAggregatesReturnTheSameValue(TestCase tc) {
        List<String> queries = drawDistinctQueries(tc);

        List<Double> firstPass = new ArrayList<>();
        for (String query : queries) {
            firstPass.add(FoodMart.scalar("Sum(" + query + ", " + FoodMart.ADDITIVE_MEASURE + ")"));
        }

        for (int i = queries.size() - 1; i >= 0; i--) {
            final int index = i;
            assertEquals(
                    firstPass.get(i),
                    FoodMart.scalar("Sum(" + queries.get(i) + ", " + FoodMart.ADDITIVE_MEASURE + ")"),
                    () -> "re-asking the sum of query " + index + " gave a different value\n  query: "
                            + queries.get(index) + "\n  all queries: " + queries);
        }
    }

    /**
     * A query's answer does not change when the same query is asked through the other evaluation
     * path in between.
     *
     * <p>Native and in-memory evaluation share caches. If one populates an entry the other then
     * reuses under an equal key without the key recording which path produced it, the second reader
     * gets a result computed under different semantics — which, given the characterised
     * native/in-memory differences, would be a wrong answer rather than merely a stale one.
     */
    @HegelTest(testCases = 80)
    void answersSurviveAnInterveningEvaluationOnTheOtherPath(TestCase tc) {
        String set = tc.draw(MdxGenerator.setExprOverPopulatedLevel(), "set");

        List<String> before = FoodMart.membersOf(set);
        FoodMart.withoutNativeEvaluation(() -> FoodMart.membersOf(set));
        List<String> after = FoodMart.membersOf(set);

        assertEquals(
                before,
                after,
                () -> "the native answer changed after the same query ran on the in-memory path\n  query: " + set);
    }

    /**
     * Two to four distinct set expressions over the same level.
     *
     * <p>Same level so their cache entries are as close together as possible — different levels
     * would produce obviously distinct keys and would not stress the distinction that matters.
     */
    private static List<String> drawDistinctQueries(TestCase tc) {
        String level = tc.draw(MdxGenerator.level(), "level");
        int count = tc.draw(dev.hegel.Generators.integers().min(2).max(4), "count");

        List<String> queries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String expr = MdxGenerator.setExprOver(tc, level);
            if (!queries.contains(expr)) {
                queries.add(expr);
            }
        }
        tc.assume(queries.size() >= 2);
        return queries;
    }
}
