# Bank demo — feature showcase & Looker migration

`demo/Bank.mondrian.xml` over `demo/bank.sql` (the shipped joint-accounts
dataset, extended) demonstrates every Mondrian-4 / 4.8.1.x feature in one
place; `demo/lookml/bank.lkml` shows the equivalent Looker model migrating to
M4. Requires the Calcite backend (`mondrian.backend=calcite`, the Saiku
default) — bridge and row-security features cannot be served by the legacy SQL
generator.

## Feature → schema element → test

| Feature | Schema element | Demonstrating test |
|---|---|---|
| Full-count bridge (#107/#103) | cube `Joint Accounts (Full Count)` | `BankShowcaseH2EndToEndTest.bridgeAndStatsGoldenNumbers` |
| Weighted bridge (#107) | cube `Joint Accounts (Weighted)` | `bridgeAndStatsGoldenNumbers` |
| Median / percentile (#104) | cube `Account Statistics` | `bridgeAndStatsGoldenNumbers` |
| Balance tier (#108) | `Account` dim `Balance Tier` | `tierAndDurationDimensions` |
| Account-age duration (#108) | `Account` dim `Age Years` | `tierAndDurationDimensions` |
| Query parameter (#105) | `<QueryParameter name="tenant">` | `predicateRowSecurityByTenant` |
| Predicate RLS (#106) | role `Tenant` | `predicateRowSecurityByTenant` |
| Member-grant RLS (#107) | role `Premium` | `memberGrantRowSecurityBySegment` |
| Distinct grain (#119) | cube `Transactions` | `distinctGrainTransactions` |
| Looker migration | `bank.lkml` | `BankLookmlMigrationH2EndToEndTest` |

All golden numbers are hand-verifiable from `demo/bank.sql` (total balance
13000). The schema is validated in both XML and YAML form (the tests run each
assertion against `Bank.mondrian.xml` and its YAML round-trip).

## Files

- `../bank.sql` — dataset (shipped base from
  `../saiku/saiku-launcher/src/main/resources/seed/bank.sql`, kept in sync,
  plus `tenant`/`open_date`/`as_of_date` columns and the `mm_txn` fan-out table).
- `../Bank.mondrian.xml` — the comprehensive M4 schema (XML).
- `../Bank.yaml` — the same schema in YAML, so the showcase can be shown in both
  formats. It is the `mondrian schema-cli to-yaml` conversion of the XML; a
  parity test (`BankYamlParityTest`) keeps it in sync and proves every construct
  survives the YAML form. Regenerate with
  `mondrian schema-cli to-yaml demo/Bank.mondrian.xml -o demo/Bank.yaml`.
- `bank.lkml` — the synthetic Looker model demonstrating Looker → M4 migration.

## What the LookML example shows

`bank.lkml` exercises the importer's shipped CLEAN/DEGRADE paths over the same
tables:

- a many-to-many bridge two-hop (`one_to_many` then `many_to_one`) → `<BridgeLink>` (#124);
- `access_filter` on a fact column → `<PredicateGrant>` (#106);
- `access_filter` on a modelled dimension key → `<HierarchyGrant>` (#115);
- `sum_distinct` on a same-view non-primary key → measure-level distinct grain (#117/#119);
- `type: tier` → `<Tier>` and `dimension_group: { type: duration }` → `<Duration>` (#108);
- `type: median` / `type: percentile` measures (#104).

Row-security is verified to survive the migration (the secured explore is never
refused).
