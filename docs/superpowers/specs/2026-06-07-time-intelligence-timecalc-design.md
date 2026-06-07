# Declarative time-intelligence (`<TimeCalc>`) — design (issue #112, Phase 2)

Date: 2026-06-07
Branch: `feat/time-intelligence` (stacked on `feat/bank-yaml-demo`)
Status: approved (brainstorm), pending spec review

## Goal

Let schema authors *declare* the common time-intelligence metrics — year-over-year,
period-over-period, year-to-date, rolling-N — instead of hand-writing the MDX
calculated member for each measure in each cube. A new `<TimeCalc>` schema
element desugars at load time into a validated calculated member on `[Measures]`.

Removes the "#1 authoring pain" (the issue's words) and standardises navigation so
every cube's YoY is computed identically. Phase 2 of #112; independent of Phase 1
(semi-additive aggregators) and Phase 3 (currency).

## Why this is low-risk (from the #112 evaluation)

- Calc members are built by generating an MDX **formula string** fed to the
  existing validated pipeline (`RolapSchemaLoader.preCalcMember:5615` →
  `resolveCalcMembers` → `conn.parseQuery().resolve()`); a bad formula fails
  loudly at load.
- The MDX primitives already exist: `ParallelPeriod`, `Ytd`, `Lag`/`LastPeriods`,
  `Aggregate` (`src/main/java/mondrian/olap/fun/`).
- The typed-Time contract already exists and is used by those functions
  (`CubeBase.getTimeLevel`, `levelType="TimeYears|TimeQuarters|TimeMonths"`).
- The "declare → desugar → emit calc member" pattern already ships (the LookML
  transpiler's `Measures.buildFilteredCalcMember`; #108's `RolapComputedColumnFactory`).

## Decisions (from brainstorm)

- **Types:** full coherent set — `yoy`, `pop`, `ytd`, `rolling`.
- **`yoy`/`pop` emit the growth %** (not the prior-period value).
- **Fixture:** extend the Bank demo with a monthly calendar + time-series fact +
  a `Monthly Revenue` cube carrying the examples (existing cubes untouched).
- **`<TimeCalc>` is first-class** in the schema and round-trips through BOTH YAML
  converter pairs, so it is visible in the committed `Bank.yaml` and in docs.

## Declaration syntax

A `<Cube>` child (sibling of `<CalculatedMembers>`):

```xml
<TimeCalc name="Revenue YoY"  type="yoy"     measure="Revenue" timeDimension="Calendar" formatString="0.0%"/>
<TimeCalc name="Revenue PoP"  type="pop"     measure="Revenue" timeDimension="Calendar" formatString="0.0%"/>
<TimeCalc name="Revenue YTD"  type="ytd"     measure="Revenue" timeDimension="Calendar"/>
<TimeCalc name="Revenue R3"   type="rolling" measure="Revenue" timeDimension="Calendar" window="3" function="avg"/>
```

YAML mirror:

```yaml
time_calcs:
- name: "Revenue YoY"
  type: "yoy"
  measure: "Revenue"
  time_dimension: "Calendar"
  format_string: "0.0%"
- name: "Revenue R3"
  type: "rolling"
  measure: "Revenue"
  time_dimension: "Calendar"
  window: 3
  function: "avg"
```

Attributes:

| attr | required | notes |
|---|---|---|
| `name` | yes | output calculated-member name on `[Measures]` |
| `type` | yes | `yoy` \| `pop` \| `ytd` \| `rolling` |
| `measure` | yes | must resolve to an existing measure in the cube |
| `timeDimension` | no | defaults to the cube's single `type="TIME"` dimension; error if absent or ambiguous |
| `window` | rolling only | positive integer; required for `rolling`, rejected otherwise |
| `function` | rolling only | `sum` (default) \| `avg` |
| `formatString` | no | becomes the member's `FORMAT_STRING` property |

## Desugaring (`TimeCalcDesugarer`)

A pure helper: `(TimeCalc, resolved time dimension + year level + leaf level) → MDX formula string`. Independently unit-testable; no engine state. Formulas (M = `[Measures].[<measure>]`, T = `[<timeDimension>]`, Y = the `TimeYears` level):

- **yoy** — year-over-year growth %:
  `(M - (M, ParallelPeriod(T.[<Y>], 1))) / (M, ParallelPeriod(T.[<Y>], 1))`
- **pop** — period-over-period growth % (current grain):
  `(M - (M, T.CurrentMember.PrevMember)) / (M, T.CurrentMember.PrevMember)`
- **ytd** — cumulative to date:
  `Aggregate(Ytd(), M)`
- **rolling** — last `window` periods, `sum`/`avg`:
  sum: `Aggregate(LastPeriods(<window>, T.CurrentMember), M)`
  avg: `Avg(LastPeriods(<window>, T.CurrentMember), M)`

Division forms guard divide-by-zero/empty the way the shipped FoodMart "Profit Growth" member does (it divides plainly; we mirror that and document the empty-prior behaviour). Exact member-navigation details are pinned by the integration golden tests.

## Processing / validation (`RolapSchemaLoader`)

At cube build (hook: just before `createCalcMembersAndNamedSets(xmlCube.getCalculatedMembers(), …)` at `RolapSchemaLoader.java:2061`):

1. Read the cube's `<TimeCalc>` list from the XOM model.
2. Validate: `measure` exists; `timeDimension` resolves to a typed Time dimension (and the needed level exists — e.g. a `TimeYears` level for `yoy`/`ytd`); `rolling` has a positive `window`; `function`/`window` not set for non-rolling. On failure → `MondrianException` at load (fail-closed).
3. Desugar each to a `MondrianDef.CalculatedMember` (dimension `Measures`, generated formula, optional `FORMAT_STRING`) and append to the list passed into `createCalcMembersAndNamedSets`. The existing pipeline then parses + resolves the formula, so malformed navigation also fails at load.

## Files

- `src/main/xom/mondrian/olap/MondrianSchema.xml` — **modify**: add the `TimeCalc` element as a `Cube` child (attrs above).
- `src/main/java/mondrian/rolap/TimeCalcDesugarer.java` — **create**: pure type+params→MDX.
- `src/main/java/mondrian/rolap/RolapSchemaLoader.java` — **modify**: read/validate/desugar `<TimeCalc>` at `~:2061`.
- `src/main/java/mondrian/schema/yaml/XmlSchemaToYaml.java` + `YamlSchemaConverter.java` — **modify**: round-trip `time_calcs` (CLI pair).
- `src/main/java/mondrian/schema/yaml/m4/M4XmlToYaml.java` + `M4YamlToXml.java` — **modify**: round-trip `time_calcs` (showcase-test pair).
- `demo/bank.sql` — **modify**: add `mm_calendar` (month/quarter/year) + `mm_monthly` (revenue series, 2 years).
- `demo/Bank.mondrian.xml` — **modify**: `Calendar` (Year>Quarter>Month) dimension + `Monthly Revenue` cube with the 4 `<TimeCalc>` examples.
- `demo/Bank.yaml` — **regenerate** (parity test guards it).
- `src/test/java/mondrian/rolap/TimeCalcDesugarerTest.java` — **create**: unit (exact MDX per type + validation).
- `src/test/java/mondrian/calcite/TimeIntelligenceH2EndToEndTest.java` — **create**: integration golden values, XML + YAML.
- `src/test/java/mondrian/calcite/BankYamlParityTest.java` — **modify** only if needed (regenerated YAML).
- saiku-cloud docs-site — **create**: a "Time intelligence" page (path confirmed during planning per the docs-deploy convention).

## Fixture (monthly revenue, hand-verifiable)

`mm_calendar(month_key, month_name, quarter, yr)` and `mm_monthly(month_key, branch_id, revenue)` — a small series across 2 years (e.g. 2024 + 2025, a few months each) chosen so:

- YoY: a month in 2025 vs the same month in 2024 gives a clean % (e.g. 100→150 = +50%).
- YTD: cumulative within a year is an obvious running sum.
- rolling-3 avg: three consecutive months average to a round number.
- pop: month vs prior month is a clean %.

Golden values spelled out in `bank.sql` comments and asserted in the integration test. Row counts kept tiny.

## Tests

1. **`TimeCalcDesugarerTest`** (unit, no DB): each `type` produces the exact expected MDX formula string for given params; rolling sum vs avg; validation throws for missing measure / no time dimension / rolling-without-window / window-on-non-rolling.
2. **`TimeIntelligenceH2EndToEndTest`** (integration, H2 over `bank.sql`, parameterized XML + YAML): assert golden YoY %, PoP %, YTD cumulative, rolling-3 avg at specific months; and a load-time failure test (a `<TimeCalc>` referencing a missing measure → `MondrianException`).
3. **`BankYamlParityTest`** (existing): regenerate `demo/Bank.yaml`; the drift guard proves `<TimeCalc>` round-trips through the CLI converter, and the functional check proves the YAML form evaluates.

## Docs (saiku-cloud docs-site)

A new page under the Mondrian advanced docs: what `<TimeCalc>` is, the four types with formulas, the attribute table, the typed-Time-dimension requirement, and the worked `Monthly Revenue` Bank example with golden numbers. Linked from the bank/showcase docs. (Per the new-functionality DoD: human docs live in the saiku-cloud docs-site; exact path confirmed in the plan.)

## Risks / open points

- **Member navigation correctness** (the issue's named hard part): off-by-one periods, ragged/partial periods, the empty-prior-period cell. Mitigated by golden integration tests on a fixed fixture, including a partial trailing period; the divide forms mirror the shipped FoodMart "Profit Growth" member.
- **Two YAML converter pairs** must both round-trip `time_calcs` (CLI + M4). Both are hand-mapped; covered explicitly in the plan.
- **`timeDimension` defaulting**: if a cube has zero or multiple `type="TIME"` dimensions and `timeDimension` is omitted → load error (no silent guess).
- **Scope guard:** static/declared only — no Liquid/parameter-driven windows (those route through #105); single Time dimension per TimeCalc.

## Out of scope

- Phase 1 (semi-additive aggregators) and Phase 3 (currency) — separate efforts.
- Quarter/week variants of YTD (`Qtd`/`Wtd`) beyond what the four types cover — easy follow-on templates once the framework lands.
