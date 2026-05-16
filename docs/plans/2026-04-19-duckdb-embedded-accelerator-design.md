 # DuckDB Embedded Accelerator — Design

**Date:** 2026-04-19
**Branch:** TBD (target `duckdb-accelerator`)
**Status:** Design draft — not yet approved

## Goal

Use embedded DuckDB as a **persistent columnar cache for hot query shapes**, sitting behind the existing `SqlInterceptor` seam. Repeated/similar MDX queries short-circuit to DuckDB instead of hitting the primary backend. Cold miss falls through to the primary with no behavioural change. Optional secondary outcome: developer ergonomics — once DuckDB is a first-class backend fixture, local work against FoodMart stops requiring Postgres/H2/HSQLDB setup.

## Non-goals (v1)

- **Not** a real-time sync with primary. Invalidation is coarse (TTL + existing `ClearCache`).
- **Not** cross-process cache sharing. Single-node, per-JVM accelerator.
- **Not** a replacement for `SegmentCache`. Layered on top; segment cache stays as-is.
- **Not** dev-backend mode. v1 is accelerator-only. Dev-backend mode (DuckDB as the *primary* backend for local development against Parquet/CSV) is a v2 follow-up — much of the work is shared but the user contract differs.
- **Not** dependent on the Calcite lingua-franca work. Ships on the legacy `mondrian.rolap` path first; migrates to a `DuckDbConvention` once Calcite rung 4 lands.

## Prerequisites

1. Calcite Equivalence Harness is green (required to prove the accelerator path preserves equivalence).
2. Sonatype audit of `org.duckdb:duckdb_jdbc` passed.
3. OpenTelemetry scaffolding present (cache-hit rate / latency need emitting somewhere useful). Ideal but not strictly blocking — hit-rate can initially log via existing log4j.

## Architectural overview

```
                MDX
                 │
          RolapEvaluator
                 │
        SqlQuery emission
                 │
        ┌────────▼─────────┐
        │  SqlInterceptor  │ ← DuckDbAcceleratorInterceptor
        └────────┬─────────┘
                 │
        ┌────────▼─────────┐
        │  Shape-hash      │
        │  manifest lookup │
        └────┬────────┬────┘
             │hit     │miss
             ▼        ▼
   DuckDB query    Primary JDBC executeQuery
   (rewritten)     │
             │     └── async populate cache
             ▼        (if shape is cacheable)
        Cell set
```

## Integration point

The `SqlInterceptor` SPI already lands in the harness worktree. The accelerator is one concrete `SqlInterceptor` implementation: `DuckDbAcceleratorInterceptor`. Registered via `mondrian.sqlInterceptor=mondrian.cache.duckdb.DuckDbAcceleratorInterceptor`.

On each `onSqlEmitted(sql, dialect)`:

1. Normalise SQL and hash the **shape** (see below). O(μs) — cheap.
2. Lookup shape in manifest. If present and fresh → rewrite SQL to `SELECT … FROM duckdb_cache.<table>` and return; primary never queried.
3. If absent or stale → return original SQL unchanged (caller hits primary as normal). In parallel, if the shape is *eligible* (size + frequency heuristics), schedule async cache population.

Cache population runs the original SQL against primary, writes the rowset into a DuckDB table, and records the shape in the manifest. Never blocks the user's query.

## Shape hashing

A "shape" is the SQL structure with literal values parameterised. Two queries with the same shape but different slicer values share cache eligibility:

```sql
SELECT product_family, SUM(unit_sales) FROM sales WHERE year = 2026 GROUP BY product_family
SELECT product_family, SUM(unit_sales) FROM sales WHERE year = 2025 GROUP BY product_family
                                                        ^^^^ literal differs, shape identical
```

Implementation options, in order of pragmatism:

1. **Calcite SqlParser + parameterise literals** (preferred once Calcite is compile-scope). Robust, dialect-aware.
2. **Regex normaliser** as a stopgap (collapse literals, whitespace, casing). Fast and good enough for initial phase — wrong sometimes, safe-side failure mode (cache miss, no correctness risk).

Shapes that cache: `GROUP BY` queries with bounded cardinality, agg-table-like shapes, dimension joins with constrained WHERE. Shapes that don't: drillthrough, queries with `LIMIT`/`OFFSET` pagination against ordered fact scans, queries whose result row count exceeds the size cap.

## Cache layout in DuckDB

One DuckDB file per Mondrian catalog, default `${mondrian.work.dir}/cache/<catalog>.duckdb`. Per shape:

- Table `cache_<shape-hash>` with columns matching the SQL's projection.
- Manifest table `cache_manifest(shape_hash, sql_shape, created_at, last_accessed, row_count, size_bytes, ttl_seconds)`.
- Parameterised queries against cached shapes rewrite `WHERE col = ?` against the cached rows.

Eviction: LRU by `last_accessed`, size-capped per catalog (default 2 GiB, configurable). Background thread runs eviction every 60 s.

## Invalidation

- **TTL** per shape, default 1 hour, configurable per-shape via manifest. Simple and explicit.
- **ClearCache XMLA op** already exists in Mondrian for cell-cache invalidation — hook the accelerator's `drop_all` into the same SPI call. Operators already know this mechanism.
- **Schema reload** drops the DuckDB file entirely (schema change ⇒ any cached shapes may be invalid).
- **Not CDC.** If the primary changes underneath a cached shape within TTL, the cache serves slightly stale data. Documented as expected behaviour. Users who need freshness set `ttl_seconds=0` or call `ClearCache`.

## Dependencies

- `org.duckdb:duckdb_jdbc:0.10.x` (MIT licensed; bundles native libs for linux-amd64/arm64, macos-amd64/arm64, windows-amd64). Runtime + test scope.
- No Testcontainers needed — DuckDB runs embedded.

## Harness extensions required

The equivalence harness gains an **accelerator mode**:

- **Run A (classic):** interceptor = identity.
- **Run B (accelerator):** interceptor = `DuckDbAcceleratorInterceptor` with cache pre-warmed.
- **Outer gate:** cell-set diff A vs. B. Drift → `ACCELERATOR_CELL_SET_DRIFT`.
- **Inner gate:** for shapes that hit cache, verify the DuckDB rewrite returns the same rowset the primary would have. Drift → `ACCELERATOR_ROWSET_DRIFT`.
- **Cold path:** Run B with empty cache. Identical to Run A (interceptor is a no-op on miss). Drift → `ACCELERATOR_COLD_PATH_DRIFT` (should never happen; signals interceptor bug).

Mutation test: deliberately perturb the shape-hash normaliser to produce false cache hits. Harness must fail with `ACCELERATOR_ROWSET_DRIFT`.

## Observability

All via OpenTelemetry (presumes OTel scaffolding lands first or alongside). Span attributes per query: `mondrian.cache.shape_hash`, `mondrian.cache.outcome` (hit/miss/populate), `mondrian.cache.latency_ms`, `mondrian.cache.rows`. Metrics: hit rate, cache size, eviction rate, population queue depth.

## Phasing

| Phase | Scope | Exit criterion |
|---|---|---|
| 1. Dependency + smoke | Add DuckDB dep, single integration test: embedded DuckDB executes a hand-built query | Test green; Sonatype audit clean |
| 2. Manifest + shape hashing | Regex-normaliser shape hasher, manifest schema, no rewriting yet | Unit tests prove stable hashes across literal variation |
| 3. Interceptor: read path | `DuckDbAcceleratorInterceptor` implements lookup + rewrite; cache pre-populated manually in tests | Smoke corpus hits cache; classic cell set matches accelerator cell set |
| 4. Async population | First-execution populates cache in background; eligibility heuristics | Population runs off the hot path; second identical query hits cache |
| 5. Harness extension | New failure classes + accelerator matrix wired into `mvn test -Pcalcite-harness` | Mutation test demonstrates teeth |
| 6. Invalidation | TTL + ClearCache hook; eviction thread | Invalidation surface covered by tests |
| 7. Benchmarks + default-on | Perf benchmark on representative workload; feature flag flips to default-on for eligible shapes | ≥5× speedup on cache-hit path; no regression on cold path |

Phase 1–3 ≈ 1 month. Phase 4–7 ≈ 2 months. Total ≈ 1 quarter at moderate staffing.

## Risks

- **Shape hasher false positives** — two shapes hash identical but have different semantics → wrong cached results served. Defence: regex normaliser errs conservative; Calcite-based hasher in phase 2 once Calcite is compile-scope; harness accelerator gate catches it.
- **DuckDB memory footprint** — default DuckDB config can balloon. Cap via `SET memory_limit='1GB'` at connection.
- **Native lib distribution** — DuckDB bundles arches, but hardened environments sometimes block loading native libs. Fallback: document how to disable accelerator (unset `mondrian.sqlInterceptor`).
- **Cache coherency confusion** — ops team needs to understand TTL vs. ClearCache semantics. Mitigation: clear docs, default TTL short (1 h), `ClearCache` obvious.
- **Populate storm** — many cold misses on similar shapes → many async populates → primary backend overload. Defence: per-shape populate lock + populate queue with concurrency cap.
- **Disk growth** — `.duckdb` file grows without bound if eviction is broken. Defence: hard size cap enforced at populate time; alarm metric on cache size.

## Open items for v2 (not this worktree)

- **Dev-backend mode**: DuckDB as primary backend for local development. Needs a DuckDB Mondrian dialect (may already exist in community), FoodMart-on-DuckDB fixture, Maven profile.
- **Cross-process cache via MotherDuck** — shared accelerator across a cluster. Interesting once multi-instance Mondrian is a real deployment pattern.
- **Shape graduation to materialised view** — shapes that hit consistently get promoted to persistent MVs managed outside Mondrian. Feeds into Calcite MV matching (lingua-franca phase 5).
- **Arrow IPC materialisation** — export cached shapes as Arrow IPC files; Flight SQL can serve them directly with zero re-encode.

## Success criteria

1. `mvn test -Pcalcite-harness` passes with accelerator mode enabled across smoke + aggregate corpora.
2. Mutation test (deliberately-wrong shape hasher) fails the harness with the correct failure class.
3. Benchmark on a representative MDX workload shows ≥5× speedup on cache-hit path, no regression (<5%) on cold/miss path.
4. Cache file survives JVM restart; first-query-after-restart is a hit.
5. OpenTelemetry traces show cache outcome and latency per query.
6. This design doc committed; an implementation plan (via `superpowers:writing-plans`) authored.
