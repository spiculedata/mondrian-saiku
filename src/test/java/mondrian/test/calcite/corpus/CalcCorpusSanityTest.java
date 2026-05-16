/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.test.calcite.corpus;

import mondrian.olap.Result;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Sanity test for {@link CalcCorpus}. Mirror of
 * {@link AggregateCorpusSanityTest}: every MDX must execute cleanly under
 * the HSQLDB FoodMart fixture with the legacy evaluator. If this goes red
 * the Calcite equivalence harness has nothing meaningful to compare
 * against.
 */
public class CalcCorpusSanityTest {

    static {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    static Stream<Arguments> data() {
        return CalcCorpus.queries().stream()
            .map(q -> Arguments.of(q.name, q.mdx));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void executes(String name, String mdx) {
        Result r = TestContext.instance().executeQuery(mdx);
        assertNotNull("query " + name + " returned null result", r);
        assertNotNull("query " + name + " returned null axes", r.getAxes());
    }

    @Test
    public void corpusSizeWithinBudget() {
        int n = CalcCorpus.queries().size();
        assertTrue("calc corpus must have >=6 entries, got " + n,
            n >= 6);
        assertTrue("calc corpus must have <=12 entries, got " + n,
            n <= 12);
    }

    @Test
    public void namesAreUniqueAndHyphenated() {
        Set<String> seen = new HashSet<>();
        for (SmokeCorpus.NamedMdx q : CalcCorpus.queries()) {
            assertTrue("duplicate name: " + q.name, seen.add(q.name));
            assertTrue(
                "name not lowercase-with-hyphens: " + q.name,
                q.name.matches("[a-z][a-z0-9-]*"));
        }
    }

    @Test
    public void namesDoNotCollideWithOtherCorpora() {
        Set<String> other = new HashSet<>();
        for (SmokeCorpus.NamedMdx q : SmokeCorpus.queries()) {
            other.add(q.name);
        }
        for (SmokeCorpus.NamedMdx q : AggregateCorpus.queries()) {
            other.add(q.name);
        }
        for (SmokeCorpus.NamedMdx q : CalcCorpus.queries()) {
            assertEquals(
                "name collides with smoke/aggregate corpus: " + q.name,
                false, other.contains(q.name));
        }
    }
}

// End CalcCorpusSanityTest.java
