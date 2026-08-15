# Property-based test suite (Hegel)

Property-based tests for Mondrian, built on [Hegel](https://github.com/hegeldev/hegel-java)
(`dev.hegel:hegel`, MIT). Instead of asserting that one hand-picked input produces one
hand-written answer, each test states a property that must hold for *all* inputs and lets the
engine search for a counterexample — then shrinks whatever it finds to the smallest input that
still fails.

## Running

```bash
mvn test -Phegel                                    # whole suite
mvn test -Phegel -Dtest=UtilQuotingPropertyTest     # one class
```

**Requires a Java 22+ JVM.** Hegel calls a native engine through the Foreign Function & Memory
API. The default build and CI stay on JDK 21, so the suite is opt-in behind the `hegel` Maven
profile and runs in CI as its own JDK-22 job (`.github/workflows/hegel.yml`).

The profile also:

- adds `src/test/hegel/java` as a test source root — under JDK 21 these files are not compiled
  at all, so they cannot break the default build;
- raises **only** the test compile to release 22 (main stays at 1.8, so the published artifact is
  unaffected);
- passes `--enable-native-access=ALL-UNNAMED`;
- restricts surefire to `**/mondrian/property/*Test.java`, plus `**/mondrian/util/*PropertyTest.java`
  and `**/mondrian/test/calcite/*PropertyTest.java` for the two classes that need package-private
  access, so `-Phegel` runs *only* this suite.

### Reproducing a failure

A failing run prints the shrunk counterexample, e.g. `name = "]]";`. Hegel also records failing
examples in a gitignored `.hegel/` directory and replays them first on the next local run, so once
you have seen a failure you keep seeing it until it is fixed. In CI the database is disabled
automatically (`CI=true`), so every CI run explores fresh inputs — **a property that passed
yesterday can legitimately fail today.** That is the suite working, not flaking.

To pin a run: `@HegelTest(seed = 42)`.

## What is here

137 tests across 21 classes, about two and a half minutes. A further two are opt-in (see Calcite below).

**Engine** — properties that run real MDX against the HSQLDB FoodMart fixture:

| Class | What it pins |
| --- | --- |
| `NativeEvaluationEquivalencePropertyTest` | **Native SQL push-down must agree with in-memory evaluation.** The highest-value property here: each path is the other's oracle, it covers the whole surface at once, and it targets the failure mode that matters most — a fast path returning different numbers, silently, in the default configuration. |
| `MdxEngineMetamorphicPropertyTest` | Relations that need no oracle: `Filter(S, TRUE) == S`, `Head + Tail == S`, `Order` is a permutation, crossjoin cardinality, sum additive over a partition, `Count` agrees with axis cardinality. |
| `MdxSetAlgebraPropertyTest` | The laws of set algebra over `Union`/`Intersect`/`Except`: commutativity, associativity, identity, absorption, De Morgan, inclusion–exclusion. |
| `MdxAggregationPropertyTest` | Consistency between `Sum`/`Count`/`Avg`/`Min`/`Max`/`Aggregate`: `Min ≤ Avg ≤ Max`, `Sum == Avg × Count`, order-independence, monotonicity over subsets. |
| `MdxHierarchyNavigationPropertyTest` | Drill-down/drill-up inverses: `Parent`/`Children`, `Ancestor`, `Descendants`, `Lag`/`Lead`, `PrevMember`/`NextMember`, `Hierarchize` idempotence. |
| `AccessControlPropertyTest` | **Row-level security.** A role never invents members and never returns anything outside its granted subtree, across generated queries. |
| `SegmentCacheConsistencyPropertyTest` | An answer must not depend on what was asked before it — the cache-key-collision failure that example tests structurally cannot reach. |
| `MdxDescendantsAlgebraPropertyTest` | The eight `Descendants` flags decompose into `(self, before, after, leaves)`, so every compound flag must be exactly the union of its parts and the parts must be pairwise disjoint. A complete spec, for free. |
| `MdxTimeSeriesPropertyTest` | `Ytd`/`Qtd` == `PeriodsToDate` at the matching level, `OpeningPeriod`/`ClosingPeriod` are first/last of `Descendants`, `ParallelPeriod` is invertible, `LastPeriods` ends at its member. Period-over-period is where off-by-one bugs hide. |
| `DrillThroughAndConcurrencyPropertyTest` | Drill-through counts are additive over children and agree with cell emptiness; the same query on N threads matches the serial answer. |
| `MdxParserRoundTripPropertyTest` | `unparse` is idempotent — what Mondrian reports it ran parses back to what it ran. |
| `AggregateTableDifferentialPropertyTest` | **Currently skipped, deliberately** — see below. Answering from an aggregate table must equal answering from the fact table. |

**Pure** — no database, milliseconds:

| Class | What it pins |
| --- | --- |
| `HegelPlumbingTest` | That the engine loads, generates varied input, and actually shrinks. A degraded property suite still reports green, so this is what licenses the rest. |
| `DialectQuotingPropertyTest` | Injection safety across **26 dialects**: quoting is injective, delimited, and cannot be terminated early. |
| `UtilQuotingPropertyTest` | MDX identifier quoting, MDX/SQL string literals, connect-string round trips. |
| `UtilCollectionsPropertyTest` | `Util.binarySearch`/`intersect`/`isSorted`/`isDistinct`/`flatList`/`appendArrays` against oracles; `Pair` equals/hashCode/compareTo laws. |
| `ChunkListPropertyTest` (in `mondrian.util`) | Stateful model-based test: a generated operation sequence applied to both `ChunkList` and `ArrayList`, checking contents **and** the package-private `isValid()` chunk-link invariant after every step. |
| `ListStructurePropertyTest` | `CartesianProductList`, `CompositeList`, `ConcatenableList`, `ArrayStack` against plain-Java oracles. |
| `ArraySortedSetPropertyTest` | The segment cache's value set, model-checked against `TreeSet`. |
| `BinaryCodecPropertyTest` | Base64 round trips; `ByteString` equals/hashCode/compareTo contracts (it is the schema-cache key). |
| `FormatPropertyTest` | `Format` is pure and safe to reuse across cells — the failure mode that only appears under reuse, never in isolation. |

**Opt-in** (not run by plain `-Phegel`):

| Class | What it pins |
| --- | --- |
| `CalciteDifferentialPropertyTest` | Routing a generated query through Calcite must not change its cell set. Reuses the existing equivalence harness's `executeCold`, but with generated queries instead of a recorded corpus and **no golden files** — it goes straight to the harness's own cell-set gate. Each case is two cold executions, so run it with `mvn test -Phegel -Dhegel.calcite=true`. |

`Generators`, `MdxGenerator` and `FoodMart` are shared scaffolding, not tests.

## Two classes that are skipped or scoped, on purpose

`AggregateTableDifferentialPropertyTest` is **skipped**. The HSQLDB fixture ships 11 `agg_*` tables,
but the catalog `TestContext` loads (`demo/FoodMart.mondrian.xml`) declares none of them — no
`AggName`, no `AggPattern`. With nothing declared Mondrian recognises no aggregate stars, so both
sides of the differential would be the same configuration and all four properties would pass while
proving nothing. Its guard detected exactly that (`aggregate stars: with=0 without=0`) and disables
the class rather than report four hollow green ticks. **To activate it**, declare the fixture's
`agg_*` tables in the test catalog; the guard then passes and the differential runs for real.

That guard was itself broken first time round: it called `RolapCube.getStar()`, which this 4.x fork
deprecates to the point of throwing, so it errored while the four properties passed. Worth
remembering — a vacuity guard that fails open is worse than none.

## Two kinds of test in here

Most methods are `@HegelTest` **properties** — generated, shrinking, and the real product.

Some are plain `@Test` **characterisation tests**. Each one pins a *defect this suite found* that
was not fixed in the same change, because fixing it is a product decision rather than a test
decision. They assert the current, wrong behaviour on purpose, and each carries a comment saying
so. **When one fails, that is good news** — it means someone fixed the underlying bug, and the
test should be deleted and its property restored.

## Findings

Fixed here:

- **[#140] `quoteIdentifier` emitted already-delimited input verbatim, with no escaping.**
  `JdbcDialectImpl.quoteIdentifierImpl` short-circuited on `val.startsWith(q) && val.endsWith(q)`
  ("already quoted - nothing to do"), assuming such a value was a well-formed quoted identifier.
  It need not be. Three consequences, all minimal witnesses the generator produced: quoting was
  **not injective** (`a` and `"a"` both became `"a"`); a **lone delimiter** passed through as itself,
  emitting an unbalanced quote; and `"a" FROM t WHERE 1=1 --"` was copied verbatim into the SQL.
  In the shared base implementation, so all 26 dialects. Now escaped unconditionally; callers must
  pass a raw identifier, and the two-argument overload remains for qualified names.
- **`Util.parseConnectString` crashed on any input ending in `==`** — `StringIndexOutOfBoundsException`
  out of `parseName`, reachable from configuration and from XMLA. This is a surviving instance of
  MONDRIAN-397; the original fix covered the trailing-`;` and trailing-space cases the reporter hit
  and left this one. Fixed in `Util.ConnectStringParser.parseName`; all 43 existing `UtilTestCase`
  tests still pass.

Found and characterised, not fixed. Each is tracked as a GitHub issue and pinned by a test
that fails the day it is fixed:

- **[#146] Impala and Hive string literals do not escape backslashes**
  (`DialectQuotingPropertyTest`). Both dialects use backslash escaping but never escape a backslash
  in the value, so `\` becomes `'\'` — a literal that escapes its own closing quote and never
  terminates. **The more serious of the two quoting surfaces**: identifiers come from the schema, but
  string literals carry data (captions, key values read from the fact table), so this needs no
  privileged access. The shared base is not at fault — `Util.singleQuoteString` produces the same
  text, which is correct under standard SQL where backslash is not an escape.
- **[#138] Drill-through on an empty cell of a secondary hierarchy returns the entire fact table**
  (`DrillThroughAndConcurrencyPropertyTest`). `[Time].[Weekly].[1998]` has no value — FoodMart's
  fact data is 1997 — yet its drill-through count is **86,837**, which is exactly
  `SELECT COUNT(*) FROM "sales_fact_1997"`. Every row in the table, offered as "the rows behind"
  a blank cell. It is not a general over-count: the same situation on the *primary* time hierarchy,
  `[Time].[Time].[1998]`, correctly returns 0, as does the empty `[Store].[Stores].[Canada]`; and
  the Weekly hierarchy returns the correct 86,837 for `[1997]`, so the count works and it is the
  1998 constraint that is dropped rather than made unsatisfiable. `[Time].[Weekly]` is a *second*
  hierarchy on the Time dimension, which is what distinguishes it from the working cases. Drill-through
  exists to let a user verify a number; showing them the whole fact table instead of nothing defeats
  exactly that.
- **[#139] `Hierarchize(TopCount(...))` / `Hierarchize(BottomCount(...))` throw `UnsupportedOperationException`**
  (`MdxEngineMetamorphicPropertyTest`). "Top N in hierarchy order" is a bread-and-butter BI query and
  this is a crash, not a wrong number. Root cause traced to the line: `FunUtil.stablePartialSort`
  picks one of four algorithms by the ratio `n / size`, and the one selected for
  `0.05 < ratio ≤ 0.35` returns an anonymous `AbstractList` overriding only `get` and `size`, so the
  in-place sort inside `Hierarchize` throws. Confirmed against three levels and 22 values of `n`:
  21 matched the predicted ratio rule and the 22nd is the documented size-1 exemption
  (`hierarchizeMemberList` returns early for lists of length ≤ 1). **The data-dependence is why it has
  survived** — top 10 of 100 is a ratio of 0.1, squarely in the crashing band, so a report that works
  on a small dimension breaks when the dimension grows.
- **[#141] Native `TopCount(S, 0, M)` returns every member instead of none** (`MdxEngineMetamorphicPropertyTest`).
  Visible at source: `TopBottomCountFunDef.evaluateList` consults the native evaluator and returns
  *before* reaching its own `if (n == 0) return emptyList` guard, so that guard is dead whenever
  native evaluation applies. Not stale caching — running `n = 3` first in a fresh JVM behaves the
  same, and `RolapNativeTopCount.getCacheKey` does include the count.
- **[#137] `Filter(S, <constant true>)` silently drops members with no fact rows** (`MdxEngineMetamorphicPropertyTest`).
  Not the predicate (`1 = 1` references no measure), not one hierarchy (Store Country 3 → 1, Time
  Year 2 → 1, Store Type 6 → 5). It is native evaluation: with `EnableNativeFilter`,
  `EnableNativeCrossJoin`, `EnableNativeNonEmpty` and `EnableNativeTopCount` **all** off the answer
  is correct, and disabling any one of them is not enough. The query does not ask for `NON EMPTY`
  and all four flags ship on, so this is silently wrong output in the default configuration. The
  same property passes for every generated set once native evaluation is off, which is what
  localises it. **Highest-severity finding here.**
- **[#142] `Util.PropertyList.toString()` is not round-trip safe** (`UtilQuotingPropertyTest`). It quotes a
  value only when the value contains `;`, and then suppresses the delimiter if the value already
  starts or ends with `'` — logic its own source flags with a `REVIEW:` comment. `k` → `';x` reads
  back as `""`; `k` → `'` fails to parse; a trailing space is silently trimmed. Not fixed because
  `UtilTestCase.testParseConnectStringComplex` asserts the lossy output verbatim as the expected
  format, so a fix has to settle whether `toString()` is a serialisation or a display format.
- **[#143] `ArraySortedSet.merge` ignores view offsets** (`ArraySortedSetPropertyTest`). `subSet`/`headSet`/
  `tailSet` return a view sharing the backing array with a non-zero `start`, but `merge` reads from
  index 0 while bounding by `size()`. `tailSet("c").merge({e})` gives `[a, b, e]` instead of
  `[c, d, e]`. Whether it is live depends on whether `SegmentColumn.merge` — the one caller — can be
  handed a view.
- **[#144] `Format`'s "General Number" neither shows numbers as entered nor omits separators**
  (`FormatPropertyTest`). It groups (`1000` → `"1,000"`) and rounds to three decimal places
  (`0.5625` → `"0.562"`), against its own documented description. The existing `FormatTest` checks
  it against 6, -6, 0 and 0.6 — all under a thousand, all with at most one decimal — so neither
  deviation could ever have surfaced there.

Out of scope:

- `Util.parseIdentifier` throws on a quoted identifier containing **U+0000**. The parser is vendored
  olap4j, not Mondrian code, and XML 1.0 forbids the character, so no real schema reaches it. A full
  sweep confirmed NUL is the *only* excluded codepoint — all other 65,535 in the BMP round-trip
  exactly.

## Writing a new property

1. **Prefer an oracle when one exists.** `ArraySortedSetPropertyTest` compares against `TreeSet`
   and asserts nothing by hand. If the JDK already implements it correctly, defer to the JDK.
2. **Otherwise use a metamorphic relation.** For the query engine there is no oracle, so relate two
   queries instead: a no-op must be a no-op, a partition must recombine, a sort must permute.
3. **Bias the generator towards the characters and shapes that break things.** Unbiased Unicode
   almost never produces a `]`, so an escaping property over plain `text()` explores the escaping
   path approximately never and reports green forever. See `Generators.adversarialText()`.
4. **Make counterexamples readable.** The first failure this suite produced printed as `name = " "`
   and looked like a space; it was U+0000, and the raw rendering sent the investigation down the
   wrong path. Wrap calls that can throw and re-throw with the input escaped — see
   `UtilQuotingPropertyTest.parse`.
5. **State exclusions precisely.** `schemaName()` excludes exactly one codepoint and says why. A
   filter that quietly removes the hard cases is how a property test becomes decorative. When an
   open defect would otherwise fail everything, scope the **data** rather than the **functions** —
   `NativeEvaluationEquivalencePropertyTest` draws from fully-populated levels so `Filter` and
   `TopCount` stay under test, instead of excluding them and losing the two most-used set functions.
6. **Check the property is true before believing a failure.** Two properties here were wrong, not the
   product: `Util.isSorted` is documented as *strictly* sorted, so `[0, 0]` is correctly false; and
   "a restricted role's result is a subset of the unrestricted result of the same query" is false for
   every positional function — `Head(countries, 1)` is `[Canada]` unrestricted and `[USA]` under a
   role, both correct. Read the contract before filing the bug.
7. **Don't generalise a witness you haven't run.** A constructed
   `Hierarchize(Tail(BottomCount(.., 6), 4))` looked like it should crash by analogy with an observed
   counterexample; it does not. Pin the shape you actually saw fail.
