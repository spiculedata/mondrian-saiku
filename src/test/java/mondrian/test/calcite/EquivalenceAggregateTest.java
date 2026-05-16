/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.test.calcite;

import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.calcite.corpus.AggregateCorpus;
import mondrian.test.calcite.corpus.SmokeCorpus.NamedMdx;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

/**
 * Parameterized equivalence test for the tier-3 aggregate / native corpus.
 * Exact mirror of {@link EquivalenceSmokeTest} but iterating over
 * {@link AggregateCorpus#queries()}. Shares the single
 * {@link HarnessReporter} sink so the emitted HTML covers both suites when
 * both are executed (e.g. via the {@code calcite-harness} profile).
 *
 * <p>Task 11 of the Calcite Equivalence Harness plan.
 */
public class EquivalenceAggregateTest {

    private static final Path GOLDEN_DIR =
        Paths.get("src/test/resources/calcite-harness/golden");

    static Stream<Arguments> data() {
        return AggregateCorpus.queries().stream()
            .map(q -> Arguments.of(q.name, q));
    }

    @BeforeAll
    public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    @AfterAll
    public static void writeReport() throws Exception {
        HarnessReporter.writeHtml(
            Paths.get("target/calcite-harness-report.html"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void equivalent(String name, NamedMdx mdx) throws Exception {
        EquivalenceHarness h = new EquivalenceHarness(GOLDEN_DIR);
        HarnessResult r = h.run(mdx, CalcitePassThrough.class);
        HarnessReporter.record(mdx.name, r);
        assertEquals(
            "drift: " + r.failureClass + " - " + r.detail,
            FailureClass.PASS, r.failureClass);
    }
}

// End EquivalenceAggregateTest.java
