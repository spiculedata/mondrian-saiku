q# Calcite Plan as Lingua Franca — Design

**Date:** 2026-04-19
**Branch:** TBD (post-harness; target `calcite-plan-lingua-franca`)
**Status:** Design draft — not yet approved

## Goal

Make Calcite's `RelNode` Mondrian's internal query representation. Both MDX and SQL are parsed into the **same plan model**, flow through the **same optimiser**, and are executed by a **pluggable set of conventions** (in-memory, JDBC pushdown, DuckDB accelerator, warehouse pushdown). This is the destination after the Calcite Equivalence Harness lands and after the intermediate integration rungs (SQL post-process → plan cache → RelBuilder rewrite → MV matching) are in place.

The single most valuable compat/usability win it unlocks is **SQL-over-OLAP**: dbt, Metabase, LLM agents, and any ADBC/JDBC client can query Mondrian cubes in standard SQL without ever touching MDX.

## Non-goals (this design)

- Not replacing Mondrian's MDX parser — only its execution pipeline below `mondrian.calc`.
- Not dropping the XMLA servlet. XMLA stays on the legacy MDX path until the new path proves equivalent across the full corpus.
- Not eliminating in-memory rollup. It becomes one `Convention` among several.
- Not shipping Arrow Flight SQL in this worktree (separate design).
- Not a SQL dialect specification. SQL frontend aims at "ANSI-ish SQL that works for dbt/Metabase/Jupyter" — a fuller spec follows once the shape is proven.

## Prerequisites

1. Calcite Equivalence Harness is green on smoke + aggregate corpora, harness-mutation-test documented.
2. Calcite promoted from test-scope to compile-scope (Sonatype audit passed).
3. Java baseline raised to 17 (Calcite 1.41+ idioms assume records, sealed types; staying on 1.8 is false economy).
4. At least integration rung 2 (RelBuilder rewrite) started — even if only on a narrow slice of query shapes. Lingua-franca isn't a rewrite of rung 2; it's what rung 2 becomes once both MDX and SQL land in it.

## Architectural shift

**Before:**

```
MDX → MdxParser → CalcTree (mondrian.calc) → RolapEvaluator
     → SqlQuery string builder (mondrian.rolap)
     → JDBC.executeQuery
```

**After:**

```
MDX  → MdxParser → CalcTree → RelNodeBuilder ──┐
                                                ├─→ Calcite Planner → Physical RelNode
SQL  → Calcite SqlParser (cube catalog)  ──────┘                        │
                                                                        ├─ Enumerable (in-memory rollup)
                                                                        ├─ JdbcConvention (push to backend)
                                                                        ├─ DuckDbConvention (accelerator)
                                                                        └─ SubstraitConvention (future)
```

The `SqlInterceptor` SPI from the harness *remains* the production seam — the new pipeline installs an interceptor that replaces SQL-string emission with RelNode construction, and produces final SQL (or direct execution) per the chosen convention.

## Cube catalog for Calcite

Calcite needs to *see* Mondrian's schema as a `RelOptSchema`. New adapter: `mondrian.calcite.CubeSchema`:

- `Cube` → table with a hybrid role: measure columns + dimension columns. Annotated with `CubeMetadata` so the planner knows measures are aggregates over fact rows, dimensions are hierarchical joins.
- `Hierarchy`/`Level` → expressed as grouping sets or join paths against the physical star/snowflake. Reuses existing `RolapSchemaLoader` output — no second source of truth.
- `CalculatedMember`/`NamedSet` → registered as `ScalarFunction` or `TableMacro`. Evaluated lazily; some constructs may require a "calc-tree escape hatch" convention for constructs Calcite can't yet express.

The catalog is built lazily from an existing `RolapSchema`. One `RolapSchema` → one `CubeSchema`. Reloaded on `ClearCache` / schema refresh.

## MDX → RelNode translation

The hard part. Split by MDX construct class:

- **Straightforward** (targeted for phase 1): basic SELECT axes, crossjoin, NON EMPTY, simple calc members, tuple slicers, member.Members, standard aggregates, standard time functions.
- **Translatable with work**: named sets, drillthrough, HIERARCHIZE, complex filter expressions, solve-order interactions.
- **Escape-hatch** (phase N): order-sensitive named sets, recursive calc members, obscure analytic functions — these fall back to a `CalcTreeConvention` that wraps the legacy calc-tree evaluator as a Calcite relational operator. Correctness preserved; planner sees an opaque block.

Coverage grows by corpus. Harness gates every graduated query shape.

## SQL frontend

Calcite's `SqlParser` + `SqlValidator` configured with the `CubeSchema`. Standard SQL that users already write works as-is:

```sql
SELECT d.country, SUM(m.unit_sales)
FROM   [Sales] m
JOIN   [Store] d ON m.store_id = d.store_id
WHERE  d.year = 2026
GROUP BY d.country
ORDER BY 2 DESC
```

Compiles to the same `RelNode` tree an equivalent MDX query produces. Both enter the same planner. Both get MV matching, warehouse pushdown, DuckDB caching — whatever conventions are registered.

Dialect stance: **lean on ANSI SQL**. Cube-specific extensions (drillthrough, hierarchy navigation) via table functions rather than new syntax. Ugly but compatible with every SQL client that exists.

## Execution conventions

One planner, multiple physical destinations, cost-based routing:

| Convention | When chosen | What it does |
|---|---|---|
| `EnumerableConvention` | Small result set, all data in-memory or cheaply loadable | Mondrian's current in-memory rollup, wearing a Calcite hat |
| `JdbcConvention` | Backend is a warehouse or agg-table match exists | Emit dialect SQL, push the whole plan, stream rows back |
| `DuckDbConvention` | Hot shape hit in DuckDB accelerator (see accelerator design) | Query DuckDB cache, skip primary entirely |
| `CalcTreeConvention` | Query shape not yet translatable | Delegate to legacy `RolapEvaluator` — opaque block |
| `SubstraitConvention` (future) | Peer engine speaks Substrait | Emit plan as Substrait, ship to DuckDB/DataFusion/Velox |

Router is cost-based using Calcite's existing `RelMetadataQuery` + a `CubeCostModel` we supply.

## Legacy path coexistence

Feature-flagged via `mondrian.queryEngine=rolap|calcite` (default `rolap`). Per-schema and per-query overrides supported. During rollout:

- `calcite` mode with `CalcTreeConvention` fallback means "new path where possible, legacy for the long tail."
- Mainline `mvn test` continues on `rolap`; a second profile runs the full suite on `calcite` via the harness.
- Feature flag flips to default `calcite` only after: (a) zero harness drift across full corpus, (b) perf parity or better on benchmark set, (c) one quarter of production burn-in on a volunteer cohort.

## Harness extensions required

1. **Plan-level diff.** New gate: classic `SqlQuery` emission vs. `RelNode` emission — diff the relational plans after normalisation. Drift here is early warning before SQL/rowset divergence.
2. **Multi-convention matrix.** Same MDX, different conventions, same cell set. New failure class: `CONVENTION_DRIFT`.
3. **SQL corpus.** Parallel to MDX corpus: a set of SQL queries that should produce identical cell sets to their MDX equivalents. Authored by hand initially; generated from MDX later.
4. **Perf gate.** Equivalence isn't enough. Wall-clock budget per corpus query; regression > 20% fails the build. Not part of the equivalence harness proper — separate benchmark harness.

## Phasing

| Phase | Scope | Exit criterion |
|---|---|---|
| 1. Catalog + MDX subset | `CubeSchema`, MDX → RelNode for ~10 smoke queries, `EnumerableConvention` only | Harness green on phase-1 queries behind flag |
| 2. Expand MDX coverage | Full smoke + aggregate corpora, `CalcTreeConvention` escape hatch for the long tail | Harness green full corpus; ≥80% queries on non-escape conventions |
| 3. SQL frontend | `SqlParser`+`SqlValidator` on `CubeSchema`; SQL corpus equivalent to MDX corpus | SQL corpus passes equivalence harness |
| 4. Pushdown convention | `JdbcConvention` wired for Postgres, Snowflake, BigQuery, DuckDB | Warehouse-backed query executes end-to-end; perf gate green |
| 5. MV matching on plans | Calcite MV rules matched against Mondrian agg tables expressed as materialised views | Agg-table query graduates from legacy to new path |
| 6. Default flip | `mondrian.queryEngine=calcite` default; `rolap` remains behind flag | One quarter clean perf + equivalence; zero drift reports |

Expect Phase 1 = 1 quarter, 2 = 2 quarters, 3 = 1 quarter, 4 = 2 quarters, 5–6 = 2 quarters. ~2 years elapsed end-to-end at moderate staffing.

## Risks and open questions

- **MDX semantic corners.** Order-sensitive named sets, COUNT(DISTINCT) with complex filters, solve-order gymnastics, time-function edge cases. Expect a long tail; the `CalcTreeConvention` escape hatch is the cost we pay to ship incrementally.
- **Perf regression under novel planner.** Calcite is general-purpose; Mondrian's hand-tuned path is narrow but fast. Mitigation: perf gate in harness; keep legacy path live for rollback.
- **Calcite API churn.** 1.41 → 1.42 routinely breaks APIs. Need a quarterly bump cadence and a wrapper layer between Mondrian and Calcite public API surface to absorb small breaks.
- **Team Calcite expertise.** Steep curve. Mitigation: pair two engineers on Calcite from day one; don't let it become one person's private knowledge.
- **Cube-as-table SQL surface.** Semantic questions not yet settled: how are hierarchies exposed? Does `SELECT *` include every level or just leaf attributes? How are named sets surfaced? Needs a short design sub-doc after phase 1 lands.
- **olap4j future.** Lingua-franca pipeline is orthogonal to olap4j (which is an API shape, not an execution engine). But Flight SQL / JDBC become first-class clients under this design; olap4j's strategic role shrinks. Explicit decision needed: fork, freeze, or retire.

## Open items for subsequent worktrees

- Perf benchmark harness (separate from equivalence harness) — required before phase 4.
- Arrow Flight SQL endpoint — lights up once SQL frontend (phase 3) is usable.
- Warehouse pushdown test suite (Testcontainers × {Snowflake, BigQuery emulator, Databricks, DuckDB}).
- SQL-dialect spec for cube extensions (table functions for drillthrough, hierarchy nav).

## Success criteria

1. MDX smoke + aggregate corpora execute end-to-end via `calcite` engine with zero harness drift.
2. SQL corpus executes and produces cell sets equivalent to their MDX counterparts.
3. Warehouse-pushdown convention demonstrably pushes the full plan to at least one backend (e.g., DuckDB in-process, Snowflake via Testcontainers).
4. Perf on smoke corpus within 20% of legacy baseline; better on aggregate corpus after MV matching.
5. This design doc committed; a phase-1 implementation plan (using `superpowers:writing-plans`) authored.
