# Bank showcase schema + synthetic Looker migration — design

Date: 2026-06-07
Branch: `feat/showcase-bank-data`
Status: approved (brainstorm), pending spec review

## Goal

Provide a single, cohesive, well-documented **Bank** domain that exercises every
new piece of 4.8.1.x functionality in one place, usable for **both integration
testing and demonstration** ("so people can see them and understand"). It must
also (a) complete the already-shipped-but-orphaned `demo/Bank.mondrian.xml`
(its companion data is missing) and (b) include a **synthetic LookML model** that
demonstrates Looker → M4 migration over the same data.

This is primarily a *cohesion + demonstration* effort: nearly every feature
already has an isolated per-feature integration test (bridge ×11, predicate
grant ×7, distinct grain ×2, tier+duration, median/percentile, query-param
round-trip). The value added here is one shared dataset + one readable schema +
one migration example that show the features working **together**, with new
tests that guarantee total coverage and serve as living documentation.

## Non-goals

- Not byte-identical equivalence between the hand-written M4 and the transpiled
  LookML (user chose "unified data, parallel schemas", loose equivalence).
- Not a replacement for the existing per-feature tests — these complement them.
- No new product/runtime features; this is schema + data + tests + docs only.

## Decisions (from brainstorm)

- **Shape:** unified shared dataset, parallel schemas (hand-written M4 showcase
  + synthetic LookML over the SAME tables; both queryable on one DB).
- **Domain:** extend the existing **Bank** (`mm_*`) domain.
- **Coverage:** every new feature must be demonstrated and tested; reuse existing
  per-feature tests, add new tests only to close gaps and to provide the unified
  demonstration.
- **Branching:** one feature branch (`feat/showcase-bank-data`) holds everything,
  including `bank.sql`. `bank.sql` backs the already-released Bank schema, which
  is exactly why this is branched rather than committed straight to `develop`.
- **Backend:** Calcite (the default). Bridge / RLS features require it; the demo
  README/comments must state this.

## Feature coverage matrix

| Feature (issue) | Demonstrated by (showcase) | LookML source (bank.lkml) | Existing tests reused |
|---|---|---|---|
| Full-count bridge (#107/#103) | `Accounts` cube, full-count measures | `many_to_many` join, `relationship: many_to_many` | Bridge* (×11) |
| Weighted bridge (#107) | `AccountsWeighted` cube | `many_to_many` + weight | BridgeDimensionScenariosTest |
| Predicate RLS (#106) | `Tenant` role, PredicateGrant on `tenant` | `access_filter` on a fact column | PredicateGrant* (×7) |
| Member/Hierarchy grant RLS (#107) | `Segment` role, HierarchyGrant on Customer | `access_filter` on a dimension key | BridgeMemberGrantRlsTest |
| Bounded query parameters (#105) | `tenant` QueryParameter (enumerated) | bounded `{{ _user_attributes['tenant'] }}` | QueryParameterYamlRoundTripTest |
| Distinct grain sum/avg (#119) | `Transactions` cube, distinct over `txn_id` | `sum_distinct` / `average_distinct` | MeasureDistinctGrain*Test |
| Median / percentile (#104) | `Accounts` cube, median + p90 balance | `type: median` / `percentile` measures | Percentile*Test |
| Native tier dimension (#108) | `Accounts` balance tier | `tiers: [...]` dimension | TierDurationH2EndToEndTest |
| Native duration dimension (#108) | `Accounts` account-age duration | duration `dimension_group` | TierDurationH2EndToEndTest |

## Dataset — `demo/bank.sql` (H2 + portable SQL)

One small, hand-verifiable dataset. Tables (all prefixed `mm_`):

- `mm_customer(customer_id PK, customer_name, segment_id)` — links to segment.
- `mm_segment(segment_id PK, segment_name)` — e.g. `Retail`, `Private`
  (drives the Customer → Segment hierarchy and the member-grant RLS test).
- `mm_owner(account_id, customer_id, weight DECIMAL)` — the **bridge** table
  (many-to-many account↔customer) with an ownership `weight`.
- `mm_branch(branch_id PK, branch_name, region)` — `region` drives nothing alone
  but supports a conformed dimension; predicate RLS uses `tenant` (below).
- `mm_fact(account_id PK, branch_id, tenant INT, balance INT, open_date DATE)` —
  one row per account: `tenant` → predicate RLS; `balance` → median/percentile +
  balance **tier**; `open_date` → account-age **duration**.
- `mm_txn(txn_id, line_no, account_id, amount INT)` — a deliberately
  **fanned-out** transactions table (one logical txn repeated across lines with
  the SAME amount) for **measure-level distinct grain** (`sum_distinct` over
  `txn_id`). A second small set with a varying amount documents the
  count-each-grain-once fix (#119).

Row counts kept tiny (≈3 customers, 2 segments, ≈4 accounts, ≈6 owner rows incl.
joint accounts, ≈6 txn lines) so every asserted number is hand-derivable and the
data reads as documentation. Sample numbers (e.g. full-count grand total, the
de-duped distinct sum, tenant-partitioned RLS totals) are spelled out in
comments in `bank.sql` and asserted in the tests.

The dataset is a **superset**: it backs the shipped `demo/Bank.mondrian.xml`
(which only reads `mm_fact`/`mm_owner`/`mm_customer`/`mm_branch`/`mm_date`) AND
the new `Showcase` cubes. (`mm_date` retained for Bank; the Showcase uses
`open_date` directly. If Bank needs `mm_date`, it is included.)

## M4 showcase — `demo/Showcase.mondrian.xml` (+ YAML round-trip)

One schema, a few focused, heavily-commented cubes (each comment block explains
the feature and the expected number):

- **`Accounts`** — full-count bridge to Customer; `Balance` (sum), `Median
  Balance` (median), `P90 Balance` (percentile=90); a **balance tier** dimension
  (Low/Med/High) and an **account-age duration** dimension; conformed Branch
  dimension. Roles: `Tenant` (PredicateGrant on `tenant`) and `Segment`
  (HierarchyGrant/MemberGrant on the Customer→Segment hierarchy).
- **`AccountsWeighted`** — weighted bridge (SUM(balance×weight)).
- **`Transactions`** — `Distinct Amount` (`sum` + `distinctKeyColumn=txn_id`),
  `Distinct Avg` (`avg` + distinct grain); shows the de-dup over the fan-out.

The schema is authored in XML and validated in **both XML and YAML** form via the
M4 `XmlToYaml`/`YamlToXml` round-trip, matching the codebase's dual-form testing
convention.

## Synthetic Looker model — `demo/lookml/bank.lkml`

A single LookML file over the same `mm_*` tables that transpiles (via the #98–125
pipeline) to comparable M4. It exercises the importer's shipped CLEAN/DEGRADE
paths:

- `explore: accounts` over `view: accounts` (star) — base.
- `join: customers` with `relationship: many_to_many` via the owner bridge →
  `<BridgeLink>` (#124).
- `access_filter` on a fact column (`tenant`) → `<PredicateGrant>` (#106).
- `access_filter` on a dimension key (`segment`) → `<HierarchyGrant>` (#115).
- `measure: distinct_amount { type: sum_distinct sql_distinct_key: txn_id }` →
  symmetric / distinct grain (#117/#119).
- `measure: median_balance { type: median }`, `p90 { type: percentile }` (#104).
- `dimension: balance_tier { tiers: [...] }` → tier (#108).
- `dimension_group: account_age { type: duration ... }` → duration (#108).
- a bounded `{{ _user_attributes['tenant'] }}` reference (#118).

## Tests

Both load `demo/bank.sql` into an in-memory H2 (same pattern as the existing
`*H2EndToEndTest` suites) and clear the planner cache between cases.

1. **`ShowcaseH2EndToEndTest`** (XML + YAML, parameterized):
   - Each feature's headline number (full-count grand total, weighted total,
     median, p90, distinct sum, distinct avg, tier buckets, duration buckets).
   - **Role-based RLS** (the DoD requirement): `Tenant` role sees only its
     partition; `Segment` role excludes hidden-segment customers from bridge
     rollups; an ungranted/no-role connection sees the full total; and a
     cross-role check that one role never sees another's data.
   - XML and YAML forms produce identical numbers.

2. **`BankLookmlMigrationH2EndToEndTest`**:
   - Transpile `bank.lkml` → M4; assert the expected **classification** (CLEAN
     where shipped) and **provenance** entries, and crucially that RLS is **not
     silently dropped** (ties to the #106/#107 fail-closed work).
   - Run a handful of queries on the transpiled cube over the same `bank.sql`
     data and assert they return sensible numbers (loose equivalence with the
     hand-written showcase — same ballpark/semantics, not byte-identical).

3. **Coverage-matrix note** — a short `demo/lookml/README.md` (or comment header)
   mapping each feature to the demonstrating test, so reviewers and users can see
   what proves what.

## Risks / open points

- **Duration reference date:** account-age duration needs a stable "as of" date
  or it drifts with wall-clock. Use a fixed reference (a literal in the
  dimension definition or a `mm_date`-anchored value) so test numbers are stable.
- **Bank.mondrian.xml compatibility:** `bank.sql` must satisfy the shipped
  schema's column expectations exactly; verify by loading Bank against it too
  (a smoke assertion) so we don't fix one demo and break another.
- **LookML loose equivalence:** keep the LookML test assertions about
  classification/provenance + sanity numbers, not exact parity, per the chosen
  approach.

## Out of scope / follow-ups

- Wiring the Showcase schema into the saiku-cloud demo deployment / docs-site
  (separate repo; can follow once this lands). Per the new-functionality DoD,
  human docs for these features belong in the docs-site and are tracked
  separately.
