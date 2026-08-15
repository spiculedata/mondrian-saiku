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

import dev.hegel.Generator;
import dev.hegel.TestCase;
import java.util.List;

/**
 * Generates MDX set expressions over the live FoodMart schema.
 *
 * <p>A grammar, not a string fuzzer. Random characters would be rejected by the parser almost
 * every time and would never reach the query engine, so the interesting properties — about
 * evaluation, not about syntax rejection — would never be exercised. Building syntactically valid,
 * schema-valid MDX by construction means every generated case gets all the way through parse,
 * validate, plan and execute.
 *
 * <p>Everything here is built on {@link #setExprOver(TestCase, String)}, which roots an expression
 * at one named level. That shape exists because MDX's binary set operators — {@code Union},
 * {@code Intersect}, {@code Except} — require their operands to have the same dimensionality.
 * Generating two independent expressions and combining them would produce a validator error most
 * of the time, so the set-algebra properties would spend their budget testing that MDX rejects
 * malformed queries instead of testing the algebra.
 *
 * <p>Depth is bounded because MDX set functions compose multiplicatively: a depth-4
 * {@code Crossjoin} of two 30-member levels is 810,000 tuples and would take the suite from seconds
 * to hours.
 */
public final class MdxGenerator {

    private MdxGenerator() {}

    /** Maximum nesting depth of a generated set expression. */
    private static final int MAX_DEPTH = 3;

    /** A level's members: the base case, e.g. {@code [Time].[Time].[Quarter].Members}. */
    static Generator<String> levelMembers() {
        return dev.hegel.Generators.sampledFrom(FoodMart.generatableLevels()).map(level -> level + ".Members");
    }

    /** One level unique name, e.g. {@code [Time].[Time].[Quarter]}. */
    static Generator<String> level() {
        return dev.hegel.Generators.sampledFrom(FoodMart.generatableLevels());
    }

    /**
     * A set expression rooted anywhere in the schema.
     *
     * <p>Drawn through {@link dev.hegel.Generators#composite} so the recursion is driven by the
     * engine's own choices and therefore shrinks properly — a failing deep expression reduces
     * towards a bare {@code .Members}, which is what makes a counterexample diagnosable.
     */
    public static Generator<String> setExpr() {
        return dev.hegel.Generators.composite(tc -> build(tc, tc.draw(level()), MAX_DEPTH));
    }

    /**
     * A set expression rooted at a level whose members all have fact data.
     *
     * <p>For the differential properties — see {@link FoodMart#fullyNonEmptyLevels()} for why the
     * restriction is on the <em>level</em> rather than on the function.
     */
    static Generator<String> setExprOverPopulatedLevel() {
        return dev.hegel.Generators.composite(tc -> build(
                tc, tc.draw(dev.hegel.Generators.sampledFrom(FoodMart.fullyNonEmptyLevels())), MAX_DEPTH));
    }

    /** One level unique name, restricted to fully-populated levels. */
    static Generator<String> populatedLevel() {
        return dev.hegel.Generators.sampledFrom(FoodMart.fullyNonEmptyLevels());
    }

    /** A set expression whose members all come from {@code levelUniqueName}. */
    static String setExprOver(TestCase tc, String levelUniqueName) {
        return build(tc, levelUniqueName, MAX_DEPTH);
    }

    /**
     * Two set expressions over the same level, so binary set operators are well formed.
     *
     * <p>Returned as a three-element list — level, left, right — because the properties that use it
     * usually need the level too (to build the full member set, or an empty set of the right
     * dimensionality).
     */
    static Generator<List<String>> sameLevelPair() {
        return dev.hegel.Generators.composite(tc -> {
            String level = tc.draw(level());
            return List.of(level, build(tc, level, MAX_DEPTH), build(tc, level, MAX_DEPTH));
        });
    }

    private static String build(TestCase tc, String level, int depth) {
        String base = level + ".Members";
        if (depth <= 0) {
            return base;
        }
        // Weighted towards the base case so expressions stay small unless the engine is
        // deliberately exploring; an unweighted choice makes almost every draw maximally deep.
        int choice = tc.draw(dev.hegel.Generators.integers().min(0).max(11));
        switch (choice) {
            case 0:
            case 1:
            case 2:
                return base;
            case 3:
                return "Head(" + build(tc, level, depth - 1) + ", " + tc.draw(smallCount()) + ")";
            case 4:
                return "Tail(" + build(tc, level, depth - 1) + ", " + tc.draw(smallCount()) + ")";
            case 5:
                return "Order(" + build(tc, level, depth - 1) + ", " + FoodMart.ADDITIVE_MEASURE + ", "
                        + tc.draw(dev.hegel.Generators.sampledFrom("ASC", "DESC", "BASC", "BDESC")) + ")";
            case 6:
                return "Filter(" + build(tc, level, depth - 1) + ", " + FoodMart.ADDITIVE_MEASURE + " > "
                        + tc.draw(dev.hegel.Generators.integers().min(0).max(20000)) + ")";
            case 7:
                return "TopCount(" + build(tc, level, depth - 1) + ", " + tc.draw(nonZeroCount()) + ", "
                        + FoodMart.ADDITIVE_MEASURE + ")";
            case 8:
                return "BottomCount(" + build(tc, level, depth - 1) + ", " + tc.draw(nonZeroCount()) + ", "
                        + FoodMart.ADDITIVE_MEASURE + ")";
            case 9: {
                String inner = build(tc, level, depth - 1);
                // Hierarchize over anything derived from TopCount/BottomCount throws
                // UnsupportedOperationException for most values of n -- a characterised defect with
                // its own pinning test in MdxEngineMetamorphicPropertyTest.
                // Remove this guard when that defect is fixed.
                return containsRanked(inner) ? inner : "Hierarchize(" + inner + ")";
            }
            case 10:
                return "Distinct(" + build(tc, level, depth - 1) + ")";
            default:
                // Binary operators need both operands over the same level, which is exactly what
                // threading `level` through this recursion buys.
                return tc.draw(dev.hegel.Generators.sampledFrom("Union", "Intersect", "Except")) + "("
                        + build(tc, level, depth - 1) + ", " + build(tc, level, depth - 1) + ")";
        }
    }

    /**
     * A single member, as a two-element list of {@code [level, memberUniqueName]}.
     *
     * <p>The level comes back with the member because the navigation properties need it — asserting
     * anything about {@code Ancestor} or {@code Descendants} means naming the level to navigate to.
     */
    static Generator<List<String>> memberWithLevel() {
        return dev.hegel.Generators.composite(tc -> {
            String level = tc.draw(level());
            List<String> members = FoodMart.membersOfLevel(level);
            int index = tc.draw(dev.hegel.Generators.integers().min(0).max(members.size() - 1));
            return List.of(level, members.get(index));
        });
    }

    /**
     * An empty set of the same dimensionality as {@code level}.
     *
     * <p>A bare {@code {}} carries no hierarchy, so MDX cannot type-check it against a typed operand
     * in {@code Union}/{@code Except}. {@code Head(.., 0)} is the idiomatic typed empty set — and
     * {@code Head} handles a zero count correctly, unlike {@code TopCount}.
     */
    static String emptySetOver(String level) {
        return "Head(" + level + ".Members, 0)";
    }

    /**
     * A set expression safe to wrap in {@code Hierarchize}.
     *
     * <p>The generator's own {@code Hierarchize} case already avoids ranked arguments, but a
     * property that wraps {@code Hierarchize} around a generated expression <em>from the outside</em>
     * needs the same guarantee — otherwise it re-triggers the characterised
     * {@code Hierarchize(TopCount(..))} crash and fails for a reason already recorded. Use this
     * wherever a test applies {@code Hierarchize} itself.
     */
    static Generator<String> hierarchizableSetExpr() {
        return setExpr().filter(expr -> !containsRanked(expr));
    }

    /**
     * Whether {@code expr} involves a ranking function anywhere.
     *
     * <p>Anywhere, not just outermost: the read-only list {@code TopCount}/{@code BottomCount}
     * returns propagates through the set functions layered on top of it, because {@code Head} and
     * {@code Tail} hand back views rather than copies. So {@code Hierarchize(Tail(TopCount(..), 2))}
     * crashes just as {@code Hierarchize(TopCount(..))} does, and a guard that only inspected the
     * outermost call would let it through — as an earlier version of this method did, until the
     * generator found that exact shape.
     */
    private static boolean containsRanked(String expr) {
        return expr.contains("TopCount(") || expr.contains("BottomCount(");
    }

    /** Counts for {@code Head}/{@code Tail}, where zero is legal and correctly handled. */
    private static Generator<Integer> smallCount() {
        return dev.hegel.Generators.integers().min(0).max(10);
    }

    /**
     * Counts for {@code TopCount}/{@code BottomCount}, excluding zero.
     *
     * <p>{@code TopCount(S, 0, M)} is a characterised defect — native evaluation treats a count of
     * zero as "no limit" and returns every member instead of none. It has its own pinning test in
     * {@code MdxEngineMetamorphicPropertyTest}, so leaving it in the generator would only make every
     * property that draws a {@code TopCount} fail for that one already-recorded reason. Restore the
     * zero here when that defect is fixed.
     */
    private static Generator<Integer> nonZeroCount() {
        return dev.hegel.Generators.integers().min(1).max(10);
    }

    /** Two levels from different hierarchies, so a {@code Crossjoin} of them is well formed. */
    static Generator<List<String>> twoIndependentLevels() {
        return dev.hegel.Generators.lists(dev.hegel.Generators.sampledFrom(FoodMart.generatableLevels()))
                .minSize(2)
                .maxSize(2)
                .filter(ls -> !hierarchyOf(ls.get(0)).equals(hierarchyOf(ls.get(1))));
    }

    /**
     * The hierarchy part of a level unique name — everything up to the last bracketed segment.
     *
     * <p>Crossjoining two levels of the <em>same</em> hierarchy is not an error worth generating:
     * MDX forbids repeating a hierarchy across the tuple, so such a case would test the validator's
     * rejection path rather than the evaluation path these properties are about.
     */
    static String hierarchyOf(String levelUniqueName) {
        int lastSegment = levelUniqueName.lastIndexOf(".[");
        return lastSegment < 0 ? levelUniqueName : levelUniqueName.substring(0, lastSegment);
    }
}
