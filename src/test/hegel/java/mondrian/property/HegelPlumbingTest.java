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

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Hegel;
import dev.hegel.HegelTest;
import dev.hegel.Settings;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Guards the Hegel wiring itself, not Mondrian.
 *
 * <p>Every other class in this package is only as trustworthy as the engine underneath it, and a
 * property-based test that silently degrades is worse than no test: a suite whose native library
 * failed to load, or whose shrinker is inert, still reports 100% green. These three tests fail
 * loudly in exactly those cases, so a green run of this class is what licenses you to believe the
 * rest of the package.
 *
 * <p>Runs only under {@code mvn test -Phegel} on a Java 22+ JVM — see the {@code hegel} profile in
 * {@code pom.xml} for why the suite is opt-in.
 */
class HegelPlumbingTest {

    /**
     * The native engine loads and generation happens at all. If libhegel is missing for this
     * OS/arch, or {@code --enable-native-access} is misconfigured, this fails at {@code draw}.
     */
    @HegelTest(testCases = 50)
    void engineGeneratesValues(TestCase tc) {
        List<Integer> xs = tc.draw(lists(integers()), "xs");
        assertEquals(xs.size(), new ArrayList<>(xs).size());
    }

    /**
     * Generation is actually varied rather than returning one constant forever. A stuck engine
     * would make every property in this package vacuously true, so we assert the engine explores:
     * across 200 cases drawing a 0..1000 integer, at least 10 distinct values must appear.
     */
    @Test
    void engineExploresTheInputSpace() {
        var seen = new java.util.HashSet<Integer>();
        Hegel.test(tc -> seen.add(tc.draw(integers().min(0).max(1000))), new Settings().testCases(200));
        assertTrue(
                seen.size() >= 10,
                "engine produced only " + seen.size() + " distinct values in 200 cases: " + seen
                        + " — generation looks stuck, every property in this package would be vacuous");
    }

    /**
     * The shrinker reduces a failure to a minimal counterexample.
     *
     * <p>The property "no generated string contains 'x'" is false, and the minimal witness is the
     * one-character string {@code "x"}. If shrinking were disabled or broken we would still see a
     * failure here — but a sprawling random one — so the assertion is on the <em>size</em> of the
     * reported example, which is the whole reason to prefer Hegel over a plain random loop.
     */
    @Test
    void shrinkerMinimisesCounterexamples() {
        AtomicReference<String> smallestFailure = new AtomicReference<>(null);
        try {
            Hegel.test(
                    tc -> {
                        String s = tc.draw(text().includeCharacters("x"), "s");
                        if (s.contains("x")) {
                            smallestFailure.set(s);
                            throw new AssertionError("found an x: " + s);
                        }
                    },
                    new Settings().testCases(500));
        } catch (AssertionError expected) {
            // The engine is expected to falsify this property.
        }

        String minimal = smallestFailure.get();
        assertTrue(minimal != null, "engine never falsified an obviously-false property");
        assertEquals(
                "x",
                minimal,
                "shrinker did not reduce to the minimal counterexample; got " + quote(minimal)
                        + ". Counterexamples from this package will be hard to read.");
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\" (length " + s.length() + ")";
    }
}
