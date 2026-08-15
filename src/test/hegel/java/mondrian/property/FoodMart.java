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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import mondrian.olap.Axis;
import mondrian.olap.Cell;
import mondrian.olap.Connection;
import mondrian.olap.Cube;
import mondrian.olap.Dimension;
import mondrian.olap.Hierarchy;
import mondrian.olap.Level;
import mondrian.olap.Member;
import mondrian.olap.MondrianProperties;
import mondrian.olap.Position;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.SchemaReader;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

/**
 * A shared, lazily-built FoodMart connection plus the small query helpers the engine-level property
 * tests need.
 *
 * <p>The connection is a JVM-wide singleton on purpose. Booting Mondrian against the HSQLDB
 * FoodMart fixture costs seconds, and a property test runs its body hundreds of times; a
 * per-test-case connection would make these suites unrunnable. Sharing is safe here because every
 * property in this package only <em>reads</em> — none flushes the cache or mutates the schema.
 *
 * <p>The schema is <em>discovered</em> rather than hard-coded. Member unique names in this fork
 * have moved before ({@code [Gender].[F]} became {@code [Customer].[Gender].[F]} under the 4.x
 * schema), and a generator built from a stale hard-coded list fails as a compile-time-looking error
 * miles from the real cause. Reading the levels out of the live schema means these tests keep
 * testing the engine rather than testing my memory of the schema.
 */
final class FoodMart {

    /** The cube every property in this package queries. */
    static final String CUBE = "[Sales]";

    /** An additive measure — the aggregation properties rely on SUM being the right roll-up. */
    static final String ADDITIVE_MEASURE = "[Measures].[Unit Sales]";

    private FoodMart() {}

    private static final class Holder {
        static final Connection CONNECTION;

        static {
            FoodMartHsqldbBootstrap.ensureExtracted();
            CONNECTION = TestContext.instance().getConnection();
        }
    }

    static Connection connection() {
        return Holder.CONNECTION;
    }

    /**
     * Levels worth generating sets from: every non-{@code (All)} level of the {@code Sales} cube
     * that has at least two and at most 60 members.
     *
     * <p>Bounded at both ends deliberately. A one-member level makes properties such as "Head plus
     * Tail reconstructs the set" trivially true, so it would dilute the suite; a 100k-member level
     * would make each of several hundred test cases a full table scan. What is left is the range
     * where the set functions actually have work to do.
     */
    static List<String> generatableLevels() {
        return LevelsHolder.LEVELS;
    }

    private static final class LevelsHolder {
        static final List<String> LEVELS = discover();

        private static List<String> discover() {
            Connection c = connection();
            SchemaReader reader = c.getSchemaReader().withLocus();
            List<String> out = new ArrayList<>();
            for (Cube cube : c.getSchema().getCubes()) {
                if (!cube.getName().equals("Sales")) {
                    continue;
                }
                for (Dimension d : cube.getDimensions()) {
                    if (d.isMeasures()) {
                        continue;
                    }
                    for (Hierarchy h : d.getHierarchies()) {
                        for (Level l : h.getLevels()) {
                            if (l.isAll()) {
                                continue;
                            }
                            int size = reader.getLevelMembers(l, false).size();
                            if (size >= 2 && size <= 60) {
                                out.add(l.getUniqueName());
                            }
                        }
                    }
                }
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("no generatable levels found in the Sales cube — schema changed?");
            }
            return List.copyOf(out);
        }
    }

    /**
     * Level chains: for each hierarchy, its non-{@code (All)} levels in depth order.
     *
     * <p>Needed by the properties about {@code Descendants} and the time functions, which have to
     * name both a member and a level below it. {@link #generatableLevels()} is a flat list and
     * cannot express "this level is under that one".
     *
     * <p>Hierarchies whose deepest level is large are excluded: {@code Descendants(.., LEAVES)} over
     * a 10,000-member level materialises all of it on every test case, which would dominate the
     * suite's runtime without testing anything the smaller hierarchies do not.
     */
    static List<List<String>> levelChains() {
        return ChainHolder.CHAINS;
    }

    private static final int MAX_DEEPEST_LEVEL_SIZE = 60;

    private static final class ChainHolder {
        static final List<List<String>> CHAINS = discover();

        private static List<List<String>> discover() {
            Connection c = connection();
            SchemaReader reader = c.getSchemaReader().withLocus();
            List<List<String>> chains = new ArrayList<>();
            for (Cube cube : c.getSchema().getCubes()) {
                if (!cube.getName().equals("Sales")) {
                    continue;
                }
                for (Dimension d : cube.getDimensions()) {
                    if (d.isMeasures()) {
                        continue;
                    }
                    for (Hierarchy h : d.getHierarchies()) {
                        List<String> chain = new ArrayList<>();
                        boolean tooBig = false;
                        for (Level l : h.getLevels()) {
                            if (l.isAll()) {
                                continue;
                            }
                            if (reader.getLevelMembers(l, false).size() > MAX_DEEPEST_LEVEL_SIZE) {
                                tooBig = true;
                                break;
                            }
                            chain.add(l.getUniqueName());
                        }
                        // At least two levels, or there is no "below" for Descendants to reach.
                        if (!tooBig && chain.size() >= 2) {
                            chains.add(List.copyOf(chain));
                        }
                    }
                }
            }
            if (chains.isEmpty()) {
                throw new IllegalStateException("no multi-level hierarchies found; schema changed?");
            }
            return List.copyOf(chains);
        }
    }

    /**
     * The member unique names of one level, cached.
     *
     * <p>Cached because the member-level properties draw a member per test case, and re-running a
     * {@code .Members} query for each draw would dominate the suite's runtime. Members do not change
     * — the schema and fixture are read-only here — so one query per level is enough.
     */
    static List<String> membersOfLevel(String levelUniqueName) {
        return MEMBER_CACHE.computeIfAbsent(levelUniqueName, l -> membersOf(l + ".Members"));
    }

    private static final java.util.Map<String, List<String>> MEMBER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The subset of {@link #generatableLevels()} whose members <em>all</em> have fact data.
     *
     * <p>Exists to remove one specific confound from the differential
     * ({@code NativeEvaluationEquivalencePropertyTest}) properties. Native evaluation applies
     * non-empty semantics that the in-memory path does not — the defect recorded in
     * {@code MdxEngineMetamorphicPropertyTest.filterByConstantTrueDropsEmptyMembers} — so on a level
     * containing fact-less members the two paths differ for that reason alone, and every
     * differential property would fail on it regardless of the function under test.
     *
     * <p>Restricting to fully-populated levels removes exactly that difference and leaves everything
     * else — ordering, limits, cardinality, aggregation — genuinely under comparison. That is
     * strictly better than excluding the affected <em>functions</em>, which would have taken
     * {@code Filter} and {@code TopCount} out of the differential entirely.
     *
     * <p>Computed by comparing each level's member count against its non-empty count on the
     * in-memory path, so it tracks the data rather than a hard-coded list.
     */
    static List<String> fullyNonEmptyLevels() {
        return NonEmptyLevelsHolder.LEVELS;
    }

    private static final class NonEmptyLevelsHolder {
        static final List<String> LEVELS = discover();

        private static List<String> discover() {
            List<String> out = new ArrayList<>();
            withoutNativeEvaluation(() -> {
                for (String level : generatableLevels()) {
                    int all = membersOf(level + ".Members").size();
                    int populated = membersOf(
                                    "Filter(" + level + ".Members, NOT IsEmpty(" + ADDITIVE_MEASURE + "))")
                            .size();
                    if (all == populated && all >= 2) {
                        out.add(level);
                    }
                }
                return null;
            });
            if (out.isEmpty()) {
                throw new IllegalStateException(
                        "no fully-populated levels found; the differential properties would be vacuous");
            }
            return List.copyOf(out);
        }
    }

    /**
     * Runs {@code body} with all four native-evaluation short-cuts disabled, restoring them
     * afterwards even if the body throws.
     *
     * <p>All four have to go off together: with any one of them left on, native evaluation still
     * reaches the wrong result for {@code Filter} (see
     * {@code MdxEngineMetamorphicPropertyTest.filterByConstantTrueDropsEmptyMembers}).
     *
     * <p>These are process-global {@code MondrianProperties}, so this is only safe because the
     * {@code hegel} profile runs this package alone in its JVM.
     */
    static void withoutNativeEvaluation(Runnable body) {
        withoutNativeEvaluation(() -> {
            body.run();
            return null;
        });
    }

    /** As {@link #withoutNativeEvaluation(Runnable)}, returning the body's value. */
    static <T> T withoutNativeEvaluation(Supplier<T> body) {
        MondrianProperties props = MondrianProperties.instance();
        boolean filter = props.EnableNativeFilter.get();
        boolean crossJoin = props.EnableNativeCrossJoin.get();
        boolean nonEmpty = props.EnableNativeNonEmpty.get();
        boolean topCount = props.EnableNativeTopCount.get();
        props.EnableNativeFilter.set(false);
        props.EnableNativeCrossJoin.set(false);
        props.EnableNativeNonEmpty.set(false);
        props.EnableNativeTopCount.set(false);
        try {
            return body.get();
        } finally {
            props.EnableNativeFilter.set(filter);
            props.EnableNativeCrossJoin.set(crossJoin);
            props.EnableNativeNonEmpty.set(nonEmpty);
            props.EnableNativeTopCount.set(topCount);
        }
    }

    // ------------------------------------------------------------------
    // Query helpers
    // ------------------------------------------------------------------

    /** Executes {@code mdx} against the shared connection. */
    static Result execute(String mdx) {
        Query query = connection().parseQuery(mdx);
        return connection().execute(query);
    }

    /**
     * Returns the member unique names produced by a set expression, in order.
     *
     * <p>Order is preserved rather than sorted, because several properties here are precisely about
     * order ({@code Order} is a permutation, {@code Head}/{@code Tail} split a sequence).
     */
    static List<String> membersOf(String setExpr) {
        return membersOfQuery("SELECT " + setExpr + " ON COLUMNS FROM " + CUBE);
    }

    /**
     * As {@link #membersOf} but for a complete MDX statement, so a property can control the axis
     * modifiers ({@code NON EMPTY}), the slicer, or the cube.
     */
    static List<String> membersOfQuery(String mdx) {
        return membersOfQuery(connection(), mdx);
    }

    /** As {@link #membersOfQuery(String)} but against a specific connection (e.g. a restricted role). */
    static List<String> membersOfQuery(Connection connection, String mdx) {
        Result result = connection.execute(connection.parseQuery(mdx));
        Axis axis = result.getAxes()[0];
        List<String> out = new ArrayList<>();
        for (Position position : axis.getPositions()) {
            StringBuilder tuple = new StringBuilder();
            for (Member member : position) {
                if (tuple.length() > 0) {
                    tuple.append(" * ");
                }
                tuple.append(member.getUniqueName());
            }
            out.add(tuple.toString());
        }
        return out;
    }

    /**
     * The cell at the intersection of {@code memberExpr} and the additive measure.
     *
     * <p>Returned as the concrete {@code RolapCell} because the drill-through API
     * ({@code canDrillThrough}, {@code getDrillThroughCount}) lives there rather than on the
     * {@link Cell} interface.
     */
    static mondrian.rolap.RolapCell cellFor(String memberExpr) {
        Result result = execute("SELECT {" + ADDITIVE_MEASURE + "} ON COLUMNS, {" + memberExpr + "} ON ROWS FROM "
                + CUBE);
        return (mondrian.rolap.RolapCell) result.getCell(new int[] {0, 0});
    }

    /**
     * Every cell of {@code setExpr} against the additive measure, from a <em>single</em> query.
     *
     * <p>Exists purely for cost. Calling {@link #cellFor} once per member issues one full MDX
     * statement each, and the drill-through properties need a member and all its children — six
     * statements where one will do. Putting the whole set on the row axis and reading the cells off
     * it is the same information for a sixth of the work.
     */
    static List<mondrian.rolap.RolapCell> cellsFor(String setExpr) {
        Result result = execute("SELECT {" + ADDITIVE_MEASURE + "} ON COLUMNS, " + setExpr + " ON ROWS FROM " + CUBE);
        int rows = result.getAxes()[1].getPositions().size();
        List<mondrian.rolap.RolapCell> cells = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            cells.add((mondrian.rolap.RolapCell) result.getCell(new int[] {0, i}));
        }
        return cells;
    }

    /**
     * Evaluates a scalar MDX expression, returning {@code null} for an empty cell.
     *
     * <p>Wrapped in a calculated member rather than read off a cell coordinate so that any
     * expression — an aggregate, an arithmetic combination, a {@code Count} — can be evaluated the
     * same way.
     */
    static Double scalar(String expr) {
        Result result = execute(
                "WITH MEMBER [Measures].[__probe] AS " + expr + " SELECT {[Measures].[__probe]} ON COLUMNS FROM "
                        + CUBE);
        Cell cell = result.getCell(new int[] {0});
        if (cell.isNull()) {
            return null;
        }
        Object value = cell.getValue();
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }
}
