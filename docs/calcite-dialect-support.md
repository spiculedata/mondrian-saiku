# Calcite Backend — Dialect Support Matrix

Per-dialect status of Mondrian's Calcite SQL emitter (`-Dmondrian.backend=calcite` and the strict-mode default). Closes the DoD item from issue #10.

## Status taxonomy

| Status | Meaning |
|---|---|
| ✅ **Fully supported** | All 41-query Calcite-equivalence-harness queries pass; zero translator fallbacks observed in the test corpus. |
| 🟡 **Partially supported** | Most shapes pass; known gaps documented below. With `-Dmondrian.calcite.strict=true` (default since 4.8.1.6), translator gaps surface as hard failures rather than silent legacy-SQL fallback. |
| 🔴 **Not supported** | Calcite backend known to mis-translate or hard-crash on this dialect. Deployment should leave `-Dmondrian.backend=calcite` off OR set `-Dmondrian.calcite.strict=false` for silent fallback. |
| ⚪ **Untested** | No CI coverage; production use at your own risk. |

## Matrix

| Dialect | Calcite SQL emitter | ADBC native wire | Tested via |
|---|---|---|---|
| **HSQLDB** | ✅ Fully supported | n/a (no ADBC driver) | `cross-backend-equivalence.yml` HSQLDB harness, 41 queries |
| **DuckDB** | ✅ Fully supported | ✅ DuckDB-native `arrowExportStream` | `cross-backend-equivalence.yml` DuckDB harness, 41 queries + `#36` Phase 0 spike |
| **PostgreSQL** | 🟡 Partial — 7 result-diff candidates outstanding (see below) | ✅ via libadbc_driver_postgresql (build-from-source, see #47) | `PostgresConnectivityTest` + `PerfBenchmarkTest` (opt-in, requires Postgres FoodMart scale=1000) |
| **MySQL** | ⚪ Untested | ❌ no ADBC Java artifact published | — |
| **Oracle** | ⚪ Untested | ❌ no ADBC Java artifact published | — |
| **MS SQL Server** | ⚪ Untested | ❌ no ADBC Java artifact published | — |
| **Snowflake** | ⚪ Untested | ⚪ ADBC driver exists (adbc-drivers org) but unverified from Java | — |
| **BigQuery** | ⚪ Untested | ⚪ ADBC driver exists but unverified from Java | — |
| **Databricks / Spark** | ⚪ Untested | ⚪ ADBC driver exists but unverified from Java | — |
| **ClickHouse** | ⚪ Untested | ⚪ ADBC driver exists but unverified from Java | — |
| **Trino** | ⚪ Untested | ⚪ ADBC driver exists but unverified from Java | — |
| **Redshift** | ⚪ Untested | ⚪ ADBC driver exists but unverified from Java | — |

## Known per-dialect gaps

### PostgreSQL

- **Result-diff candidates** — `+7` result divergences surfaced during the `4.8.1.6 → strict-mode` rollout (see [`pages/decisions/calcite-fallback-stance` in the project wiki][wiki-fallback] + issue #8). Triage ticket TBD.
- **Native ADBC setup** — `libadbc_driver_postgresql.dylib` not Homebrew-packaged; build from source via `cmake --build` against `apache/arrow-adbc`'s `apache-arrow-adbc-23` branch. Setup recipe in PR #47 commit `7ca2221`.

### MySQL, Oracle, MSSQL, Snowflake, BigQuery, Databricks, ClickHouse, Trino, Redshift

- **No CI coverage.** Production users should run the cross-backend equivalence harness (`mvn -Pcalcite-harness`) against their backend before enabling `-Dmondrian.backend=calcite`. Per-dialect gaps may surface and need ticket filing.
- **Calcite-backed `Dialect` bridge** (#40 / PR #41) lets these backends use Mondrian-side dialect-aware push-down without a hand-written `mondrian.spi.Dialect` subclass — the bridge derives capability flags from Calcite's `SqlDialect`. Behavior validated for HSQLDB; assumed-correct for other backends Calcite recognises.

## Observability of fallback rate

When `-Dmondrian.calcite.strict=false` is set (opt-out from default strict mode), every Calcite translator failure that falls back to legacy SQL is now:

1. **Logged at WARN level** (was DEBUG pre-2026-05-17; #10) — surfaces in production logs without needing debug logging enabled.
2. **Counted via the OTel metric `mondrian.calcite.fallback`** with attributes:
   - `mondrian.calcite.fallback.site` — one of `tuple-read`, `segment-load`, `segment-load-worker`
   - `mondrian.calcite.fallback.exception` — the exception class simple name

Dashboards can break down fallback rate by site to identify which call paths are hitting translator gaps most often. Pairs with the `mondrian.sql.statements` counter (broken down by `mondrian.sql.kind`) for the "what fraction of segment-load SQL is going through Calcite vs legacy" story.

See [`docs/opentelemetry.md`](opentelemetry.md) for OTel setup.

## How to add a new dialect to this matrix

1. Run the cross-backend equivalence harness against the target dialect:
   ```bash
   CALCITE_HARNESS_BACKEND=<dialect> mvn -Pcalcite-harness test
   ```
2. If all 41 queries pass → status ✅. If some fail → triage and status 🟡 with a list of gaps. If most fail / hard crashes → status 🔴.
3. Update this matrix + open a per-gap issue with the failure shape.

[wiki-fallback]: /Users/tombarber/Documents/Obsidian/mondrian-saiku/pages/decisions/calcite-fallback-stance.md
