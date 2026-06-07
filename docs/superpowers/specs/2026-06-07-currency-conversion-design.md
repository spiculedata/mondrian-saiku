# Currency / unit conversion (`<CurrencyConversion>`) — design (issue #112, Phase 3)

Date: 2026-06-07
Branch: `feat/currency-conversion`
Status: approved (brainstorm + spike), pending spec review

## Goal

Let a schema author declare a currency/unit conversion on a measure — convert a
measure to a reporting currency via a rate table, picking the rate **as of the
fact's effective date** — instead of hand-writing a per-cube calculated member.
A new `<CurrencyConversion>` measure-group element produces a converted measure
`SUM(measure × rate)`, joining the rate table on currency + rate-type + an
effective-date interval.

Phase 3 of #112; independent of Phase 1 (semi-additive) and the shipped Phase 2
(time intelligence).

## Decisions (from brainstorm + spike)

- **Effective-date semantics**, realised as an **interval range-join**: the rate
  table carries non-overlapping `(valid_from, valid_to)` intervals; the as-of
  rate is the row whose interval contains the fact date. No window functions, no
  correlated subqueries.
- **Spike confirmed feasible** (2026-06-07): `RelBuilder` accepts a non-equi
  (band) join condition and `RelToSqlConverter` unparses it to SQL that runs on
  H2 returning the correct per-interval result (660 + 900 = 1560). The Calcite
  path's "inner equi-join only" is a self-imposed scope, not a Calcite limit; we
  lift it **for the conversion join only**.
- **`SUM(measure × rate)`** reuses the existing weighted-bridge multiply
  (`CalciteSqlPlanner.measureRef` → `b.call(MULTIPLY, operand, weightColumn)`,
  `PlannerRequest.Measure.weighted`).
- **Fail-closed on the legacy backend**: a currency-converted load can only be
  served by the Calcite path (the legacy generator can't do the rate join); refuse
  rather than silently mis-aggregate (mirrors the bridge policy — correctness,
  not security).
- **First cut is a single fixed `rate_type`** per declaration (e.g. `ECB`);
  multiple rate types = multiple declarations. Currency is a data join, not a
  security construct — no cache-key change needed (the converted measure is just
  another measure).

## Declaration syntax

A `<MeasureGroup>` child (a transform producing a measure):

```xml
<MeasureGroup name="Revenue" table="mm_monthly">
  <Measures>
    <Measure name="Revenue" column="revenue" aggregator="sum"/>
  </Measures>
  <CurrencyConversion name="Revenue (USD)" measure="Revenue"
      rateTable="fx_rate" rateColumn="rate"
      rateType="ECB" rateTypeColumn="rate_type"
      factCurrencyColumn="currency_id" rateCurrencyColumn="currency_id"
      factDateColumn="month_key"
      rateValidFromColumn="valid_from" rateValidToColumn="valid_to"
      formatString="#,##0.00"/>
  <DimensionLinks>...</DimensionLinks>
</MeasureGroup>
```

YAML mirror: `currency_conversions:` list with snake_case keys
(`rate_table`, `rate_column`, `rate_type`, `rate_type_column`,
`fact_currency_column`, `rate_currency_column`, `fact_date_column`,
`rate_valid_from_column`, `rate_valid_to_column`, `format_string`).

Attributes:

| attr | required | notes |
|---|---|---|
| `name` | yes | the generated converted measure name |
| `measure` | yes | base measure to convert (must exist in the group) |
| `rateTable` | yes | physical rate table to join |
| `rateColumn` | yes | the rate (multiplier) column |
| `rateType` | yes | the fixed rate-type value to select |
| `rateTypeColumn` | yes | the rate-type column on the rate table |
| `factCurrencyColumn` | yes | currency key on the fact |
| `rateCurrencyColumn` | yes | currency key on the rate table |
| `factDateColumn` | yes | the fact's effective-date column |
| `rateValidFromColumn` | yes | interval start (inclusive) on the rate table |
| `rateValidToColumn` | yes | interval end (exclusive) on the rate table |
| `formatString` | no | FORMAT_STRING for the converted measure |

**Data contract:** the rate table's `(currency, rate_type)` intervals must be
non-overlapping and cover the queried fact dates; a fact date with no matching
interval yields NULL (the row drops from the inner join — documented).

## Engine processing

- **Loader** (`RolapSchemaLoader` + `RolapMeasureGroup`): parse each
  `<CurrencyConversion>` into a `CurrencyConversionInfo` registered on the
  measure group (parallel to `BridgeInfo`). The converted `name` becomes a real
  `RolapStar.Measure`/cube measure backed by the conversion. Validate the base
  measure exists and the named columns resolve; fail-closed (`MondrianException`)
  at load otherwise.
- **Calcite emission** (`CalcitePlannerAdapters` segment-load translation): when
  the load requests a converted measure, (1) `scan` the rate table and `join` it
  to the fact with the band condition
  `fact.currency = rate.currency AND rate.rateType = '<type>' AND
   fact.date >= rate.validFrom AND fact.date < rate.validTo`
  (built via `b.and(b.equals(...), b.call(GREATER_THAN_OR_EQUAL,...),
   b.call(LESS_THAN,...))`), and (2) emit the measure as
  `Measure.weighted(base, rateColumn)` so `measureRef` produces
  `SUM(operand × rate)`. A small helper builds the band `RexNode`.
- **Fail-closed gate** (`SegmentLoader`): a load touching a currency-converted
  measure group on a non-Calcite backend (or when no Calcite SQL is produced)
  refuses, exactly like the bridge/predicate gates.

## Files

- `src/main/xom/mondrian/olap/MondrianSchema.xml` — **modify**: add
  `CurrencyConversion`/`CurrencyConversions` as a `MeasureGroup` child.
- `src/main/java/mondrian/rolap/RolapMeasureGroup.java` — **modify**: a
  `CurrencyConversionInfo` struct + registry (mirrors `BridgeInfo`).
- `src/main/java/mondrian/rolap/RolapSchemaLoader.java` — **modify**: parse +
  validate + register conversions; create the converted measure.
- `src/main/java/mondrian/calcite/CalcitePlannerAdapters.java` — **modify**: add
  the rate-table band-join + weighted measure on the conversion path; the
  currency-secured-load detector for the fail-closed gate.
- `src/main/java/mondrian/calcite/CalciteSqlPlanner.java` / `PlannerRequest.java`
  — **modify**: carry an optional band-join descriptor on the request and emit
  the non-equi condition (the multiply already exists).
- `src/main/java/mondrian/rolap/agg/SegmentLoader.java` — **modify**: extend the
  fail-closed gate to currency-converted loads.
- YAML round-trip: `XmlSchemaToYaml.java`, `YamlSchemaConverter.java`,
  `m4/M4CubeIngester.java`, `m4/M4CubeBuilder.java` — **modify** (both pairs).
- `demo/bank.sql`, `demo/Bank.mondrian.xml`, `demo/Bank.yaml` — **modify**: the
  multi-currency fixture + converted measure on `Monthly Revenue`.
- Tests (below). saiku-cloud docs-site — a "Currency conversion" page.

## Fixture (extends the Bank demo, additive)

- Add `currency_id` to `mm_monthly` (all rows `'EUR'` — additive column; existing
  Phase 2 golden numbers for `Revenue` are unchanged).
- New `fx_rate(currency_id, rate_type, valid_from, valid_to, rate)` with two
  non-overlapping intervals: `('EUR','ECB',202401,202500,1.10)` and
  `('EUR','ECB',202501,202600,1.20)`.
- Add a `<CurrencyConversion name="Revenue (USD)" measure="Revenue" .../>` to the
  `Monthly Revenue` measure group.
- **Golden:** `Revenue (USD)` grand total = 2024 (600 × 1.10 = 660) + 2025
  (750 × 1.20 = 900) = **1560**. The changing rate across the interval is what
  proves the as-of band join (a single flat rate wouldn't).

## Tests

1. **`CurrencyConversionRoundTripTest`** — `<CurrencyConversion>` round-trips
   through both YAML converter pairs.
2. **`CurrencyConversionH2EndToEndTest`** (XML + YAML): `Revenue (USD)` grand
   total = 1560; a per-year breakdown proving the interval boundary (2024 rows →
   ×1.10, 2025 rows → ×1.20); and a **fail-closed** case: an unknown base
   measure → load error, and (separately) the converted load under
   `mondrian.backend=legacy` → refuses (mirrors `BridgePredicateGrantBatteryTest`).
3. **`BankYamlParityTest`** (existing) — regenerate `demo/Bank.yaml`; drift guard
   proves the round-trip, functional check proves the YAML form converts.

## Docs (saiku-cloud docs-site)

A "Currency conversion" page: what `<CurrencyConversion>` is, the attribute
table, the interval/effective-date data contract (non-overlapping `valid_from`/
`valid_to`), the fail-closed behaviour, and the worked Bank example (XML + YAML +
the 1560 golden total). Sibling to the bridge/time-intelligence advanced pages.

## Risks / open points

- **Non-overlapping interval contract** is the author's responsibility; overlap
  would multiply-count (inner join fan-out). Document loudly; a future check
  could validate intervals at load (out of scope here).
- **Dialect coverage:** the spike proved H2; the band join unparses through
  `RelToSqlConverter` per-dialect, but only H2 is exercised in tests. Other
  dialects are expected to work (standard inequality SQL) but unverified.
- **No matching interval** → the fact row drops from the inner join (its
  converted value is absent). Documented; an outer-join variant is a future option.
- **Single rate_type per declaration** — multiple types are multiple
  `<CurrencyConversion>` elements; no multi-type resolution in this cut.

## Out of scope

- Window-function / correlated as-of resolution (the interval model avoids it).
- Triangulation (A→USD→B), inverse rates, unit-of-measure dimensional analysis.
- Validating interval non-overlap at load; outer-join "missing rate" handling.
- Phase 1 (semi-additive aggregators) — separate effort.
