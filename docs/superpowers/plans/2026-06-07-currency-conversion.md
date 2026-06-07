# Currency Conversion (`<CurrencyConversion>`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `<CurrencyConversion>` measure-group element that produces a converted measure `SUM(measure × rate)`, joining a rate table to the fact with an interval band-join (`fact.date ∈ [valid_from, valid_to)`) + equi on currency + rate_type — picking the effective-date rate.

**Architecture:** Mirror the weighted bridge (#107): a `CurrencyConversionInfo` on `RolapMeasureGroup`, parsed/validated/registered at load; the Calcite path stitches the rate-table **band** join (the one net-new capability — `PlannerRequest.Join` gains a `BAND` kind + band columns; `CalciteSqlPlanner.build` gains a band-emission branch using `b.and/greaterThanOrEqual/lessThan`) and reuses `Measure.weighted`/`weightMeasures` for `× rate`. Fail-closed on the legacy backend (Calcite-only, like bridges). Round-trips through the M4 YAML converter pair. A spike already proved the band join unparses and runs on H2 (golden 1560).

**Tech Stack:** Mondrian-4 / XOM (`MondrianSchema.xml` → `MondrianDef`), Calcite `RelBuilder` + `RelToSqlConverter`, H2, JUnit 5.

---

## File structure
- `src/main/xom/mondrian/olap/MondrianSchema.xml` — `CurrencyConversion` + `CurrencyConversions` MeasureGroup child.
- `src/main/java/mondrian/rolap/RolapMeasureGroup.java` — `CurrencyConversionInfo` struct + registry (mirrors `BridgeInfo`).
- `src/main/java/mondrian/rolap/RolapSchemaLoader.java` — parse/validate/register + create the converted measure.
- `src/main/java/mondrian/calcite/PlannerRequest.java` — `JoinKind.BAND` + band fields on `Join` + factory.
- `src/main/java/mondrian/calcite/CalciteSqlPlanner.java` — band-join emission branch.
- `src/main/java/mondrian/calcite/CalcitePlannerAdapters.java` — `applyCurrencyConversion` (stitch band join + weight) + `touchesCurrencyConversion` detector.
- `src/main/java/mondrian/rolap/agg/SegmentLoader.java` — fail-closed gate (4 sites + parity skip).
- `src/main/java/mondrian/schema/yaml/m4/{M4CubeIngester,M4CubeBuilder}.java` — `currency_conversions` round-trip.
- `demo/{bank.sql,Bank.mondrian.xml,Bank.yaml}` — multi-currency fixture + example.
- Tests + saiku-cloud docs page.

> **Reference (read before starting):** the weighted bridge is the master template. Key anchors: `RolapMeasureGroup.BridgeInfo` (`:70-107`); loader `addBridgeLink` (`RolapSchemaLoader.java:2701-2811`); `PlannerRequest.Measure.weighted` (`:216-220`) + `Builder.weightMeasures` (`:724-737`); `CalciteSqlPlanner.build` join loop (`:734-772`); `CalcitePlannerAdapters.translateSegmentLoad` weighting (`:3497-3647`), `applyBridgeMemberGrant` (`:4334-4466`), `addChainEdge` (`:4763-4875`), `isBridgeMemberSecuredLoad` (`:4027-4047`); M4 converters `measureGroup`/`dimensionLink` (`M4CubeIngester.java:312-520`) and `buildMeasureGroup`/`buildDimensionLink` (`M4CubeBuilder.java:242-415`).

---

## Task 1: XOM `<CurrencyConversion>` element

**Files:** Modify `src/main/xom/mondrian/olap/MondrianSchema.xml`.

- [ ] **Step 1:** Add a `CurrencyConversions` wrapper + `CurrencyConversion` element. Insert AFTER the `DimensionLinks` holder block (after ~`:4115`). Model `CurrencyConversion` on the `BridgeLink` element (`:4214-4249`) but as a `MeasureGroupElement` item.

```xml
    <Element type="CurrencyConversions" class="Holder,MeasureGroupElement">
        <Doc>Collection of CurrencyConversion elements (#112 phase 3).</Doc>
        <Array name="array" type="CurrencyConversion"/>
        <Code>
            <![CDATA[
            private static final CurrencyConversions EMPTY;
            static {
                EMPTY = new CurrencyConversions();
                EMPTY.array = new CurrencyConversion[0];
            }
            public CurrencyConversions copy() {
                CurrencyConversions c = new CurrencyConversions();
                c.array = array.clone();
                return c;
            }
            public NamedList<CurrencyConversion> list() {
                return new NamedListImpl<CurrencyConversion>(
                    new MutableArrayList<CurrencyConversion>() {
                        protected CurrencyConversion[] getArray() {
                            return array == null ? EMPTY.array : array;
                        }
                        protected void setArray(CurrencyConversion[] ts) {
                            array = ts;
                        }
                    }
                );
            }
            ]]>
        </Code>
    </Element>

    <Element type="CurrencyConversion" class="NamedElement">
        <Doc>
            #112 phase 3: converts a measure to a reporting currency/unit via a
            rate table, picking the rate whose [valid_from,valid_to) interval
            contains the fact's effective date. Produces SUM(measure × rate).
            Requires the Calcite backend.
        </Doc>
        <Code><![CDATA[
        public String getNameAttribute() { return name; }
        ]]></Code>
        <Attribute name="name" required="true"><Doc>Converted measure name.</Doc></Attribute>
        <Attribute name="measure" required="true"><Doc>Base measure to convert.</Doc></Attribute>
        <Attribute name="rateTable" required="true"><Doc>Rate table.</Doc></Attribute>
        <Attribute name="rateColumn" required="true"><Doc>Rate (multiplier) column.</Doc></Attribute>
        <Attribute name="rateType" required="true"><Doc>Rate-type value to select.</Doc></Attribute>
        <Attribute name="rateTypeColumn" required="true"><Doc>Rate-type column on the rate table.</Doc></Attribute>
        <Attribute name="factCurrencyColumn" required="true"><Doc>Currency key on the fact.</Doc></Attribute>
        <Attribute name="rateCurrencyColumn" required="true"><Doc>Currency key on the rate table.</Doc></Attribute>
        <Attribute name="factDateColumn" required="true"><Doc>Effective-date column on the fact.</Doc></Attribute>
        <Attribute name="rateValidFromColumn" required="true"><Doc>Interval start (inclusive).</Doc></Attribute>
        <Attribute name="rateValidToColumn" required="true"><Doc>Interval end (exclusive).</Doc></Attribute>
        <Attribute name="formatString"><Doc>FORMAT_STRING for the converted measure.</Doc></Attribute>
    </Element>
```

- [ ] **Step 2:** Register `CurrencyConversions` as a MeasureGroup child. In the `MeasureGroup` `<Code>` block (`:4021-4040`), add `CurrencyConversions.class,` to the `Children<MeasureGroupElement>(...)` list and add the getter next to `getDimensionLinks()`:
```java
            public NamedList<CurrencyConversion> getCurrencyConversions() {
                return children.only(CurrencyConversions.EMPTY).list();
            }
```

- [ ] **Step 3:** Regenerate + verify.
Run: `mvn -q generate-sources 2>&1 | tail -3 && grep -nE "class CurrencyConversion |class CurrencyConversions |public String rateTable;|public String rateValidFromColumn;" target/generated-sources/xom/mondrian/olap/MondrianDef.java | head`
Then `mvn -q test-compile 2>&1 | grep -E "BUILD FAILURE|ERROR" | head`.
Expected: classes generated with all attribute fields; compiles. (If `NamedElement`/`getNameAttribute` is needed, it's already declared on the element above — mirrors `TimeCalc`/`CalculatedMember`.)

- [ ] **Step 4:** Commit: `git add src/main/xom/mondrian/olap/MondrianSchema.xml && git commit -m "feat(#112): CurrencyConversion XOM element"`

---

## Task 2: `CurrencyConversionInfo` on `RolapMeasureGroup`

**Files:** Modify `src/main/java/mondrian/rolap/RolapMeasureGroup.java`.

- [ ] **Step 1:** Add the struct + registry, mirroring `BridgeInfo` (`:65-107`). Place next to the bridge members.
```java
    /** #112 phase 3: a currency/unit conversion registered on this measure
     *  group — produces SUM(measure × rate) via a rate-table band join. */
    public static final class CurrencyConversionInfo {
        public final String convertedName;
        public final RolapStar.Measure baseMeasure;
        public final RolapSchema.PhysRelation rateTable;
        public final RolapSchema.PhysColumn rateColumn;
        public final RolapSchema.PhysColumn rateCurrencyColumn;
        public final RolapSchema.PhysColumn rateTypeColumn;
        public final RolapSchema.PhysColumn validFromColumn;
        public final RolapSchema.PhysColumn validToColumn;
        public final RolapSchema.PhysColumn factCurrencyColumn;
        public final RolapSchema.PhysColumn factDateColumn;
        public final String rateType;
        public CurrencyConversionInfo(
            String convertedName, RolapStar.Measure baseMeasure,
            RolapSchema.PhysRelation rateTable, RolapSchema.PhysColumn rateColumn,
            RolapSchema.PhysColumn rateCurrencyColumn,
            RolapSchema.PhysColumn rateTypeColumn,
            RolapSchema.PhysColumn validFromColumn,
            RolapSchema.PhysColumn validToColumn,
            RolapSchema.PhysColumn factCurrencyColumn,
            RolapSchema.PhysColumn factDateColumn, String rateType)
        {
            this.convertedName = convertedName;
            this.baseMeasure = baseMeasure;
            this.rateTable = rateTable;
            this.rateColumn = rateColumn;
            this.rateCurrencyColumn = rateCurrencyColumn;
            this.rateTypeColumn = rateTypeColumn;
            this.validFromColumn = validFromColumn;
            this.validToColumn = validToColumn;
            this.factCurrencyColumn = factCurrencyColumn;
            this.factDateColumn = factDateColumn;
            this.rateType = rateType;
        }
    }

    private final java.util.List<CurrencyConversionInfo> currencyConversions =
        new java.util.ArrayList<CurrencyConversionInfo>();

    public void addCurrencyConversion(CurrencyConversionInfo info) {
        currencyConversions.add(info);
    }
    public java.util.List<CurrencyConversionInfo> getCurrencyConversions() {
        return currencyConversions;
    }
    /** Whether this measure group has any currency conversion (Calcite-only). */
    public boolean hasCurrencyConversion() {
        return !currencyConversions.isEmpty();
    }
    /** The conversion whose converted measure has this name, or null. */
    public CurrencyConversionInfo currencyConversionByName(String name) {
        for (CurrencyConversionInfo i : currencyConversions) {
            if (i.convertedName.equals(name)) { return i; }
        }
        return null;
    }
```
(Confirm `RolapStar.Measure`, `RolapSchema.PhysRelation`, `RolapSchema.PhysColumn` are referenceable here — they are, used throughout this file.)

- [ ] **Step 2:** `mvn -q test-compile 2>&1 | grep -E "ERROR" | head` — expect none.
- [ ] **Step 3:** Commit: `git add src/main/java/mondrian/rolap/RolapMeasureGroup.java && git commit -m "feat(#112): CurrencyConversionInfo on RolapMeasureGroup"`

---

## Task 3: Loader — parse, validate, register, create the converted measure

**Files:** Modify `src/main/java/mondrian/rolap/RolapSchemaLoader.java`.

> Functional verification is the Task 10 integration test; this task wires registration. Read `addBridgeLink` (`:2701-2811`) and the measure-creation block (`:1789-1828`, `createMeasure` `:2398`) before editing.

- [ ] **Step 1:** Add a registration helper near `addBridgeLink` (~`:2811`). It resolves columns via `getPhysColumn(relation, name, xml, attr)` and the rate relation via `getPhysRelation(name, xml, attr)`, validates the base measure exists in `measureGroup.measureList`, registers the `CurrencyConversionInfo`, and creates a metadata-only converted `RolapBaseCubeMeasure`.
```java
    private void addCurrencyConversion(
        RolapSchema.PhysRelation fact,
        RolapMeasureGroup measureGroup,
        List<RolapMember> measureList,
        MondrianDef.CurrencyConversion xml)
    {
        RolapStar.Measure base = null;
        for (RolapMeasure m : measureGroup.measureList) {
            if (m.getName().equals(xml.measure)
                && m instanceof RolapBaseCubeMeasure)
            {
                base = ((RolapBaseCubeMeasure) m).getStarMeasure();
                break;
            }
        }
        if (base == null) {
            getHandler().error(
                "CurrencyConversion '" + xml.name + "': measure '"
                + xml.measure + "' not found in measure group '"
                + measureGroup.getName() + "'", xml, null);
            return;
        }
        RolapSchema.PhysRelation rate =
            getPhysRelation(xml.rateTable, xml, "rateTable");
        if (rate == null) { return; }
        RolapSchema.PhysColumn rateCol =
            getPhysColumn(rate, xml.rateColumn, xml, "rateColumn");
        RolapSchema.PhysColumn rateCcy =
            getPhysColumn(rate, xml.rateCurrencyColumn, xml, "rateCurrencyColumn");
        RolapSchema.PhysColumn rateType =
            getPhysColumn(rate, xml.rateTypeColumn, xml, "rateTypeColumn");
        RolapSchema.PhysColumn vFrom =
            getPhysColumn(rate, xml.rateValidFromColumn, xml, "rateValidFromColumn");
        RolapSchema.PhysColumn vTo =
            getPhysColumn(rate, xml.rateValidToColumn, xml, "rateValidToColumn");
        RolapSchema.PhysColumn factCcy =
            getPhysColumn(fact, xml.factCurrencyColumn, xml, "factCurrencyColumn");
        RolapSchema.PhysColumn factDate =
            getPhysColumn(fact, xml.factDateColumn, xml, "factDateColumn");
        if (rateCol == null || rateCcy == null || rateType == null
            || vFrom == null || vTo == null || factCcy == null
            || factDate == null)
        {
            return; // getPhysColumn already errored
        }
        measureGroup.addCurrencyConversion(
            new RolapMeasureGroup.CurrencyConversionInfo(
                xml.name, base, rate, rateCol, rateCcy, rateType,
                vFrom, vTo, factCcy, factDate, xml.rateType));

        // Register a metadata-only converted measure (same star column/agg as
        // the base; the × rate arithmetic is emitted in the Calcite path).
        RolapBaseCubeMeasure converted =
            createConvertedMeasure(measureGroup, xml, base);
        measureGroup.measureList.add(converted);
        measureList.add(converted);
    }
```
NOTE: the exact base-measure resolution + `createConvertedMeasure(...)` body depends on the `RolapBaseCubeMeasure`/`RolapStar.Measure` API. READ `createMeasure` (`:2398-2536`) and adapt: the converted measure shares the base's star column, aggregator (`sum`), datatype, and gets `xml.formatString` as its `FORMAT_STRING` larder property and `xml.name` as its name. If `getStarMeasure()` is not the accessor name, use the real one. If creating a `RolapBaseCubeMeasure` requires a `PhysColumn` expr, pass the base measure's column (the engine reads the metadata; the Calcite path overrides the SQL with `× rate`). Keep `createConvertedMeasure` a thin wrapper over the same construction `createMeasure` uses.

- [ ] **Step 2:** Dispatch `<CurrencyConversion>` during measure-group load. In the measure-group build (after the `<DimensionLinks>` loop ~`:1941`), add:
```java
        for (MondrianDef.CurrencyConversion xmlCc
            : xmlMeasureGroup.getCurrencyConversions())
        {
            addCurrencyConversion(fact, measureGroup, measureList, xmlCc);
        }
```
Match the real variable names at that site (`xmlMeasureGroup`, `fact`, `measureGroup`, `measureList`) by reading the surrounding code.

- [ ] **Step 3:** `mvn -q test-compile 2>&1 | grep -E "ERROR" | head` — expect none. (If a referenced accessor/constructor differs, STOP and report NEEDS_CONTEXT with the real signature from `createMeasure`.)
- [ ] **Step 4:** Commit: `git add src/main/java/mondrian/rolap/RolapSchemaLoader.java && git commit -m "feat(#112): load <CurrencyConversion> (register + converted measure)"`

---

## Task 4: `PlannerRequest` — `BAND` join kind + band fields

**Files:** Modify `src/main/java/mondrian/calcite/PlannerRequest.java`. Test: covered by Task 5's unit test.

- [ ] **Step 1:** Extend `JoinKind` (`:489`) to `INNER, CROSS, BAND`.
- [ ] **Step 2:** Add band fields + a factory to `Join` (`:491-575`). The band carries: the dim (rate) table alias + physName, plus the fact/rate column names for currency (equi), rate_type (equi-to-literal), and the date band (from/to). Add fields:
```java
        /** #112 band join: non-null marks a currency-conversion rate join. */
        public final String factCurrencyKey;   // fact currency column
        public final String rateCurrencyCol;   // rate currency column
        public final String factDateKey;       // fact date column
        public final String validFromCol;      // rate interval start
        public final String validToCol;        // rate interval end
        public final String rateTypeCol;       // rate-type column
        public final String rateTypeValue;     // the literal rate-type to match
```
and a factory:
```java
        public static Join band(
            String rateTable, String physName,
            String factCurrencyKey, String rateCurrencyCol,
            String factDateKey, String validFromCol, String validToCol,
            String rateTypeCol, String rateTypeValue)
        {
            return new Join(rateTable, physName, factCurrencyKey, rateCurrencyCol,
                factDateKey, validFromCol, validToCol, rateTypeCol, rateTypeValue);
        }
```
Add a private constructor that sets `kind = JoinKind.BAND`, `dimTable = rateTable`, `physName`, the seven band fields, and leaves the equi fields (`factKey`, `dimKey`, `leftTable`) null. Keep the existing constructors initializing the new fields to null. READ the existing `Join` constructors (`:520-544`) and add the band fields to each as `null` to keep them final.

- [ ] **Step 3:** `mvn -q test-compile 2>&1 | grep -E "ERROR" | head` — expect none.
- [ ] **Step 4:** Commit: `git add src/main/java/mondrian/calcite/PlannerRequest.java && git commit -m "feat(#112): PlannerRequest BAND join kind + band fields"`

---

## Task 5: `CalciteSqlPlanner.build` — band-join emission

**Files:** Modify `src/main/java/mondrian/calcite/CalciteSqlPlanner.java`. Test: `src/test/java/mondrian/calcite/CurrencyBandJoinTest.java`.

- [ ] **Step 1: Write the failing test** — build a `PlannerRequest` with a band `Join` over two scanned tables and assert the unparsed SQL contains the band condition. Model the request on the spike (which proved the SQL shape). Use the project's planner over a tiny H2-backed schema OR assert the generated SQL string. Minimal version asserting the emission:
```java
/* licence header */
package mondrian.calcite;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CurrencyBandJoinTest {
    @Test public void bandJoinEmitsInequalityCondition() {
        PlannerRequest.Builder b = new PlannerRequest.Builder("REVENUE");
        b.addMeasure(new PlannerRequest.Measure(
            PlannerRequest.AggFn.SUM,
            new PlannerRequest.Column("REVENUE", "AMOUNT"), "amt",
            false, null, null, null, null));
        b.addJoin(PlannerRequest.Join.band(
            "FX_RATE", "FX_RATE",
            "CURRENCY_ID", "CURRENCY_ID",
            "MONTH_KEY", "VALID_FROM", "VALID_TO",
            "RATE_TYPE", "ECB"));
        // weight the measure by the rate column so it becomes SUM(amt × rate)
        b.weightMeasures(new PlannerRequest.Column("FX_RATE", "RATE"));
        String sql = CalciteSqlPlannerTestHelper.planToSql(b.build());
        assertTrue(sql.contains(">=") && sql.contains("<")
            && sql.toUpperCase().contains("RATE_TYPE"), sql);
    }
}
```
NOTE: the exact `Builder`/`Measure` constructor args + a `planToSql` helper must match the real API — READ `PlannerRequest.Builder` (`:681+`) and `CalciteSqlPlanner` for how an existing test plans a request to SQL (look at `SqlCaptureTest`/`HarnessPlanSnapshotTest` for the pattern). If there's no easy standalone `planToSql`, instead write this as an end-to-end test deferred to Task 10 and make Task 5 a pure compile+manual-verify of the emission branch. Prefer reusing an existing planner-test harness.

- [ ] **Step 2:** Run → fails (BAND not emitted / compile gap).

- [ ] **Step 3:** Add the BAND branch to the join loop (`:734-772`), after the `CROSS` check, before the equi `else`:
```java
            if (j.kind == PlannerRequest.JoinKind.BAND) {
                b.join(JoinRelType.INNER, b.and(
                    b.equals(
                        b.field(2, 0, j.factCurrencyKey),
                        b.field(2, j.dimTable, j.rateCurrencyCol)),
                    b.equals(
                        b.field(2, j.dimTable, j.rateTypeCol),
                        b.literal(j.rateTypeValue)),
                    b.greaterThanOrEqual(
                        b.field(2, 0, j.factDateKey),
                        b.field(2, j.dimTable, j.validFromCol)),
                    b.lessThan(
                        b.field(2, 0, j.factDateKey),
                        b.field(2, j.dimTable, j.validToCol))));
            } else if (j.kind == PlannerRequest.JoinKind.CROSS) {
                b.join(JoinRelType.INNER, b.literal(true));
            } else {
                // ... existing equi branch unchanged ...
            }
```
(`b.greaterThanOrEqual`/`b.lessThan`/`b.and` are confirmed-available RelBuilder idioms in this file, `:1227-1231`. The band uses the spike's proven shape.)

- [ ] **Step 4:** Run → passes. (If the standalone harness is impractical, verify via Task 10 and keep this task's change + a compile check, noting the deferral.)
- [ ] **Step 5:** Commit: `git add -A && git commit -m "feat(#112): emit currency band join in CalciteSqlPlanner"`

---

## Task 6: `CalcitePlannerAdapters` — apply the conversion in the translate path

**Files:** Modify `src/main/java/mondrian/calcite/CalcitePlannerAdapters.java`.

> This is the integration task — JUDGMENT REQUIRED. Use the bridge-weighted path as the template: `translateSegmentLoad` weighting (`:3497-3647`), `applyBridgeMemberGrant` forced-join+weight (`:4334-4466`), `addChainEdge` join-stitching (`:4763-4875`), `touchedMeasureGroups` (`:4165`). Target SQL = the spike's `SUM(amount × rate)` with the band join.

- [ ] **Step 1:** Add a `touchesCurrencyConversion(List<Segment>, RolapStar)` detector mirroring `isBridgeMemberSecuredLoad` (`:4027-4047`) but checking `mg.hasCurrencyConversion()` (no role check):
```java
    public static boolean touchesCurrencyConversion(
        List<Segment> segments, RolapStar star)
    {
        for (RolapMeasureGroup mg : touchedMeasureGroups(segments, star)) {
            if (mg.hasCurrencyConversion()) { return true; }
        }
        return false;
    }
```

- [ ] **Step 2:** In `translateSegmentLoad`, when a requested measure is a measure group's converted measure (`mg.currencyConversionByName(measureName) != null`), apply the conversion: (a) add the band `Join` to the builder describing the rate table + the fact/rate columns (resolve their `relation.getAlias()`/`name` like `weightCol` is resolved at `:3547-3553`), and (b) `b.weightMeasures(new PlannerRequest.Column(rateTable.getAlias(), rateColumn.name))` so the converted measure becomes `SUM(operand × rate)`. Build the band Join via `PlannerRequest.Join.band(rateTable.getAlias(), rateTable's physName, factCurrencyCol.name, rateCurrencyCol.name, factDateCol.name, validFromCol.name, validToCol.name, rateTypeCol.name, info.rateType)`. The fact-side column names come from `info.factCurrencyColumn`/`info.factDateColumn`; the rate-side from the `info` rate columns; the alias is the rate table's alias (ensure the rate table scans under that alias — `j.physName` carries the physical name, `j.dimTable` the alias, mirroring the equi join at `:735-744`).

   Extract a focused helper:
```java
    private static void applyCurrencyConversion(
        PlannerRequest.Builder b,
        RolapMeasureGroup mg,
        RolapMeasureGroup.CurrencyConversionInfo info)
    {
        String rateAlias = info.rateTable.getAlias();
        b.addJoin(PlannerRequest.Join.band(
            rateAlias, info.rateTable.getAlias(),
            colName(info.factCurrencyColumn), colName(info.rateCurrencyColumn),
            colName(info.factDateColumn),
            colName(info.validFromColumn), colName(info.validToColumn),
            colName(info.rateTypeColumn), info.rateType));
        b.weightMeasures(new PlannerRequest.Column(
            rateAlias, colName(info.rateColumn)));
    }
```
where `colName(PhysColumn)` returns the real column name (use the existing pattern — `PhysRealColumn.name`). READ how `weightCol` is built at `:3547-3553` and how the bridge join physName/alias are set in `addChainEdge` (`:4865-4874`) to get the alias/physName exactly right (the rate table must `scan(physName).as(alias)`).

   The wiring point: find where `translateSegmentLoad` iterates the load's measures and adds them to the builder (`:3640-3647`); when the measure is a converted measure, call `applyCurrencyConversion(b, mg, info)` once (guard `!b.hasWeightedMeasure()` to avoid double-weighting) and add the base operand measure. Because `weightMeasures` multiplies ALL measures, a load mixing a converted measure with a plain measure of the same group must be split (the engine already issues homogeneous per-measure segments in the bridge/distinct-grain cases — verify the same holds, or fail-closed via `UnsupportedTranslation` for a mixed load, mirroring `applyMeasureLevelDistinctGrain`).

- [ ] **Step 3:** `mvn -q test-compile` clean. Functional proof is Task 10.
- [ ] **Step 4:** Commit: `git add -A && git commit -m "feat(#112): apply currency conversion (band join + rate weighting) in translate path"`

> If the measure-routing in `translateSegmentLoad` is unclear after reading, STOP and report NEEDS_CONTEXT with the exact measure-iteration code found — do not guess the wiring.

---

## Task 7: `SegmentLoader` fail-closed gate (legacy backend)

**Files:** Modify `src/main/java/mondrian/rolap/agg/SegmentLoader.java`.

- [ ] **Step 1:** At each of the 4 fail-closed sites (`:243-247`, `:276`, `:824-829`, `:920-925`) where `securedLoad` is computed as `isPredicateSecuredLoad(...) || isBridgeMemberSecuredLoad(...)`, OR-in `|| CalcitePlannerAdapters.touchesCurrencyConversion(<segs>, star)`. Use the same segment-list variable each site already uses. At the backend-agnostic gate (`:824`) and worker gate (`:920`) likewise extend the condition. Update the thrown message to mention "or currency conversion" so it's actionable.
- [ ] **Step 2:** Also add `&& !CalcitePlannerAdapters.touchesCurrencyConversion(...)` to the parity-guard skip (`:1004-1006`) — the legacy comparison would emit `SUM(measure)` without the rate join and falsely diverge.
- [ ] **Step 3:** `mvn -q test-compile` clean.
- [ ] **Step 4:** Commit: `git add -A && git commit -m "fix(#112): fail-closed currency-converted loads on the legacy backend"`

---

## Task 8: M4 YAML round-trip (`currency_conversions`)

**Files:** Modify `src/main/java/mondrian/schema/yaml/m4/{M4CubeIngester,M4CubeBuilder}.java`. Test: `src/test/java/mondrian/schema/yaml/CurrencyConversionRoundTripTest.java`.

- [ ] **Step 1: Failing round-trip test:**
```java
/* licence header */
package mondrian.schema.yaml;

import mondrian.schema.yaml.m4.M4XmlToYaml;
import mondrian.schema.yaml.m4.M4YamlToXml;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CurrencyConversionRoundTripTest {
    private static final String XML =
        "<Schema name='CC' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='f'/><Table name='fx'/></PhysicalSchema>\n"
        + "  <Cube name='C'>\n    <Dimensions/>\n"
        + "    <MeasureGroups><MeasureGroup name='M' table='f'>\n"
        + "      <Measures><Measure name='Amount' column='amt' aggregator='sum'/></Measures>\n"
        + "      <CurrencyConversions>\n"
        + "        <CurrencyConversion name='Amount (USD)' measure='Amount'\n"
        + "          rateTable='fx' rateColumn='rate' rateType='ECB' rateTypeColumn='rate_type'\n"
        + "          factCurrencyColumn='ccy' rateCurrencyColumn='ccy'\n"
        + "          factDateColumn='dt' rateValidFromColumn='vf' rateValidToColumn='vt'/>\n"
        + "      </CurrencyConversions>\n"
        + "      <DimensionLinks/>\n"
        + "    </MeasureGroup></MeasureGroups>\n  </Cube>\n</Schema>\n";

    @Test public void roundTrips() {
        String yaml = M4XmlToYaml.toYaml(XML);
        assertTrue(yaml.contains("currency_conversions:"), yaml);
        assertTrue(yaml.contains("rate_table: \"fx\""), yaml);
        String xml2 = M4YamlToXml.toXml(yaml);
        assertTrue(xml2.contains("<CurrencyConversion"), xml2);
        assertTrue(xml2.contains("rateValidFromColumn=\"vf\""), xml2);
    }
}
```
Run → fails.

- [ ] **Step 2:** Emit — `M4CubeIngester.measureGroup(...)` (`:312-350`): add a `CurrencyConversions` dispatch arm in the `childArray` loop → `out.put("currency_conversions", currencyConversions((MondrianDef.CurrencyConversions) ce))`, plus the mappers (mirror `dimensionLink` `:492-517` snake_case + non-null rule):
```java
    private static List<Object> currencyConversions(
        MondrianDef.CurrencyConversions w) {
        List<Object> out = new ArrayList<>();
        if (w.array != null) {
            for (MondrianDef.CurrencyConversion cc : w.array) {
                out.add(currencyConversion(cc));
            }
        }
        return out;
    }
    private static Map<String, Object> currencyConversion(
        MondrianDef.CurrencyConversion cc) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", cc.name);
        o.put("measure", cc.measure);
        o.put("rate_table", cc.rateTable);
        o.put("rate_column", cc.rateColumn);
        o.put("rate_type", cc.rateType);
        o.put("rate_type_column", cc.rateTypeColumn);
        o.put("fact_currency_column", cc.factCurrencyColumn);
        o.put("rate_currency_column", cc.rateCurrencyColumn);
        o.put("fact_date_column", cc.factDateColumn);
        o.put("rate_valid_from_column", cc.rateValidFromColumn);
        o.put("rate_valid_to_column", cc.rateValidToColumn);
        if (cc.formatString != null) { o.put("format_string", cc.formatString); }
        return o;
    }
```

- [ ] **Step 3:** Consume — `M4CubeBuilder.buildMeasureGroup(...)` (`:258-269`): add
```java
        Object ccs = m.get("currency_conversions");
        if (ccs instanceof List && !((List<?>) ccs).isEmpty()) {
            kids.add(buildCurrencyConversions((List<?>) ccs));
        }
```
and the builders:
```java
    private static MondrianDef.CurrencyConversions buildCurrencyConversions(
        List<?> list) {
        MondrianDef.CurrencyConversions w = new MondrianDef.CurrencyConversions();
        List<MondrianDef.CurrencyConversion> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add(buildCurrencyConversion((Map<?, ?>) item));
            }
        }
        w.array = out.toArray(new MondrianDef.CurrencyConversion[0]);
        return w;
    }
    private static MondrianDef.CurrencyConversion buildCurrencyConversion(
        Map<?, ?> m) {
        MondrianDef.CurrencyConversion cc = new MondrianDef.CurrencyConversion();
        cc.name = M4YamlToXml.str(m.get("name"));
        cc.measure = M4YamlToXml.str(m.get("measure"));
        cc.rateTable = M4YamlToXml.str(m.get("rate_table"));
        cc.rateColumn = M4YamlToXml.str(m.get("rate_column"));
        cc.rateType = M4YamlToXml.str(m.get("rate_type"));
        cc.rateTypeColumn = M4YamlToXml.str(m.get("rate_type_column"));
        cc.factCurrencyColumn = M4YamlToXml.str(m.get("fact_currency_column"));
        cc.rateCurrencyColumn = M4YamlToXml.str(m.get("rate_currency_column"));
        cc.factDateColumn = M4YamlToXml.str(m.get("fact_date_column"));
        cc.rateValidFromColumn = M4YamlToXml.str(m.get("rate_valid_from_column"));
        cc.rateValidToColumn = M4YamlToXml.str(m.get("rate_valid_to_column"));
        cc.formatString = M4YamlToXml.str(m.get("format_string"));
        return cc;
    }
```

- [ ] **Step 4:** Run → passes. Commit: `git add -A && git commit -m "feat(#112): round-trip <CurrencyConversion> through M4 YAML converter + test"`

---

## Task 9: Fixture + Bank example

**Files:** Modify `demo/bank.sql`, `demo/Bank.mondrian.xml`, regenerate `demo/Bank.yaml`.

- [ ] **Step 1:** `demo/bank.sql` — add `currency_id` to `mm_monthly` and a `fx_rate` table. Add `DROP TABLE IF EXISTS "fx_rate";` near the others. Change the `mm_monthly` CREATE to include `"currency_id" VARCHAR(8)` and the INSERTs to add `'EUR'`:
```sql
CREATE TABLE "mm_monthly" (
    "month_key" INTEGER,
    "currency_id" VARCHAR(8),
    "revenue"   INTEGER
);
INSERT INTO "mm_monthly" ("month_key","currency_id","revenue") VALUES
    (202401,'EUR',100),(202402,'EUR',200),(202403,'EUR',300),
    (202501,'EUR',150),(202502,'EUR',250),(202503,'EUR',350);
CREATE TABLE "fx_rate" (
    "currency_id" VARCHAR(8), "rate_type" VARCHAR(8),
    "valid_from" INTEGER, "valid_to" INTEGER, "rate" DECIMAL(8,4)
);
-- EUR→USD: 1.10 across 2024, 1.20 across 2025 (non-overlapping intervals).
INSERT INTO "fx_rate" VALUES
    ('EUR','ECB',202401,202500,1.10),
    ('EUR','ECB',202501,202600,1.20);
-- Golden: Revenue (USD) = (100+200+300)*1.10 + (150+250+350)*1.20 = 660+900 = 1560
```
Register `<Table name="fx_rate"/>` in `Bank.mondrian.xml`'s `<PhysicalSchema>`.
Verify load: `java -cp "$(find ~/.m2 -name 'h2-1.4.188.jar' | head -1)" org.h2.tools.RunScript -url "jdbc:h2:mem:c;DB_CLOSE_DELAY=-1" -user sa -script demo/bank.sql && echo OK`.

- [ ] **Step 2:** Add the `<CurrencyConversions>` to the `Monthly Revenue` measure group in `Bank.mondrian.xml` (inside `<MeasureGroup name="Revenue" table="mm_monthly">`, after `<Measures>`):
```xml
        <CurrencyConversions>
          <CurrencyConversion name="Revenue (USD)" measure="Revenue"
              rateTable="fx_rate" rateColumn="rate"
              rateType="ECB" rateTypeColumn="rate_type"
              factCurrencyColumn="currency_id" rateCurrencyColumn="currency_id"
              factDateColumn="month_key"
              rateValidFromColumn="valid_from" rateValidToColumn="valid_to"
              formatString="#,##0.00"/>
        </CurrencyConversions>
```

- [ ] **Step 3:** Smoke: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#bridgeAndStatsGoldenNumbers' -DfailIfNoTests=false` then read the report — existing Bank cubes (incl. the YAML round-trip) must stay green. (The `mm_monthly` schema change is additive; Phase 2's `Revenue` golden numbers are unaffected.)
- [ ] **Step 4:** Regenerate Bank.yaml: `CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime 2>/dev/null | tail -1)"; java -cp "$CP" mondrian.schema.yaml.SchemaCli to-yaml demo/Bank.mondrian.xml -o demo/Bank.yaml; grep -c currency_conversions demo/Bank.yaml` (expect ≥1).
- [ ] **Step 5:** Commit: `git add demo/bank.sql demo/Bank.mondrian.xml demo/Bank.yaml && git commit -m "demo(#112): multi-currency fixture + Revenue (USD) conversion"`

---

## Task 10: Integration test (golden + interval boundary + fail-closed)

**Files:** Create `src/test/java/mondrian/calcite/CurrencyConversionH2EndToEndTest.java`. Model on `TimeIntelligenceH2EndToEndTest` (RUNSCRIPT load, XML + YAML forms).

- [ ] **Step 1: Write the test** — assert the converted grand total + the per-year interval split + fail-closed cases:
```java
/* licence header — package mondrian.calcite */
// boot(): RUNSCRIPT demo/bank.sql; xmlSchema = read demo/Bank.mondrian.xml;
// yamlSchema = M4YamlToXml.toXml(M4XmlToYaml.toYaml(xmlSchema)); connect() as in
// TimeIntelligenceH2EndToEndTest.

    @ParameterizedTest @ValueSource(strings = {"xml", "yaml"})
    public void convertedGrandTotalUsesPerIntervalRate(String form) {
        Connection c = connect(form);
        try {
            // (100+200+300)*1.10 + (150+250+350)*1.20 = 660 + 900 = 1560
            assertEquals(1560.0, scalar(c,
                "SELECT {[Measures].[Revenue (USD)]} ON COLUMNS"
                + " FROM [Monthly Revenue]"), 0.001,
                "converted total picks 1.10 for 2024, 1.20 for 2025");
            // The local measure is unchanged (Phase 2): 1350.
            assertEquals(1350.0, scalar(c,
                "SELECT {[Measures].[Revenue]} ON COLUMNS FROM [Monthly Revenue]"),
                0.001, "base Revenue unchanged");
        } finally { c.close(); clearCache(); }
    }

    @ParameterizedTest @ValueSource(strings = {"xml", "yaml"})
    public void intervalBoundaryPicksTheRightRate(String form) {
        Connection c = connect(form);
        try {
            // 2024 Q1 revenue 600 × 1.10 = 660 (a member on the Year level).
            assertEquals(660.0, scalar(c,
                "SELECT {[Measures].[Revenue (USD)]} ON COLUMNS,"
                + " {[Calendar].[Calendar].[2024]} ON ROWS"
                + " FROM [Monthly Revenue]"), 0.001, "2024 @ 1.10");
            assertEquals(900.0, scalar(c,
                "SELECT {[Measures].[Revenue (USD)]} ON COLUMNS,"
                + " {[Calendar].[Calendar].[2025]} ON ROWS"
                + " FROM [Monthly Revenue]"), 0.001, "2025 @ 1.20");
        } finally { c.close(); clearCache(); }
    }

    @Test public void unknownBaseMeasureFailsClosed() {
        // a CurrencyConversion measure='Nope' must error at load.
        String bad = xmlSchema.replace("measure=\"Revenue\"\n              rateTable",
            "measure=\"Nope\"\n              rateTable");
        org.junit.jupiter.api.Assertions.assertNotEquals(xmlSchema, bad);
        assertThrows(RuntimeException.class, () -> {
            Connection c = connectCatalog(bad);
            c.execute(c.parseQuery(
                "SELECT {[Measures].[Revenue]} ON COLUMNS FROM [Monthly Revenue]"));
        });
    }

    @Test public void legacyBackendRefusesConvertedLoad() {
        String prior = System.getProperty("mondrian.backend");
        System.setProperty("mondrian.backend", "legacy");
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        try {
            Connection c = connect("xml");
            final Connection fc = c;
            assertThrows(RuntimeException.class, () -> fc.execute(fc.parseQuery(
                "SELECT {[Measures].[Revenue (USD)]} ON COLUMNS"
                + " FROM [Monthly Revenue]")),
                "currency-converted load must fail closed on legacy backend");
            c.close();
        } finally {
            if (prior == null) System.clearProperty("mondrian.backend");
            else System.setProperty("mondrian.backend", prior);
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
```
Implement the harness helpers (`boot`, `connect`, `connectCatalog`, `scalar`, `clearCache`) copied from `TimeIntelligenceH2EndToEndTest`.

- [ ] **Step 2:** Run; PROBE-THEN-PIN member paths (`[Calendar].[Calendar].[2024]` etc.) against the real unique names exactly as the Phase 2 test did; pin the `xmlSchema.replace(...)` substring to the real `Bank.mondrian.xml` text. Golden 1560/660/900 are correct by construction — if a value is wrong it's a real bug (report DONE_WITH_CONCERNS, do not weaken).
- [ ] **Step 3:** Commit: `git add -A && git commit -m "test(#112): currency conversion golden + interval + fail-closed (XML & YAML)"`

---

## Task 11: Parity + regression sweep

- [ ] **Step 1:** `mvn -q test -Dtest='BankYamlParityTest' -DfailIfNoTests=false` — green (regenerated Bank.yaml matches CLI + loads).
- [ ] **Step 2:** Sweep: `mvn -q test -Dtest='CurrencyConversionRoundTripTest,CurrencyBandJoinTest,CurrencyConversionH2EndToEndTest,BankShowcaseH2EndToEndTest,BankYamlParityTest,TimeIntelligenceH2EndToEndTest,Bridge*,mondrian.schema.yaml.**' -DfailIfNoTests=false` then inspect `target/surefire-reports/*.txt`. Expected: 0 failures/errors. If `FoodMartYamlSmokeCorpusEquivalenceTest` or any committed `*.yaml` drifts, regenerate it via SchemaCli and commit.
- [ ] **Step 3:** Commit any regenerated artifacts.

---

## Task 12: Docs-site page (saiku-cloud)

**Files:** Create a "Currency conversion" page in `/Users/tombarber/Projects/saiku/saiku-cloud/docs-site` matching the `time-intelligence.mdx`/`advanced.mdx` style (Astro Starlight; `<Tabs syncKey="schema-format">` XML/YAML, `<Aside>`; register in `mondrian/index.mdx`).

- [ ] **Step 1:** Write the page: concept (declare conversion vs hand-written calc member), the attribute table, the **interval/effective-date data contract** (non-overlapping `valid_from`/`valid_to`; a fact date with no interval drops from the inner join), the Calcite-backend requirement + fail-closed behaviour, and the worked Bank example (XML + YAML + the 1.10/1.20 rates and the 1560 golden total). Link from the section index.
- [ ] **Step 2:** Commit on a branch in saiku-cloud: `cd /Users/tombarber/Projects/saiku/saiku-cloud && git checkout -b docs/currency-conversion && git add <page + index> && git commit -m "docs: currency conversion (<CurrencyConversion>)"`. Do NOT push (the controller will). Report the path + branch.

---

## Task 13: Push + PR

- [ ] **Step 1:** `cd /Users/tombarber/Projects/saiku/mondrian-saiku && git push -u origin feat/currency-conversion`
- [ ] **Step 2:** `gh pr create --base develop --head feat/currency-conversion --title "Currency conversion (<CurrencyConversion>) — #112 phase 3" --body "..."` summarising: `<CurrencyConversion>` measure-group element → `SUM(measure × rate)` via an interval band-join (effective-date as-of), fail-closed on legacy, M4 YAML round-trip, Bank multi-currency fixture (golden 1560), tests + docs (separate saiku-cloud PR). Note the band-join spike that de-risked it.

---

## Self-review notes
- **Spec coverage:** element (T1), info struct (T2), loader (T3), band join (T4/T5), translate wiring (T6), fail-closed (T7), round-trip (T8), fixture (T9), integration incl. interval + fail-closed XML&YAML (T10), parity (T11), docs (T12), PR (T13). ✅
- **Type/name consistency:** `CurrencyConversionInfo` fields, `JoinKind.BAND` + band field names, `currency_conversions`/snake_case keys, `touchesCurrencyConversion`, `applyCurrencyConversion`, golden 1560/660/900 — consistent across tasks.
- **Hardest / judgment tasks:** T6 (translate-path wiring) — flagged JUDGMENT REQUIRED with a NEEDS_CONTEXT escape; T5 harness may defer functional proof to T10 if no standalone `planToSql` exists. The band-join SQL shape is proven by the spike.
- **Known unknowns (probe-then-pin, not guessed):** `RolapBaseCubeMeasure`/`getStarMeasure` exact API (T3 reads `createMeasure`); the `Builder`/`Measure` constructor arity for T5's test (read `PlannerRequest`); Calendar member unique names + the replace substring (T10); whether a mixed converted+plain load needs splitting (T6 — mirror `applyMeasureLevelDistinctGrain`).
- **Only the M4 converter pair** handles measure groups (the CLI delegates for M4 schemas), so T8 covers both the showcase test and the CLI-generated Bank.yaml.
