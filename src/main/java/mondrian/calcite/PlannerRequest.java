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

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable description of a Calcite plan request: fact table, optional
 * inner-equi-joins onto dimensions, projections (when not aggregating),
 * group-by columns + measures (when aggregating), equality filters, and
 * order-by.
 *
 * <p>Worktree #1 scope is intentionally narrow: equality filters only,
 * inner equi-join only, no nested subqueries, no DISTINCT, no window
 * functions. See {@link CalciteSqlPlanner} for the rendering side.
 */
public final class PlannerRequest {

    public enum AggFn { SUM, COUNT, MIN, MAX, AVG, PERCENTILE_CONT }
    public enum Order { ASC, DESC }

    public static final class Column {
        /** Optional table qualifier; null when unambiguous. */
        public final String table;
        public final String name;
        /** #108: when non-null, this column projects a structured computed
         *  expression (a {@link TierExpr} or {@link DurationExpr}) rather
         *  than a plain table column. The renderer builds the Rex from this
         *  instead of {@code b.field(name)}; {@link #name} is still the
         *  result alias. Null for ordinary columns. */
        public final ComputedExpr expr;
        public Column(String table, String name) {
            this(table, name, null);
        }
        public Column(String table, String name, ComputedExpr expr) {
            this.table = table;
            this.name = name;
            this.expr = expr;
        }
    }

    /** #108: marker for a structured computed-column expression carried by
     *  a {@link Column} on the group-by / projection path. */
    public abstract static class ComputedExpr {
    }

    /** #108: one branch of a {@link TierExpr}: rows whose {@code column}
     *  value is strictly less than {@code boundary} take {@code label};
     *  a branch with a null boundary is the open-ended ELSE. */
    public static final class TierBranch {
        public final Number boundary;
        public final String label;
        public TierBranch(Number boundary, String label) {
            this.boundary = boundary;
            this.label = label;
        }
    }

    /** #108: a multi-branch tier CASE over {@code column}. Branches are in
     *  ascending boundary order; the final branch is the open-ended ELSE
     *  (null boundary). Rendered as a nested Calcite CASE. */
    public static final class TierExpr extends ComputedExpr {
        public final Column column;
        public final List<TierBranch> branches;
        public TierExpr(Column column, List<TierBranch> branches) {
            this.column = column;
            this.branches = List.copyOf(branches);
        }
    }

    /** #108: time units a {@link DurationExpr} is expressed in. Matches the
     *  Calcite {@code TimeUnit} names used to build the interval qualifier
     *  passed to {@code TIMESTAMP_DIFF}. */
    public enum DurationUnit {
        DAY, WEEK, MONTH, QUARTER, YEAR, HOUR, MINUTE, SECOND
    }

    /** #108: a date-diff of {@code endColumn − startColumn} in
     *  {@code unit}. Rendered via Calcite {@code TIMESTAMP_DIFF} so each
     *  dialect's unparse is correct. */
    public static final class DurationExpr extends ComputedExpr {
        public final Column startColumn;
        public final Column endColumn;
        public final DurationUnit unit;
        public DurationExpr(
            Column startColumn, Column endColumn, DurationUnit unit)
        {
            this.startColumn = startColumn;
            this.endColumn = endColumn;
            this.unit = unit;
        }
    }

    public static final class Measure {
        public final AggFn fn;
        public final Column column;
        public final String alias;
        /** Whether the aggregator applies DISTINCT (only honoured for
         *  {@link AggFn#COUNT} today; rendered as {@code count(distinct x)}).
         *  Added for cardinality-probe dispatch (Task C). */
        public final boolean distinct;
        /** When non-null, the measure aggregates this literal value
         *  instead of a column reference. Used by the CalcitePlannerAdapters
         *  segment-load translator for calc-columns that resolve to a
         *  constant (e.g. {@code <CalculatedColumnDef>} with SQL just
         *  {@code 0} / {@code NULL}). The {@link #column} alias is still
         *  used as the result alias; the {@code column.name} is ignored.
         *  Sentinel value {@link #NULL_LITERAL} represents SQL NULL —
         *  distinct from "no literal set" (this field == null). */
        public final Object literal;
        /** When non-null, the measure aggregates a structured CASE
         *  expression instead of a column reference. Used for
         *  calc-column measures like FoodMart's Promotion Sales:
         *  {@code case when promotion_id = 0 then 0 else store_sales end}.
         *  Mutually exclusive with {@link #literal}. */
        public final CaseExpr caseExpr;
        /** When non-null, the measure aggregates a structured binary
         *  arithmetic expression ({@code lhsCol &lt;op&gt; rhsCol}).
         *  Covers calc-column measures like FoodMart's warehouse_profit
         *  ({@code warehouse_sales - warehouse_cost}). Mutually
         *  exclusive with {@link #literal} and {@link #caseExpr}. */
        public final ArithExpr arithExpr;
        /** #107 weighted bridge: when non-null, the measure's operand
         *  (whatever its shape — column / CASE / arithmetic / literal) is
         *  multiplied by this bridge weight column before aggregating, i.e.
         *  {@code SUM(operand × weight)}. Orthogonal to the other operand
         *  fields. Only set for weighted bridges. */
        public final Column weightColumn;
        /** #104 ordered-set aggregate: the fraction (0..1) for
         *  {@code PERCENTILE_CONT(fraction) WITHIN GROUP (ORDER BY column)}.
         *  Non-null iff {@link #fn} is {@link AggFn#PERCENTILE_CONT}; the
         *  ORDER BY target is {@link #column}. */
        public final Double percentileFraction;
        /** Sentinel value used in {@link #literal} to represent SQL NULL,
         *  distinguishing it from the field being unset (which is also
         *  Java null). */
        public static final Object NULL_LITERAL = new Object();
        public Measure(AggFn fn, Column column, String alias) {
            this(fn, column, alias, false, null, null, null);
        }
        public Measure(
            AggFn fn, Column column, String alias, boolean distinct)
        {
            this(fn, column, alias, distinct, null, null, null);
        }
        public Measure(
            AggFn fn, Column column, String alias, boolean distinct,
            Object literal)
        {
            this(fn, column, alias, distinct, literal, null, null);
        }
        public Measure(
            AggFn fn, Column column, String alias, boolean distinct,
            Object literal, CaseExpr caseExpr)
        {
            this(fn, column, alias, distinct, literal, caseExpr, null);
        }
        public Measure(
            AggFn fn, Column column, String alias, boolean distinct,
            Object literal, CaseExpr caseExpr, ArithExpr arithExpr)
        {
            this(fn, column, alias, distinct, literal, caseExpr, arithExpr,
                null);
        }
        public Measure(
            AggFn fn, Column column, String alias, boolean distinct,
            Object literal, CaseExpr caseExpr, ArithExpr arithExpr,
            Column weightColumn)
        {
            this.fn = fn;
            this.column = column;
            this.alias = alias;
            this.distinct = distinct;
            this.literal = literal;
            this.caseExpr = caseExpr;
            this.arithExpr = arithExpr;
            this.weightColumn = weightColumn;
            this.percentileFraction = null;
        }

        /** #104: a {@code PERCENTILE_CONT(fraction) WITHIN GROUP (ORDER BY
         *  orderColumn)} ordered-set aggregate measure. */
        private Measure(
            String alias, Column orderColumn, double percentileFraction)
        {
            this.fn = AggFn.PERCENTILE_CONT;
            this.column = orderColumn;
            this.alias = alias;
            this.distinct = false;
            this.literal = null;
            this.caseExpr = null;
            this.arithExpr = null;
            this.weightColumn = null;
            this.percentileFraction = percentileFraction;
        }

        /** A percentile (median = 0.5) ordered-set aggregate measure that
         *  orders by {@code orderColumn}. */
        public static Measure percentile(
            String alias, Column orderColumn, double fraction)
        {
            return new Measure(alias, orderColumn, fraction);
        }

        /** Copy of {@code base} that multiplies its operand by
         *  {@code weightColumn} when aggregating — the weighted-bridge form
         *  of any measure shape (#107). */
        public static Measure weighted(Measure base, Column weightColumn) {
            return new Measure(
                base.fn, base.column, base.alias, base.distinct,
                base.literal, base.caseExpr, base.arithExpr, weightColumn);
        }
    }

    /** Binary arithmetic operator for {@link ArithExpr}. */
    public enum ArithOp { PLUS, MINUS, TIMES, DIVIDE }

    /** Structured binary arithmetic descriptor used by
     *  {@link Measure#arithExpr}: {@code lhsCol <op> rhsCol}. Covers
     *  the canonical FoodMart warehouse_profit shape
     *  ({@code warehouse_sales - warehouse_cost}). */
    public static final class ArithExpr {
        public final Column lhsCol;
        public final ArithOp op;
        public final Column rhsCol;
        public ArithExpr(Column lhsCol, ArithOp op, Column rhsCol) {
            this.lhsCol = lhsCol;
            this.op = op;
            this.rhsCol = rhsCol;
        }
    }

    /**
     * Structured CASE expression descriptor used by
     * {@link Measure#caseExpr}.
     *
     * <p>Covers the canonical FoodMart Promotion Sales shape:
     * {@code case when whenCol = whenLiteral then thenLiteral
     * else elseCol end}. More general CASE shapes (multi-WHEN,
     * column-vs-column, expression operands) are deferred — the
     * shape here is exactly what the corpus exercises.
     */
    public static final class CaseExpr {
        public final Column whenCol;
        public final Object whenLiteral;
        public final Object thenLiteral;
        public final Column elseCol;
        public CaseExpr(
            Column whenCol, Object whenLiteral,
            Object thenLiteral, Column elseCol)
        {
            this.whenCol = whenCol;
            this.whenLiteral = whenLiteral;
            this.thenLiteral = thenLiteral;
            this.elseCol = elseCol;
        }
    }

    public enum Operator { EQ, IN }

    /** HAVING comparison operator set — the binary-compare subset the
     *  corpus exercises for {@code RolapNativeFilter$FilterConstraint}.
     *  Added for Task P. {@code IS_NULL} and {@code IS_NOT_NULL} are
     *  unary on the measure side; the {@code Having.literal} field is
     *  ignored in those cases (use any non-null placeholder, e.g.
     *  {@link Boolean#TRUE}, to satisfy the constructor invariant). */
    public enum Comparison { GT, LT, GE, LE, EQ, NE, IS_NULL, IS_NOT_NULL }

    /**
     * HAVING predicate: {@code <measure> <op> <literal>}. Used for
     * native-filter translation where the MDX Filter expression is a
     * single binary comparison of a stored measure against a numeric
     * literal. The {@link #measure} is projected in the same aggregate
     * as the GROUP BY so the renderer can filter on its alias, then
     * dropped by the post-aggregate reprojection so the SELECT list
     * remains {groupBy, userMeasures}. Added in Task P.
     */
    public static final class Having {
        public final Measure measure;
        public final Comparison op;
        public final Object literal;
        public Having(Measure measure, Comparison op, Object literal) {
            if (measure == null) {
                throw new IllegalArgumentException("measure is null");
            }
            if (op == null) {
                throw new IllegalArgumentException("op is null");
            }
            if (literal == null) {
                throw new IllegalArgumentException("literal is null");
            }
            this.measure = measure;
            this.op = op;
            this.literal = literal;
        }
    }

    public static final class Filter {
        public final Column column;
        public final Operator op;
        public final List<Object> literals;
        /** #105: when non-null, this is a parameter-bound EQ filter — the
         *  predicate value is NOT one of {@link #literals} but the validated,
         *  typed value of the named query parameter resolved at render time
         *  from the request's {@link PlannerRequest#paramContext}. This is the
         *  single Phase-2 isolation seam: a future {@code ?} bind-variable
         *  swap touches only the render site, not this model. The
         *  {@link #literals} list holds a sentinel ({@link #PARAM_PLACEHOLDER})
         *  so the EQ single-literal invariant still holds. */
        public final String paramRef;
        /** Back-compat shortcut: single-literal EQ filter. */
        public Filter(Column column, Object literal) {
            this(column, Operator.EQ,
                java.util.Collections.singletonList(literal));
        }
        public Filter(Column column, Operator op, List<Object> literals) {
            this(column, op, literals, null);
        }
        private Filter(
            Column column, Operator op, List<Object> literals, String paramRef)
        {
            this.column = column;
            this.op = op;
            // Defensive copy that tolerates null elements — a null
            // literal represents a SQL NULL match (rendered as IS NULL
            // downstream). List.copyOf / List.of reject nulls.
            this.literals = java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(literals));
            this.paramRef = paramRef;
            // A parameter-bound filter holds a single placeholder regardless
            // of op (EQ or IN); the real value(s) are resolved at render
            // time. So bypass the literal-count invariants when paramRef set.
            if (paramRef == null) {
                if (op == Operator.EQ && this.literals.size() != 1) {
                    throw new IllegalArgumentException(
                        "EQ filter requires exactly one literal; got "
                        + this.literals.size());
                }
                if (op == Operator.IN && this.literals.isEmpty()) {
                    throw new IllegalArgumentException(
                        "IN filter requires at least one literal");
                }
            }
        }
        /** #105: sentinel literal held by a parameter-bound filter so the
         *  EQ invariant holds; the real value is resolved at render time. */
        public static final Object PARAM_PLACEHOLDER = new Object();
        /** #105: build an EQ filter whose value is bound to a declared query
         *  parameter, resolved+validated at render time. */
        public static Filter boundToParam(Column column, String paramRef) {
            if (paramRef == null || paramRef.isEmpty()) {
                throw new IllegalArgumentException("paramRef required");
            }
            return new Filter(
                column, Operator.EQ,
                java.util.Collections.singletonList(PARAM_PLACEHOLDER),
                paramRef);
        }
        /** #106: build an IN filter whose membership set is bound to a declared
         *  query parameter (a comma-separated session value), each token
         *  coerced+validated at render time. The single placeholder keeps the
         *  literals list non-empty; the real set is resolved off the
         *  request's {@link PlannerRequest#paramContext}. */
        public static Filter boundToParamIn(Column column, String paramRef) {
            if (paramRef == null || paramRef.isEmpty()) {
                throw new IllegalArgumentException("paramRef required");
            }
            return new Filter(
                column, Operator.IN,
                java.util.Collections.singletonList(PARAM_PLACEHOLDER),
                paramRef);
        }
        public boolean isParamBound() {
            return paramRef != null;
        }
        /** Back-compat accessor: EQ filter's single literal. */
        public Object literal() {
            if (op != Operator.EQ) {
                throw new IllegalStateException(
                    "literal() only valid on EQ filters; this is " + op);
            }
            return literals.get(0);
        }
    }

    /**
     * OR-of-AND cross-column tuple filter. Expresses
     * {@code (col0=v00 AND col1=v01) OR (col0=v10 AND col1=v11) OR ...}
     * where every row is aligned to the shared {@link #columns} list.
     * A single-column TupleFilter collapses to an IN-list at render time.
     * Added for segment-load cross-column OR predicates (Task M).
     */
    public static final class TupleFilter {
        public final List<Column> columns;
        public final List<List<Object>> rows;
        public TupleFilter(List<Column> columns, List<List<Object>> rows) {
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException(
                    "TupleFilter requires at least one column");
            }
            if (rows == null || rows.isEmpty()) {
                throw new IllegalArgumentException(
                    "TupleFilter requires at least one row");
            }
            List<Column> cols = List.copyOf(columns);
            List<List<Object>> rs = new ArrayList<>(rows.size());
            for (List<Object> row : rows) {
                if (row.size() != cols.size()) {
                    throw new IllegalArgumentException(
                        "TupleFilter row arity " + row.size()
                        + " != columns arity " + cols.size());
                }
                List<Object> copy = new ArrayList<>(row.size());
                for (Object v : row) {
                    copy.add(v);
                }
                rs.add(java.util.Collections.unmodifiableList(copy));
            }
            this.columns = cols;
            this.rows = java.util.Collections.unmodifiableList(rs);
        }
    }

    /**
     * A post-aggregate computed measure: an arithmetic expression over
     * already-aggregated base measures. Rendered by
     * {@link CalciteSqlPlanner} as an extra projection on top of the
     * aggregate, built by {@link ArithmeticCalcTranslator}.
     *
     * <p>The {@link #expression} is an opaque MDX {@code Exp} tree; the
     * planner owns a translator and the {@link #baseMeasureAliases}
     * mapping resolves a calc's base-measure references to the
     * aggregate row's columns.
     *
     * <p>Added in Task T (arithmetic calc-member pushdown).
     */
    public static final class ComputedMeasure {
        public final String alias;
        public final Object expression; // mondrian.olap.Exp, kept Object
                                        // to avoid an import-cycle burden
                                        // on callers that don't touch
                                        // MDX directly.
        /** Map from mondrian.olap.Member (base measure) → alias of the
         *  Measure entry in {@link PlannerRequest#measures}. */
        public final java.util.Map<Object, String> baseMeasureAliases;

        public ComputedMeasure(
            String alias, Object expression,
            java.util.Map<Object, String> baseMeasureAliases)
        {
            if (alias == null || alias.isEmpty()) {
                throw new IllegalArgumentException("alias required");
            }
            if (expression == null) {
                throw new IllegalArgumentException("expression required");
            }
            if (baseMeasureAliases == null) {
                throw new IllegalArgumentException(
                    "baseMeasureAliases required");
            }
            this.alias = alias;
            this.expression = expression;
            this.baseMeasureAliases =
                java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(baseMeasureAliases));
        }
    }

    public static final class OrderBy {
        public final Column column;
        public final Order direction;
        public OrderBy(Column column, Order direction) {
            this.column = column;
            this.direction = direction;
        }
    }

    /** Join flavour. INNER = equi-join on ({@link Join#factKey},
     *  {@link Join#dimKey}); CROSS = unconditional cross-join (the key
     *  fields are ignored). Added for multi-target tuple-read (Task H). */
    public enum JoinKind { INNER, CROSS, BAND }

    public static final class Join {
        /** Alias the renderer uses to refer to this dim's row-set on the
         *  builder stack (the LHS for subsequent joins and field
         *  references). When {@link #physName} is null, this is also the
         *  physical table name to {@code RelBuilder.scan(...)}. When
         *  {@code physName} is non-null and differs from this alias, the
         *  renderer emits {@code b.scan(physName).as(dimTable)} so
         *  Mondrian-aliased PhysTables (multiple DimensionUsages of the
         *  same shared dim → {@code store}, {@code store_1}, {@code
         *  store_2}) resolve correctly against {@code JdbcSchema}. */
        public final String dimTable;
        public final String factKey;
        public final String dimKey;
        public final JoinKind kind;
        /** Optional LHS of the edge. {@code null} means "the fact table"
         *  (single-hop, back-compat with callers that only expressed
         *  fact→dim joins). When non-null, this identifies an already-
         *  joined table alias on the builder stack so the renderer can
         *  resolve {@link #factKey} against the correct input of a
         *  multi-hop snowflake chain (Task I). */
        public final String leftTable;
        /** Optional physical table name. {@code null} means {@link
         *  #dimTable} <em>is</em> the physical table name (back-compat:
         *  single-DimensionUsage cubes never need an alias). Non-null
         *  when the cube schema aliases the same shared dim across
         *  multiple DimensionUsages — e.g. {@code physName="store"} with
         *  {@code dimTable="store_2"} for the third Store usage. Issue
         *  #46 third-class fix. */
        public final String physName;
        /** #112 band join (currency conversion): the fact currency column,
         *  the rate currency column, the fact date column, the rate interval
         *  start/end columns, the rate-type column, and the literal rate-type
         *  value to match. Non-null only when {@link #kind} is BAND. */
        public final String factCurrencyKey;
        public final String rateCurrencyCol;
        public final String factDateKey;
        public final String validFromCol;
        public final String validToCol;
        public final String rateTypeCol;
        public final String rateTypeValue;
        public Join(String dimTable, String factKey, String dimKey) {
            this(dimTable, factKey, dimKey, JoinKind.INNER, null, null);
        }
        public Join(
            String dimTable, String factKey, String dimKey, JoinKind kind)
        {
            this(dimTable, factKey, dimKey, kind, null, null);
        }
        public Join(
            String dimTable, String factKey, String dimKey, JoinKind kind,
            String leftTable)
        {
            this(dimTable, factKey, dimKey, kind, leftTable, null);
        }
        public Join(
            String dimTable, String factKey, String dimKey, JoinKind kind,
            String leftTable, String physName)
        {
            this.dimTable = dimTable;
            this.factKey = factKey;
            this.dimKey = dimKey;
            this.kind = kind;
            this.leftTable = leftTable;
            this.physName = physName;
            this.factCurrencyKey = null;
            this.rateCurrencyCol = null;
            this.factDateKey = null;
            this.validFromCol = null;
            this.validToCol = null;
            this.rateTypeCol = null;
            this.rateTypeValue = null;
        }
        /** #112 private band-join constructor (kind = BAND). {@code factAlias}
         *  is stored in {@link #leftTable} so the renderer qualifies the
         *  fact-side band fields against the fact table — robust whether the
         *  band join is emitted before or after the dimension joins (e.g. the
         *  fact date column would otherwise collide with a Calendar dim's
         *  identically-named key). */
        private Join(
            String factAlias, String rateTable, String physName,
            String factCurrencyKey, String rateCurrencyCol, String factDateKey,
            String validFromCol, String validToCol, String rateTypeCol,
            String rateTypeValue)
        {
            this.dimTable = rateTable;
            this.factKey = null;
            this.dimKey = null;
            this.kind = JoinKind.BAND;
            this.leftTable = factAlias;
            this.physName = physName;
            this.factCurrencyKey = factCurrencyKey;
            this.rateCurrencyCol = rateCurrencyCol;
            this.factDateKey = factDateKey;
            this.validFromCol = validFromCol;
            this.validToCol = validToCol;
            this.rateTypeCol = rateTypeCol;
            this.rateTypeValue = rateTypeValue;
        }
        /** #112 factory for a currency-conversion rate band join. */
        public static Join band(
            String factAlias, String rateTable, String physName,
            String factCurrencyKey, String rateCurrencyCol,
            String factDateKey, String validFromCol, String validToCol,
            String rateTypeCol, String rateTypeValue)
        {
            return new Join(factAlias, rateTable, physName, factCurrencyKey,
                rateCurrencyCol, factDateKey, validFromCol, validToCol,
                rateTypeCol, rateTypeValue);
        }
        /** Convenience factory for an unconditional CROSS JOIN. */
        public static Join cross(String dimTable) {
            return new Join(
                dimTable, null, null, JoinKind.CROSS, null, null);
        }
        /** Cross-join variant carrying a physical table name distinct
         *  from {@code dimAlias}. */
        public static Join cross(String dimAlias, String physName) {
            return new Join(
                dimAlias, null, null, JoinKind.CROSS, null, physName);
        }
        /** Convenience factory for an inner equi-join whose LHS is an
         *  already-joined non-fact table (Task I snowflake multi-hop). */
        public static Join chained(
            String leftTable, String leftKey,
            String dimTable, String dimKey)
        {
            return new Join(
                dimTable, leftKey, dimKey, JoinKind.INNER, leftTable, null);
        }
        /** Chained variant carrying a physical table name distinct from
         *  {@code dimAlias}. */
        public static Join chained(
            String leftTable, String leftKey,
            String dimAlias, String dimKey, String physName)
        {
            return new Join(
                dimAlias, leftKey, dimKey, JoinKind.INNER,
                leftTable, physName);
        }
    }

    public final String factTable;
    /** Optional physical fact-table name. {@code null} means {@link
     *  #factTable} <em>is</em> the physical name (back-compat). Non-null
     *  when the schema aliases the fact table (rare but valid: a cube
     *  whose MeasureGroup table is referenced under an alias distinct
     *  from the physical table name). Issue #46 third-class fix —
     *  parallels {@link Join#physName} for symmetry. */
    public final String factPhysName;
    public final List<Join> joins;
    public final List<Column> groupBy;
    public final List<Measure> measures;
    public final List<Column> projections;
    public final List<Filter> filters;
    public final List<TupleFilter> tupleFilters;
    public final List<Having> havings;
    public final List<ComputedMeasure> computedMeasures;
    public final List<OrderBy> orderBy;
    /** Row-level DISTINCT on projections. Only valid when not aggregating.
     *  Added for tuple-read / level-member dispatch (Task E). */
    public final boolean distinct;
    /** When true, the request has a universal FALSE filter (zero rows).
     *  Other {@link #filters} are ignored by the renderer.
     *  Added for segment-load predicate translation (Task F). */
    public final boolean universalFalse;
    /** Row-limit clause to emit ({@code LIMIT n} or its dialect-specific
     *  equivalent). 0 (the default) means no limit. Used in place of
     *  {@code Statement.setMaxRows} so multi-backend correctness no longer
     *  depends on driver-specific JDBC behaviour — DuckDB JDBC silently
     *  ignores setMaxRows. */
    public final int limit;
    /** When non-empty, this request is a UNION (distinct) of these arms.
     *  The renderer unions the arms and applies only {@link #orderBy} over
     *  the union output; the request's own fact/joins/projections are
     *  ignored. Used for cross-measure-group tuple reads — the M4
     *  equivalent of the legacy virtual-cube UNION across fact tables. */
    public final List<PlannerRequest> unionArms;
    /** #103 symmetric aggregate: when non-null, this aggregation fans out
     *  over a one-to-many join (a bridge), so the renderer must de-duplicate
     *  on the fact grain before aggregating — {@code SUM} over a
     *  {@code SELECT DISTINCT groupKeys, grain, measureCols} subquery —
     *  instead of a naive {@code SUM} that would double-count fanned rows.
     *  The column is the fact-grain key (the fact table's primary key). Only
     *  set for full-count bridges with plain real-column measures; weighted
     *  bridges roll up additively and never set this. */
    public final Column symmetricGrainColumn;

    /** #105: per-request resolved query-parameter context. Carries the
     *  validated, typed values that parameter-bound filters substitute at
     *  the single render seam. Never null — defaults to
     *  {@link QueryParameterContext#EMPTY}. */
    public final QueryParameterContext paramContext;

    private PlannerRequest(Builder b) {
        this.factTable = b.factTable;
        this.factPhysName = b.factPhysName;
        this.joins = List.copyOf(b.joins);
        this.groupBy = List.copyOf(b.groupBy);
        this.measures = List.copyOf(b.measures);
        this.projections = List.copyOf(b.projections);
        this.filters = List.copyOf(b.filters);
        this.tupleFilters = List.copyOf(b.tupleFilters);
        this.havings = List.copyOf(b.havings);
        this.computedMeasures = List.copyOf(b.computedMeasures);
        this.orderBy = List.copyOf(b.orderBy);
        this.distinct = b.distinct;
        this.universalFalse = b.universalFalse;
        this.limit = b.limit;
        this.unionArms = List.copyOf(b.unionArms);
        this.symmetricGrainColumn = b.symmetricGrainColumn;
        this.paramContext = b.paramContext == null
            ? QueryParameterContext.EMPTY : b.paramContext;
        if (this.distinct
            && (!this.measures.isEmpty() || !this.groupBy.isEmpty()))
        {
            throw new IllegalStateException(
                "PlannerRequest.distinct is mutually exclusive with "
                + "aggregation (measures / group-by)");
        }
    }

    public boolean isAggregation() {
        return !measures.isEmpty() || !groupBy.isEmpty();
    }

    public static Builder builder(String factTable) {
        return new Builder(factTable);
    }

    /**
     * Builder for a UNION (distinct) of two or more arm requests — the
     * cross-measure-group tuple-read shape. Add the union-level
     * {@link OrderBy} entries (applied over the union output) before
     * {@link Builder#build()}.
     */
    public static Builder unionBuilder(List<PlannerRequest> arms) {
        if (arms == null || arms.size() < 2) {
            throw new IllegalArgumentException(
                "unionBuilder requires at least 2 arms");
        }
        Builder b = new Builder(arms.get(0).factTable);
        b.unionArms.addAll(arms);
        return b;
    }

    public static final class Builder {
        private final String factTable;
        private String factPhysName;
        private final List<Join> joins = new ArrayList<>();
        private final List<Column> groupBy = new ArrayList<>();
        private final List<Measure> measures = new ArrayList<>();
        private final List<Column> projections = new ArrayList<>();
        private final List<Filter> filters = new ArrayList<>();
        private final List<TupleFilter> tupleFilters = new ArrayList<>();
        private final List<Having> havings = new ArrayList<>();
        private final List<ComputedMeasure> computedMeasures =
            new ArrayList<>();
        private final List<OrderBy> orderBy = new ArrayList<>();
        private boolean distinct;
        private boolean universalFalse;
        private int limit;
        private final List<PlannerRequest> unionArms = new ArrayList<>();
        private Column symmetricGrainColumn;
        private QueryParameterContext paramContext;

        private Builder(String factTable) {
            if (factTable == null || factTable.isEmpty()) {
                throw new IllegalArgumentException("factTable required");
            }
            this.factTable = factTable;
        }

        /** Set the physical fact-table name when it differs from
         *  {@link #factTable}. See {@link PlannerRequest#factPhysName}. */
        public Builder factPhysName(String physName) {
            this.factPhysName = physName;
            return this;
        }
        public Builder addJoin(Join j) { joins.add(j); return this; }
        public Builder addGroupBy(Column c) { groupBy.add(c); return this; }
        public Builder addMeasure(Measure m) { measures.add(m); return this; }
        /** #107 weighted bridge: multiply every (not-already-weighted) measure
         *  operand by {@code weightColumn} — {@code SUM(operand × weight)}.
         *  Used when a weighted-bridge member grant forces the fan-out join
         *  into scope for a load that does not group by the bridge dimension
         *  (e.g. a bare {@code [All]}), where the weight was not resolved at
         *  measure-build time. Idempotent: a measure already carrying a weight
         *  (the load groups by the bridge dimension) is left unchanged. */
        public Builder weightMeasures(Column weightColumn) {
            if (weightColumn == null) {
                return this;
            }
            for (int i = 0; i < measures.size(); i++) {
                Measure m = measures.get(i);
                if (m.weightColumn == null
                    && m.percentileFraction == null)
                {
                    measures.set(i, Measure.weighted(m, weightColumn));
                }
            }
            return this;
        }
        /** #107: whether any measure already carries a bridge weight (the load
         *  groups by a weighted bridge dimension, so weighting is in scope). */
        public boolean hasWeightedMeasure() {
            for (Measure m : measures) {
                if (m.weightColumn != null) {
                    return true;
                }
            }
            return false;
        }
        /** #103: mark this aggregation as fan-out-safe over the given fact
         *  grain key — see {@link PlannerRequest#symmetricGrainColumn}. */
        public Builder symmetricGrainColumn(Column c) {
            this.symmetricGrainColumn = c;
            return this;
        }
        /** #107: whether a fan-out grain key has already been pinned (so a
         *  later row-security injection does not clobber it). */
        public boolean hasSymmetricGrainColumn() {
            return this.symmetricGrainColumn != null;
        }
        /** #105: attach the resolved query-parameter context that
         *  parameter-bound filters substitute at render time. */
        public Builder paramContext(QueryParameterContext ctx) {
            this.paramContext = ctx;
            return this;
        }
        public Builder addProjection(Column c) {
            projections.add(c);
            return this;
        }
        public Builder addFilter(Filter f) { filters.add(f); return this; }
        public Builder addTupleFilter(TupleFilter f) {
            tupleFilters.add(f);
            return this;
        }
        public Builder addHaving(Having h) {
            if (h == null) {
                throw new IllegalArgumentException("having is null");
            }
            havings.add(h);
            return this;
        }
        public Builder addComputedMeasure(ComputedMeasure cm) {
            if (cm == null) {
                throw new IllegalArgumentException("computedMeasure is null");
            }
            computedMeasures.add(cm);
            return this;
        }
        public Builder addOrderBy(OrderBy o) { orderBy.add(o); return this; }
        public Builder distinct(boolean d) { this.distinct = d; return this; }
        public Builder universalFalse(boolean f) {
            this.universalFalse = f;
            return this;
        }
        public Builder limit(int n) {
            if (n < 0) {
                throw new IllegalArgumentException(
                    "limit must be >= 0 (got " + n + ")");
            }
            this.limit = n;
            return this;
        }

        public PlannerRequest build() {
            if (unionArms.isEmpty()
                && projections.isEmpty()
                && measures.isEmpty()
                && groupBy.isEmpty())
            {
                throw new IllegalStateException(
                    "PlannerRequest needs at least one projection, "
                    + "measure, or group-by column");
            }
            return new PlannerRequest(this);
        }
    }
}

// End PlannerRequest.java
