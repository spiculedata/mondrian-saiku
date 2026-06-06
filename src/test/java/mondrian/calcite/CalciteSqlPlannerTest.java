/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.calcite;

import mondrian.test.FoodMartHsqldbBootstrap;

import org.apache.calcite.config.NullCollation;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.HsqldbSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.junit.jupiter.api.BeforeAll;import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertFalse;import static org.junit.Assert.assertNotEquals;import static org.junit.Assert.assertNotNull;import static org.junit.Assert.assertTrue;
/**
 * Tests for {@link CalciteSqlPlanner}: PlannerRequest -> dialect-rendered SQL.
 */
public class CalciteSqlPlannerTest {

    @BeforeAll
    public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    private static CalciteSqlPlanner plannerFor(SqlDialect dialect) {
        CalciteMondrianSchema schema =
            new CalciteMondrianSchema(
                FoodMartHsqldbBootstrap.dataSource(), "foodmart");
        return new CalciteSqlPlanner(schema, dialect);
    }

    @Test
    public void simpleScanEmitsSelect() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addProjection(new PlannerRequest.Column(null, "unit_sales"))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue("expected SELECT in: " + sql, lower.contains("select"));
        assertTrue("expected unit_sales in: " + sql,
            lower.contains("unit_sales"));
        assertTrue("expected sales_fact_1997 in: " + sql,
            lower.contains("sales_fact_1997"));
    }

    @Test
    public void groupedAggregateEmitsGroupBy() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addGroupBy(new PlannerRequest.Column("time_by_day", "the_year"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                "unit_sales"))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue("expected GROUP BY in: " + sql, lower.contains("group by"));
        assertTrue("expected the_year in: " + sql, lower.contains("the_year"));
        assertTrue("expected SUM in: " + sql, lower.contains("sum("));
    }

    /** #104: a median (percentile 0.5) measure emits
     *  PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY col). */
    @Test
    public void medianEmitsPercentileContWithinGroup() {
        // PERCENTILE_CONT is emitted for a supporting dialect (Postgres).
        CalciteSqlPlanner planner = plannerFor(PostgresqlSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addGroupBy(new PlannerRequest.Column("time_by_day", "the_year"))
            .addMeasure(PlannerRequest.Measure.percentile(
                "med_sales",
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                0.5))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue("expected PERCENTILE_CONT in: " + sql,
            lower.contains("percentile_cont("));
        assertTrue("expected the fraction 0.5 in: " + sql,
            lower.contains("0.5"));
        assertTrue("expected WITHIN GROUP in: " + sql,
            lower.contains("within group"));
        assertTrue("expected ORDER BY unit_sales in: " + sql,
            lower.contains("order by") && lower.contains("unit_sales"));
    }

    /** #104: percentile(0.9) renders the requested fraction. */
    @Test
    public void percentile90EmitsFraction() {
        CalciteSqlPlanner planner = plannerFor(PostgresqlSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addGroupBy(
                new PlannerRequest.Column("sales_fact_1997", "store_id"))
            .addMeasure(PlannerRequest.Measure.percentile(
                "p90_sales",
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                0.9))
            .build();
        String sql = planner.plan(req).toLowerCase();
        assertTrue("expected PERCENTILE_CONT(0.9) in: " + sql,
            sql.contains("percentile_cont(") && sql.contains("0.9"));
    }

    /** #104: a percentile measure on a backend without PERCENTILE_CONT
     *  (HSQLDB) is REFUSED with a clear error, not silently mis-emitted. */
    /** #108: a multi-branch tier projection renders a nested CASE over the
     *  source column, in boundary order. */
    @Test
    public void tierProjectionEmitsNestedCase() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest.Column source =
            new PlannerRequest.Column("sales_fact_1997", "unit_sales");
        PlannerRequest.TierExpr tier =
            new PlannerRequest.TierExpr(
                source,
                java.util.List.of(
                    new PlannerRequest.TierBranch(10, "Small"),
                    new PlannerRequest.TierBranch(100, "Medium"),
                    new PlannerRequest.TierBranch(null, "Large")));
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addProjection(
                new PlannerRequest.Column(null, "size_tier", tier))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue("expected CASE in: " + sql, lower.contains("case when"));
        assertTrue("expected first boundary 10 in: " + sql,
            lower.contains("< 10"));
        assertTrue("expected second boundary 100 in: " + sql,
            lower.contains("< 100"));
        assertTrue("expected Small label in: " + sql,
            sql.contains("'Small'"));
        assertTrue("expected Medium label in: " + sql,
            sql.contains("'Medium'"));
        assertTrue("expected Large (else) label in: " + sql,
            sql.contains("'Large'"));
        // Boundary order: the Small branch must precede the Medium branch.
        assertTrue("expected Small before Medium in: " + sql,
            sql.indexOf("'Small'") < sql.indexOf("'Medium'"));
    }

    /** #108: a duration projection renders a TIMESTAMPDIFF over the two
     *  date columns, carrying the unit. */
    @Test
    public void durationProjectionEmitsTimestampDiff() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest.DurationExpr dur =
            new PlannerRequest.DurationExpr(
                new PlannerRequest.Column("time_by_day", "the_date"),
                new PlannerRequest.Column("time_by_day", "the_date"),
                PlannerRequest.DurationUnit.DAY);
        PlannerRequest req = PlannerRequest.builder("time_by_day")
            .addProjection(
                new PlannerRequest.Column(null, "lead_days", dur))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue("expected TIMESTAMPDIFF in: " + sql,
            lower.contains("timestampdiff"));
        assertTrue("expected DAY unit in: " + sql,
            lower.contains("day"));
    }

    @Test
    public void percentileRefusedOnUnsupportedDialect() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addGroupBy(
                new PlannerRequest.Column("sales_fact_1997", "store_id"))
            .addMeasure(PlannerRequest.Measure.percentile(
                "med",
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                0.5))
            .build();
        try {
            planner.plan(req);
            org.junit.jupiter.api.Assertions.fail(
                "expected REFUSE on HSQLDB");
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            assertTrue("expected a clear percentile message: " + e.getMessage(),
                msg.contains("percentile_cont") || msg.contains("percentile"));
        }
    }

    /**
     * #104 edge: a percentile measure is REFUSED on MySQL — an explicitly
     * non-allowlisted dialect — with a clear error, not silently mis-emitted.
     * Parameterized over the non-allowlisted dialects so the allowlist policy
     * (refuse-unless-known-supported) is pinned for more than just HSQLDB.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("unsupportedPercentileDialects")
    public void percentileRefusedOnMySqlExplicitly(SqlDialect dialect) {
        CalciteSqlPlanner planner = plannerFor(dialect);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addGroupBy(
                new PlannerRequest.Column("sales_fact_1997", "store_id"))
            .addMeasure(PlannerRequest.Measure.percentile(
                "med",
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                0.5))
            .build();
        try {
            planner.plan(req);
            org.junit.jupiter.api.Assertions.fail(
                "expected REFUSE on " + dialect.getClass().getSimpleName());
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            assertTrue("expected a clear percentile message: " + e.getMessage(),
                msg.contains("percentile_cont") || msg.contains("percentile"));
        }
    }

    static java.util.stream.Stream<SqlDialect> unsupportedPercentileDialects() {
        return java.util.stream.Stream.of(
            org.apache.calcite.sql.dialect.MysqlSqlDialect.DEFAULT,
            HsqldbSqlDialect.DEFAULT);
    }

    /** A non-percentile (plain SUM) query is unaffected by the refuse on a
     *  non-supporting dialect. */
    @Test
    public void plainAggregateAllowedOnAnyDialect() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addGroupBy(
                new PlannerRequest.Column("sales_fact_1997", "store_id"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                "us"))
            .build();
        assertNotNull(planner.plan(req));
    }

    @Test
    public void distinctProjectionEmitsSelectDistinct() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("product_class")
            .addProjection(
                new PlannerRequest.Column(null, "product_family"))
            .addOrderBy(new PlannerRequest.OrderBy(
                new PlannerRequest.Column("product_class", "product_family"),
                PlannerRequest.Order.ASC))
            .distinct(true)
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue(
            "expected SELECT DISTINCT (or equivalent) in: " + sql,
            lower.contains("distinct") || lower.contains("group by"));
        assertTrue("expected product_family in: " + sql,
            lower.contains("product_family"));
        assertTrue("expected ORDER BY in: " + sql,
            lower.contains("order by"));
    }

    @Test public void distinctWithAggregationRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> PlannerRequest.builder("sales_fact_1997")
                .addGroupBy(new PlannerRequest.Column(null, "time_id"))
                .addMeasure(new PlannerRequest.Measure(
                    PlannerRequest.AggFn.SUM,
                    new PlannerRequest.Column(null, "unit_sales"),
                    "m"))
                .distinct(true)
                .build());
    }

    @Test
    public void inListEmitsOrChain() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addFilter(new PlannerRequest.Filter(
                new PlannerRequest.Column("time_by_day", "the_year"),
                PlannerRequest.Operator.IN,
                java.util.Arrays.<Object>asList(1997, 1998, 1999)))
            .addGroupBy(new PlannerRequest.Column("time_by_day", "the_year"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                "m"))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        assertTrue("expected 1997 in: " + sql, sql.contains("1997"));
        assertTrue("expected 1998 in: " + sql, sql.contains("1998"));
        assertTrue("expected 1999 in: " + sql, sql.contains("1999"));
        String lower = sql.toLowerCase();
        assertTrue(
            "expected OR (or IN) chain in: " + sql,
            lower.contains(" or ") || lower.contains(" in "));
    }

    @Test
    public void falseFilterEmitsFalseLiteral() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addProjection(new PlannerRequest.Column(null, "unit_sales"))
            .universalFalse(true)
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue(
            "expected FALSE literal in WHERE: " + sql,
            lower.contains("false") || lower.contains("1 = 0")
                || lower.contains("1=0"));
    }

    @Test
    public void distinctCountMeasureEmitsCountDistinct() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addGroupBy(
                new PlannerRequest.Column("sales_fact_1997", "time_id"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.COUNT,
                new PlannerRequest.Column("sales_fact_1997", "customer_id"),
                "dc",
                true))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase().replaceAll("\\s+", " ");
        assertTrue(
            "expected COUNT(DISTINCT ...) in: " + sql,
            lower.contains("count(distinct"));
        assertTrue("expected customer_id in: " + sql,
            lower.contains("customer_id"));
    }

    @Test
    public void planRequestWithCrossJoinEmitsCrossJoinSql() {
        // Multi-target tuple-read (Task H) emits a CROSS JOIN between two
        // dim tables. Verify the planner translates Join.cross(...) into
        // an unparsed CROSS JOIN (or, at minimum, a join with no equi-key
        // predicate — some dialects render this as "INNER JOIN ... ON TRUE"
        // but HSQLDB keeps the CROSS form).
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("product_class")
            .addJoin(PlannerRequest.Join.cross("time_by_day"))
            .addProjection(
                new PlannerRequest.Column("product_class", "product_family"))
            .addProjection(
                new PlannerRequest.Column("time_by_day", "the_year"))
            .distinct(true)
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase().replaceAll("\\s+", " ");
        // HSQLDB dialect via Calcite may render an unconditional INNER
        // JOIN on TRUE as a CROSS JOIN, an "INNER JOIN ... ON TRUE", or
        // as the comma-separated FROM form (implicit cross product).
        // Any of those three is a valid cross-join rendering.
        boolean cross = lower.contains("cross join");
        boolean innerOnTrue =
            lower.contains("inner join") && lower.contains("true");
        // comma-separated FROM: "from product_class , time_by_day"
        boolean commaFrom =
            lower.matches(".*from\\s+\\w+\\s*,\\s*\\w+.*");
        assertTrue(
            "expected CROSS JOIN / INNER JOIN ON TRUE / comma-FROM in: "
                + sql,
            cross || innerOnTrue || commaFrom);
        assertTrue("expected product_class in: " + sql,
            lower.contains("product_class"));
        assertTrue("expected time_by_day in: " + sql,
            lower.contains("time_by_day"));
    }

    @Test
    public void snowflakeMultiHopEmitsChainedJoins() {
        // Task I: PlannerRequest carries a two-edge snowflake chain:
        //   sales_fact_1997 -> product (product_id)
        //                   -> product_class (product_class_id)
        // The second Join's leftTable=product identifies the already-
        // joined LHS so the renderer resolves product.product_class_id
        // unambiguously.
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "product", "product_id", "product_id"))
            .addJoin(PlannerRequest.Join.chained(
                "product", "product_class_id",
                "product_class", "product_class_id"))
            .addGroupBy(
                new PlannerRequest.Column(
                    "product_class", "product_family"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                "m"))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        // Both hops must appear in the FROM/JOIN structure.
        assertTrue("expected product in: " + sql,
            lower.contains("product"));
        assertTrue("expected product_class in: " + sql,
            lower.contains("product_class"));
        assertTrue("expected product_family in: " + sql,
            lower.contains("product_family"));
        assertTrue("expected sales_fact_1997 in: " + sql,
            lower.contains("sales_fact_1997"));
        // There must be at least two INNER JOINs (or one JOIN + a comma-
        // from in degenerate dialects). For HSQLDB via Calcite, expect
        // the explicit JOIN keyword to appear twice.
        int joinCount = 0;
        int idx = 0;
        while ((idx = lower.indexOf(" join ", idx)) >= 0) {
            joinCount++;
            idx += 5;
        }
        assertTrue("expected >=2 JOIN keywords in: " + sql, joinCount >= 2);
    }

    @Test
    public void dialectAwareness() {
        // Baseline HSQLDB dialect uses double-quoted identifiers; build a
        // custom-context variant using backtick identifier quoting so the
        // dialect parameter visibly affects the rendered SQL even on the
        // simple, non-keyword identifiers used by this corpus query.
        SqlDialect backtickDialect = new SqlDialect(
            HsqldbSqlDialect.DEFAULT_CONTEXT
                .withIdentifierQuoteString("`")
                .withNullCollation(NullCollation.HIGH)) {};
        CalciteSqlPlanner hsql = plannerFor(HsqldbSqlDialect.DEFAULT);
        CalciteSqlPlanner alt = plannerFor(backtickDialect);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addGroupBy(new PlannerRequest.Column("time_by_day", "the_year"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                "unit_sales"))
            .addOrderBy(new PlannerRequest.OrderBy(
                new PlannerRequest.Column("time_by_day", "the_year"),
                PlannerRequest.Order.ASC))
            .build();
        String hsqlSql = hsql.plan(req);
        String altSql = alt.plan(req);
        assertNotNull(hsqlSql);
        assertNotNull(altSql);
        assertNotEquals(
            "expected dialect parameter to affect emitted SQL; identical:\n"
                + hsqlSql,
            hsqlSql, altSql);
    }

    /** Task M: TupleFilter renders as an OR of ANDs across columns. */
    @Test
    public void tupleFilterEmitsOrOfAnds() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addJoin(new PlannerRequest.Join(
                "customer", "customer_id", "customer_id"))
            .addTupleFilter(new PlannerRequest.TupleFilter(
                java.util.Arrays.asList(
                    new PlannerRequest.Column("time_by_day", "the_year"),
                    new PlannerRequest.Column("customer", "gender")),
                java.util.Arrays.asList(
                    java.util.Arrays.<Object>asList(1997, "F"),
                    java.util.Arrays.<Object>asList(1998, "M"))))
            .addGroupBy(new PlannerRequest.Column("time_by_day", "the_year"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                "m"))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue("expected OR in: " + sql, lower.contains(" or "));
        assertTrue("expected AND in: " + sql, lower.contains(" and "));
        assertTrue("expected 1997 in: " + sql, sql.contains("1997"));
        assertTrue("expected 1998 in: " + sql, sql.contains("1998"));
        assertTrue("expected 'F' in: " + sql,
            sql.contains("'F'") || sql.contains("F"));
        assertTrue("expected 'M' in: " + sql,
            sql.contains("'M'") || sql.contains("M"));
    }

    /**
     * Regression guard paired with the SegmentLoader ordering fix (Task M
     * follow-up): when a request carries a TupleFilter, the filter's
     * columns must <em>not</em> leak into the GROUP BY clause. Only the
     * explicit {@link PlannerRequest.Builder#addGroupBy} columns and the
     * measure projections are allowed in SELECT/GROUP BY. This locks
     * down the translation shape that the
     * {@code agg-distinct-count-quarters} equivalence test exercises.
     */
    @Test
    public void tupleFilterDoesNotLeakIntoGroupBy() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "store", "store_id", "store_id"))
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addFilter(new PlannerRequest.Filter(
                new PlannerRequest.Column("store", "store_state"), "CA"))
            .addTupleFilter(new PlannerRequest.TupleFilter(
                java.util.Arrays.asList(
                    new PlannerRequest.Column("time_by_day", "the_year"),
                    new PlannerRequest.Column("time_by_day", "quarter")),
                java.util.Arrays.asList(
                    java.util.Arrays.<Object>asList(1997, "Q1"),
                    java.util.Arrays.<Object>asList(1997, "Q2"))))
            .addGroupBy(new PlannerRequest.Column("store", "store_state"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.COUNT,
                new PlannerRequest.Column(
                    "sales_fact_1997", "customer_id"),
                "m0",
                true))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        // Case-insensitive search — dialects vary on keyword case.
        String upper = sql.toUpperCase();
        int groupByIdx = upper.indexOf("GROUP BY");
        assertTrue("expected GROUP BY in: " + sql, groupByIdx >= 0);
        String groupByClause = sql.substring(groupByIdx);
        assertTrue(
            "GROUP BY must reference store_state: " + groupByClause,
            groupByClause.toLowerCase().contains("store_state"));
        assertFalse(
            "tuple-filter column 'the_year' leaked into GROUP BY: "
                + groupByClause,
            groupByClause.toLowerCase().contains("the_year"));
        assertFalse(
            "tuple-filter column 'quarter' leaked into GROUP BY: "
                + groupByClause,
            groupByClause.toLowerCase().contains("quarter"));
    }

    /**
     * Regression: Calcite's {@code Aggregate} normalises the group set to an
     * ImmutableBitSet, which re-orders group columns into the input-row's
     * column-ordinal order. The planner must reproject the group columns
     * after aggregate so the SELECT list matches the request's group-by
     * order — Mondrian's segment consumer positionally maps SELECT columns
     * onto {@code GroupingSet.columns[i]} and a reordered SELECT silently
     * assigns axis values to the wrong column (cells miss on lookup, every
     * measure comes back empty).
     *
     * <p>In FoodMart's {@code customer} table, {@code marital_status}
     * precedes {@code gender} physically, so without the reproject Calcite
     * emits them in (marital_status, gender) order even when the request
     * lists (gender, marital_status).
     */
    @Test
    public void aggregateSelectOrderMatchesGroupByRequestOrder() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addJoin(new PlannerRequest.Join(
                "customer", "customer_id", "customer_id"))
            .addGroupBy(
                new PlannerRequest.Column("time_by_day", "the_year"))
            .addGroupBy(
                new PlannerRequest.Column("customer", "gender"))
            .addGroupBy(
                new PlannerRequest.Column("customer", "marital_status"))
            .addMeasure(new PlannerRequest.Measure(
                PlannerRequest.AggFn.SUM,
                new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
                "m0"))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        // Find SELECT-list-position of gender vs marital_status. The
        // default HSQLDB dialect Calcite ships with unparses identifiers
        // unquoted, so compare substrings on the raw identifier text.
        String selectList =
            sql.substring(0, sql.toUpperCase().indexOf("FROM "));
        int iGender = selectList.indexOf("gender");
        int iMarital = selectList.indexOf("marital_status");
        assertTrue(
            "expected 'gender' in SELECT: " + sql, iGender > 0);
        assertTrue(
            "expected 'marital_status' in SELECT: " + sql, iMarital > 0);
        assertTrue(
            "SELECT list must preserve request order: gender before "
            + "marital_status; got:\n" + sql,
            iGender < iMarital);
    }

    @Test
    public void filterExpressionEmitsHavingClause() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        // Filter.json shape: GROUP BY dim cols only (no user measures),
        // HAVING on SUM(unit_sales).
        PlannerRequest.Measure havingMeasure = new PlannerRequest.Measure(
            PlannerRequest.AggFn.SUM,
            new PlannerRequest.Column("sales_fact_1997", "unit_sales"),
            "h0");
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addJoin(new PlannerRequest.Join(
                "time_by_day", "time_id", "time_id"))
            .addGroupBy(
                new PlannerRequest.Column("time_by_day", "the_year"))
            .addHaving(new PlannerRequest.Having(
                havingMeasure, PlannerRequest.Comparison.GT, 10000))
            .build();
        String sql = planner.plan(req);
        assertNotNull(sql);
        String lower = sql.toLowerCase();
        assertTrue(
            "expected HAVING clause in: " + sql,
            lower.contains("having"));
        assertTrue(
            "expected 10000 literal in HAVING: " + sql,
            sql.contains("10000"));
        assertTrue(
            "expected GROUP BY in: " + sql, lower.contains("group by"));
        // The h0 alias is HAVING-only and must NOT leak into the
        // final SELECT — the post-aggregate reproject drops it.
        assertFalse(
            "HAVING-only alias h0 must not appear in SELECT: " + sql,
            lower.contains("\"h0\"") || lower.contains(" as h0"));
    }

    /**
     * Issue #46 regression: when {@link org.apache.calcite.tools.RelBuilder#field}
     * cannot resolve a field — the canonical symptom of the #8 Calcite-translator
     * gap (RelBuilder.scan returning an empty input row-type) — the resulting
     * {@link IllegalArgumentException} must be wrapped as
     * {@link UnsupportedTranslation} so call-site fallbacks treat it as a
     * translator-coverage gap rather than letting it escape as an opaque
     * runtime error.
     */
    @Test
    public void planWrapsRelBuilderIaeAsUnsupportedTranslation() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        PlannerRequest req = PlannerRequest.builder("sales_fact_1997")
            .addProjection(
                new PlannerRequest.Column(null, "no_such_column_46"))
            .build();
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedTranslation.class, () -> planner.plan(req));
    }

    /**
     * The null-request guard remains an {@link IllegalArgumentException}
     * because that is a programmer error, not a translator gap. Confirms
     * the issue #46 wrap does not swallow this signal.
     */
    @Test
    public void planNullRequestStillRaisesIllegalArgument() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> planner.plan(null));
    }

    /**
     * #105 (TDD #3): the same param-bound PlannerRequest rendered under two
     * different validated parameter contexts emits different predicate values
     * in the SQL, each containing its own context's value.
     */
    @Test
    public void paramBoundFilterSubstitutesContextValue() {
        CalciteSqlPlanner planner = plannerFor(HsqldbSqlDialect.DEFAULT);
        java.util.Map<String, mondrian.rolap.RolapQueryParameterDef> defs =
            new java.util.LinkedHashMap<>();
        defs.put("region", mondrian.rolap.RolapQueryParameterDef.create(
            "region", "String", "EAST",
            java.util.Arrays.asList("EAST", "WEST")));

        String eastSql = planParamBound(planner, defs, "EAST");
        String westSql = planParamBound(planner, defs, "WEST");

        assertTrue("EAST in: " + eastSql, eastSql.contains("EAST"));
        assertFalse("WEST not in EAST plan: " + eastSql,
            eastSql.contains("WEST"));
        assertTrue("WEST in: " + westSql, westSql.contains("WEST"));
        assertFalse("EAST not in WEST plan: " + westSql,
            westSql.contains("EAST"));
        assertNotEquals(
            "param substitution must change the emitted SQL per context",
            eastSql, westSql);
    }

    private static String planParamBound(
        CalciteSqlPlanner planner,
        java.util.Map<String, mondrian.rolap.RolapQueryParameterDef> defs,
        String regionValue)
    {
        QueryParameterContext ctx = QueryParameterContext.resolveAll(
            defs, java.util.Collections.singletonMap("region", regionValue));
        PlannerRequest req = PlannerRequest.builder("store")
            .addProjection(new PlannerRequest.Column(null, "store_id"))
            .addFilter(PlannerRequest.Filter.boundToParam(
                new PlannerRequest.Column(null, "store_country"), "region"))
            .paramContext(ctx)
            .build();
        return planner.plan(req);
    }
}

// End CalciteSqlPlannerTest.java
