# Declarative Time-Intelligence (`<TimeCalc>`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `<TimeCalc type="yoy|pop|ytd|rolling">` schema element that desugars at load into a validated `<CalculatedMember>` on `[Measures]`, so authors declare time-intelligence metrics instead of hand-writing MDX.

**Architecture:** A new XOM `TimeCalc` cube child (auto-generates `MondrianDef.TimeCalc`). A pure `TimeCalcDesugarer` turns `(type, names, window, function)` into an MDX formula string. `RolapSchemaLoader` resolves the cube's typed Time dimension, validates each `<TimeCalc>`, desugars it to a `MondrianDef.CalculatedMember`, and feeds it through the existing `createCalcMembersAndNamedSets` pipeline (bad formulas fail loudly at load). `<TimeCalc>` round-trips through both YAML converter pairs. A monthly-revenue fixture in the Bank demo carries worked examples; unit + integration (XML & YAML) + parity tests + a docs-site page cover it.

**Tech Stack:** Mondrian 4 / XOM model (`MondrianSchema.xml` → generated `MondrianDef.java` via `xomgen` at generate-sources), MDX (`ParallelPeriod`/`Ytd`/`LastPeriods`/`Aggregate`/`Avg`), H2, JUnit 5.

---

## File structure

- `src/main/xom/mondrian/olap/MondrianSchema.xml` — **modify**: `TimeCalc` + `TimeCalcs` elements; register in `Cube` children + `getTimeCalcs()`.
- `src/main/java/mondrian/rolap/TimeCalcDesugarer.java` — **create**: pure type+names→MDX formula + validation enum.
- `src/main/java/mondrian/rolap/RolapSchemaLoader.java` — **modify**: desugar `<TimeCalc>` at `~:2061`.
- `src/main/java/mondrian/schema/yaml/XmlSchemaToYaml.java`, `YamlSchemaConverter.java` — **modify**: round-trip `time_calcs` (CLI pair).
- `src/main/java/mondrian/schema/yaml/m4/M4CubeIngester.java`, `M4CubeBuilder.java` — **modify**: round-trip `time_calcs` (M4 pair).
- `demo/bank.sql`, `demo/Bank.mondrian.xml`, `demo/Bank.yaml` — **modify/regenerate**: monthly fixture + `Monthly Revenue` cube.
- `src/test/java/mondrian/rolap/TimeCalcDesugarerTest.java` — **create**: unit.
- `src/test/java/mondrian/calcite/TimeIntelligenceH2EndToEndTest.java` — **create**: integration (XML+YAML+validation).
- `src/test/java/mondrian/schema/yaml/TimeCalcRoundTripTest.java` — **create**: both converter pairs round-trip.
- saiku-cloud docs-site — **create**: "Time intelligence" page.

---

## Task 1: XOM model — add the `<TimeCalc>` element

**Files:**
- Modify: `src/main/xom/mondrian/olap/MondrianSchema.xml` (Cube children block ~914-957; add new elements near `CalculatedMembers` ~1784-1815)

- [ ] **Step 1: Add the `TimeCalcs` wrapper + `TimeCalc` element.** Insert immediately AFTER the `CalculatedMembers` `<Element>` block (after line ~1815). Mirror the `CalculatedMembers` EMPTY/list() pattern exactly.

```xml
    <Element type="TimeCalcs" class="Holder,CubeElement">
        <Doc>
            Collection of TimeCalc elements (#112 declarative time intelligence).
        </Doc>
        <Array name="array" type="TimeCalc"/>
        <Code>
            <![CDATA[
            private static final TimeCalcs EMPTY;
            static {
                EMPTY = new TimeCalcs();
                EMPTY.array = new TimeCalc[0];
            }
            public TimeCalcs copy() {
                TimeCalcs c = new TimeCalcs();
                c.array = array.clone();
                return c;
            }
            public NamedList<TimeCalc> list() {
                return new NamedListImpl<TimeCalc>(
                    new MutableArrayList<TimeCalc>() {
                        protected TimeCalc[] getArray() {
                            return array == null ? EMPTY.array : array;
                        }
                        protected void setArray(TimeCalc[] ts) {
                            array = ts;
                        }
                    }
                );
            }
            ]]>
        </Code>
    </Element>

    <Element type="TimeCalc">
        <Doc>
            A declarative time-intelligence metric (#112). Desugars at load into
            a calculated member on [Measures].
        </Doc>
        <Attribute name="name" required="true">
            <Doc>Name of the generated calculated member.</Doc>
        </Attribute>
        <Attribute name="type" required="true">
            <Doc>One of yoy, pop, ytd, rolling.</Doc>
            <Value>yoy</Value>
            <Value>pop</Value>
            <Value>ytd</Value>
            <Value>rolling</Value>
        </Attribute>
        <Attribute name="measure" required="true">
            <Doc>The base measure this metric is computed from.</Doc>
        </Attribute>
        <Attribute name="timeDimension">
            <Doc>
                The Time dimension to navigate. Defaults to the cube's single
                dimension of type="TIME"; required if there is more than one.
            </Doc>
        </Attribute>
        <Attribute name="window" type="Integer">
            <Doc>Number of periods for type="rolling". Required for rolling.</Doc>
        </Attribute>
        <Attribute name="function">
            <Doc>Aggregation for type="rolling": sum (default) or avg.</Doc>
            <Value>sum</Value>
            <Value>avg</Value>
        </Attribute>
        <Attribute name="formatString">
            <Doc>FORMAT_STRING property for the generated member.</Doc>
        </Attribute>
    </Element>
```

- [ ] **Step 2: Register `TimeCalcs` as a legal Cube child.** In the Cube `<Code>` block (~924-934), add `TimeCalcs.class,` to the `Children<CubeElement>(...)` constructor list and add a getter. Change:

```java
            public final Children<CubeElement> children =
                new Children<CubeElement>(
                    CubeElement.class,
                    Annotations.class,
                    MeasureGroups.class,
                    Dimensions.class,
                    CalculatedMembers.class,
                    NamedSets.class)
```
to add `TimeCalcs.class,` after `CalculatedMembers.class,`. Then add this getter alongside `getCalculatedMembers()` (~949):

```java
            public NamedList<TimeCalc> getTimeCalcs() {
                return children.only(TimeCalcs.EMPTY).list();
            }
```

- [ ] **Step 3: Build to regenerate `MondrianDef.java` and verify the class exists.**

Run:
```bash
cd /Users/tombarber/Projects/saiku/mondrian-saiku
mvn -q generate-sources 2>&1 | tail -3
grep -n "class TimeCalc\b\|class TimeCalcs\b\|public String type;\|public Integer window;" target/generated-sources/xom/mondrian/olap/MondrianDef.java | head
```
Expected: `MondrianDef.TimeCalc` and `MondrianDef.TimeCalcs` classes generated, with public fields `name,type,measure,timeDimension,window,function,formatString`. If xomgen errors, the most likely cause is a typo in the `<Element>`/`<Attribute>` tags — compare against the `CalculatedMembers`/`Measure` blocks.

- [ ] **Step 4: Commit**

```bash
git add src/main/xom/mondrian/olap/MondrianSchema.xml
git commit -m "feat(#112): TimeCalc XOM schema element (declarative time intelligence)"
```

---

## Task 2: `TimeCalcDesugarer` — pure MDX formula generation

**Files:**
- Create: `src/main/java/mondrian/rolap/TimeCalcDesugarer.java`
- Test: `src/test/java/mondrian/rolap/TimeCalcDesugarerTest.java`

- [ ] **Step 1: Write the failing unit test.**

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeCalcDesugarerTest {

    private static final String M = "[Measures].[Revenue]";
    private static final String TH = "[Calendar].[Calendar]";
    private static final String YL = "[Calendar].[Calendar].[Year]";

    @Test public void yoyIsYearOverYearGrowthPercent() {
        String f = TimeCalcDesugarer.formula("yoy", M, TH, YL, null, null);
        assertEquals(
            "(" + M + " - (" + M + ", ParallelPeriod(" + YL + ", 1)))"
            + " / (" + M + ", ParallelPeriod(" + YL + ", 1))",
            f);
    }

    @Test public void popIsPeriodOverPeriodGrowthPercent() {
        String f = TimeCalcDesugarer.formula("pop", M, TH, YL, null, null);
        assertEquals(
            "(" + M + " - (" + M + ", " + TH + ".CurrentMember.PrevMember))"
            + " / (" + M + ", " + TH + ".CurrentMember.PrevMember)",
            f);
    }

    @Test public void ytdIsCumulative() {
        String f = TimeCalcDesugarer.formula("ytd", M, TH, YL, null, null);
        assertEquals("Aggregate(Ytd(" + TH + ".CurrentMember), " + M + ")", f);
    }

    @Test public void rollingSumUsesLastPeriodsAggregate() {
        String f = TimeCalcDesugarer.formula("rolling", M, TH, YL, 3, "sum");
        assertEquals(
            "Aggregate(LastPeriods(3, " + TH + ".CurrentMember), " + M + ")", f);
    }

    @Test public void rollingAvgUsesAvg() {
        String f = TimeCalcDesugarer.formula("rolling", M, TH, YL, 3, "avg");
        assertEquals(
            "Avg(LastPeriods(3, " + TH + ".CurrentMember), " + M + ")", f);
    }

    @Test public void rollingDefaultsToSumWhenFunctionNull() {
        String f = TimeCalcDesugarer.formula("rolling", M, TH, YL, 3, null);
        assertTrue(f.startsWith("Aggregate(LastPeriods(3,"), f);
    }

    @Test public void unknownTypeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> TimeCalcDesugarer.formula("bogus", M, TH, YL, null, null));
    }

    @Test public void rollingWithoutWindowThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> TimeCalcDesugarer.formula("rolling", M, TH, YL, null, "sum"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='TimeCalcDesugarerTest' -DfailIfNoTests=false`
Expected: FAIL/compile error — `TimeCalcDesugarer` does not exist.

- [ ] **Step 3: Implement `TimeCalcDesugarer`.**

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap;

/**
 * #112 Phase 2: desugars a declarative {@code <TimeCalc>} into the MDX formula
 * of an equivalent calculated member. Pure and side-effect free — all schema
 * resolution (measure name, time hierarchy/level unique names) happens in
 * {@link RolapSchemaLoader} and is passed in, so this is trivially testable.
 */
public final class TimeCalcDesugarer {
    private TimeCalcDesugarer() {}

    /**
     * @param type one of yoy|pop|ytd|rolling
     * @param measure the base measure unique name, e.g. {@code [Measures].[Revenue]}
     * @param timeHierarchy the time hierarchy unique name, e.g. {@code [Calendar].[Calendar]}
     * @param yearLevel the TimeYears level unique name (used by yoy/ytd)
     * @param window number of periods (rolling only; required for rolling)
     * @param function rolling aggregation: sum (default) or avg
     * @return the MDX formula string
     * @throws IllegalArgumentException on an unknown type or rolling w/o window
     */
    public static String formula(
        String type, String measure, String timeHierarchy,
        String yearLevel, Integer window, String function)
    {
        switch (type) {
        case "yoy": {
            String prior = "(" + measure + ", ParallelPeriod("
                + yearLevel + ", 1))";
            return "(" + measure + " - " + prior + ") / " + prior;
        }
        case "pop": {
            String prior = "(" + measure + ", "
                + timeHierarchy + ".CurrentMember.PrevMember)";
            return "(" + measure + " - " + prior + ") / " + prior;
        }
        case "ytd":
            return "Aggregate(Ytd(" + timeHierarchy + ".CurrentMember), "
                + measure + ")";
        case "rolling": {
            if (window == null) {
                throw new IllegalArgumentException(
                    "TimeCalc type='rolling' requires a 'window'");
            }
            String range = "LastPeriods(" + window + ", "
                + timeHierarchy + ".CurrentMember)";
            boolean avg = "avg".equals(function);
            return (avg ? "Avg(" : "Aggregate(")
                + range + ", " + measure + ")";
        }
        default:
            throw new IllegalArgumentException(
                "unknown TimeCalc type '" + type + "'"
                + " (expected yoy|pop|ytd|rolling)");
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='TimeCalcDesugarerTest' -DfailIfNoTests=false`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mondrian/rolap/TimeCalcDesugarer.java src/test/java/mondrian/rolap/TimeCalcDesugarerTest.java
git commit -m "feat(#112): TimeCalcDesugarer — pure TimeCalc -> MDX formula"
```

---

## Task 3: Loader desugaring + validation

**Files:**
- Modify: `src/main/java/mondrian/rolap/RolapSchemaLoader.java` (~2057-2068 call site)
- Test: covered end-to-end in Task 7; this task adds the wiring + a focused validation helper.

- [ ] **Step 1: Add a desugaring helper method** to `RolapSchemaLoader` (place it near `createCalcMembersAndNamedSets`, ~5298). It resolves the cube's time dimension + year level, validates, and returns synthesized calc members.

```java
    /**
     * #112: desugar a cube's &lt;TimeCalc&gt; declarations into calculated
     * members on [Measures]. Validates the base measure exists and a typed Time
     * dimension is resolvable; fails closed at load otherwise.
     */
    private List<MondrianDef.CalculatedMember> desugarTimeCalcs(
        List<MondrianDef.TimeCalc> xmlTimeCalcs,
        List<RolapMember> measureList,
        RolapCube cube)
    {
        List<MondrianDef.CalculatedMember> out =
            new ArrayList<MondrianDef.CalculatedMember>();
        if (xmlTimeCalcs == null || xmlTimeCalcs.isEmpty()) {
            return out;
        }
        for (MondrianDef.TimeCalc tc : xmlTimeCalcs) {
            // Base measure must exist.
            boolean found = false;
            for (RolapMember m : measureList) {
                if (m.getName().equals(tc.measure)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new MondrianException(
                    "TimeCalc '" + tc.name + "': measure '" + tc.measure
                    + "' not found in cube '" + cube.getName() + "'");
            }
            // Resolve the time hierarchy + year level.
            mondrian.olap.Level yearLevel = cube.getTimeLevel(
                org.olap4j.metadata.Level.Type.TIME_YEARS);
            if (yearLevel == null) {
                throw new MondrianException(
                    "TimeCalc '" + tc.name + "': cube '" + cube.getName()
                    + "' has no Time dimension with a TimeYears level");
            }
            String timeHierarchy =
                yearLevel.getHierarchy().getUniqueName();
            String measureUniqueName = "[Measures].[" + tc.measure + "]";
            String formula = TimeCalcDesugarer.formula(
                tc.type, measureUniqueName, timeHierarchy,
                yearLevel.getUniqueName(),
                tc.window, tc.function);

            MondrianDef.CalculatedMember cm =
                new MondrianDef.CalculatedMember();
            cm.name = tc.name;
            cm.dimension = "Measures";
            cm.formula = formula;
            cm.formatString = tc.formatString;
            out.add(cm);
        }
        return out;
    }
```

- [ ] **Step 2: Wire it into the calc-member call site** (~2061). Replace:

```java
        createCalcMembersAndNamedSets(
            xmlCube.getCalculatedMembers(),
            xmlCube.getNamedSets(),
            measureList,
            cube.calculatedMemberList,
            cube.namedSetList,
            cube,
            true);
```
with:
```java
        List<MondrianDef.CalculatedMember> calcMembers =
            new ArrayList<MondrianDef.CalculatedMember>(
                xmlCube.getCalculatedMembers());
        calcMembers.addAll(
            desugarTimeCalcs(xmlCube.getTimeCalcs(), measureList, cube));
        createCalcMembersAndNamedSets(
            calcMembers,
            xmlCube.getNamedSets(),
            measureList,
            cube.calculatedMemberList,
            cube.namedSetList,
            cube,
            true);
```

- [ ] **Step 3: Build to confirm it compiles** (functional verification is Task 7).

Run: `mvn -q test-compile 2>&1 | grep -E "BUILD|ERROR" | head -3`
Expected: no compilation errors (the `MondrianException`, `RolapMember`, `org.olap4j.metadata.Level` types are already imported/used in this file; if `org.olap4j.metadata.Level` is not imported, use the fully-qualified name as written above).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mondrian/rolap/RolapSchemaLoader.java
git commit -m "feat(#112): desugar <TimeCalc> into calc members at load (fail-closed)"
```

---

## Task 4: CLI YAML converter round-trip (`time_calcs`)

**Files:**
- Modify: `src/main/java/mondrian/schema/yaml/XmlSchemaToYaml.java` (~240-246, ~560-580)
- Modify: `src/main/java/mondrian/schema/yaml/YamlSchemaConverter.java` (~295-297, ~702-732)
- Test: `src/test/java/mondrian/schema/yaml/TimeCalcRoundTripTest.java` (created in Task 5; CLI assertions added here)

- [ ] **Step 1: XmlSchemaToYaml — emit `time_calcs`.** After the `calculated_members` emission block (~246), add:

```java
            List<Map<String, Object>> tcs = new ArrayList<>();
            for (Element tc : directChildren(c, "TimeCalc")) {
                tcs.add(toTimeCalc(tc));
            }
            if (!tcs.isEmpty()) {
                body.put("time_calcs", tcs);
            }
```
and add the mapper near `toCalculatedMember` (~580):
```java
    private static Map<String, Object> toTimeCalc(Element tc) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, tc, "name", "name");
        putAttrIfPresent(m, tc, "type", "type");
        putAttrIfPresent(m, tc, "measure", "measure");
        putAttrIfPresent(m, tc, "time_dimension", "timeDimension");
        putAttrIfPresent(m, tc, "window", "window");
        putAttrIfPresent(m, tc, "function", "function");
        putAttrIfPresent(m, tc, "format_string", "formatString");
        return m;
    }
```

- [ ] **Step 2: YamlSchemaConverter — emit `<TimeCalc>`.** After the `calculated_members` loop (~297), add:

```java
        for (Object tc : listOrEmpty(c, "time_calcs")) {
            emitTimeCalc(buf, (Map<?, ?>) tc);
        }
```
and the emitter near `emitCalculatedMember` (~732):
```java
    private static void emitTimeCalc(StringBuilder buf, Map<?, ?> tc) {
        buf.append("    <TimeCalc");
        attrIfPresent(buf, tc, "name", "name");
        attrIfPresent(buf, tc, "type", "type");
        attrIfPresent(buf, tc, "measure", "measure");
        attrIfPresent(buf, tc, "timeDimension", "time_dimension");
        attrIfPresent(buf, tc, "window", "window");
        attrIfPresent(buf, tc, "function", "function");
        attrIfPresent(buf, tc, "formatString", "format_string");
        buf.append("/>\n");
    }
```

Note the `attrIfPresent(buf, map, xmlAttr, yamlKey)` argument order — confirm against the existing `emitCalculatedMember` usage and match it (the verbatim source shows `attrIfPresent(buf, cm, "name", "name")` where the 3rd arg is the XML attribute and the 4th the YAML key; the asymmetry vs `putAttrIfPresent` is real — keep each consistent with its file).

- [ ] **Step 3: Run the round-trip test (added in Task 5) — CLI half.**

Covered by `TimeCalcRoundTripTest.cliPairRoundTrips` (Task 5, Step 1). For now:
Run: `mvn -q test-compile 2>&1 | grep -E "ERROR" | head`
Expected: compiles.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/XmlSchemaToYaml.java src/main/java/mondrian/schema/yaml/YamlSchemaConverter.java
git commit -m "feat(#112): round-trip <TimeCalc> through the CLI YAML converter"
```

---

## Task 5: M4 YAML converter round-trip + round-trip test

**Files:**
- Modify: `src/main/java/mondrian/schema/yaml/m4/M4CubeIngester.java` (~48-76 dispatch, ~83-161 mappers)
- Modify: `src/main/java/mondrian/schema/yaml/m4/M4CubeBuilder.java` (~56-66 consume, ~72-120 builders)
- Create: `src/test/java/mondrian/schema/yaml/TimeCalcRoundTripTest.java`

- [ ] **Step 1: Write the failing round-trip test** (covers BOTH pairs).

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml;

import mondrian.schema.yaml.m4.M4XmlToYaml;
import mondrian.schema.yaml.m4.M4YamlToXml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeCalcRoundTripTest {

    private static final String XML =
        "<Schema name='TC' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema><Table name='f'/></PhysicalSchema>\n"
        + "  <Cube name='C'>\n"
        + "    <Dimensions/>\n"
        + "    <MeasureGroups><MeasureGroup name='M' table='f'>\n"
        + "      <Measures><Measure name='Revenue' column='rev'"
        + " aggregator='sum'/></Measures>\n"
        + "      <DimensionLinks/>\n"
        + "    </MeasureGroup></MeasureGroups>\n"
        + "    <TimeCalc name='Revenue YoY' type='yoy' measure='Revenue'"
        + " timeDimension='Calendar' formatString='0.0%'/>\n"
        + "    <TimeCalc name='Revenue R3' type='rolling' measure='Revenue'"
        + " timeDimension='Calendar' window='3' function='avg'/>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    @Test public void cliPairRoundTrips() {
        String yaml = XmlSchemaToYaml.toYaml(XML);
        assertTrue(yaml.contains("time_calcs:"), yaml);
        assertTrue(yaml.contains("type: \"yoy\""), yaml);
        assertTrue(yaml.contains("window: 3") || yaml.contains("window: \"3\""),
            yaml);
        String xml2 = YamlSchemaConverter.toXml(yaml);
        assertTrue(xml2.contains("<TimeCalc"), xml2);
        assertTrue(xml2.contains("type=\"yoy\""), xml2);
        assertTrue(xml2.contains("function=\"avg\""), xml2);
    }

    @Test public void m4PairRoundTrips() {
        String yaml = M4XmlToYaml.toYaml(XML);
        assertTrue(yaml.contains("time_calcs:"), yaml);
        assertTrue(yaml.contains("type: \"rolling\""), yaml);
        String xml2 = M4YamlToXml.toXml(yaml);
        assertTrue(xml2.contains("<TimeCalc"), xml2);
        assertTrue(xml2.contains("measure=\"Revenue\""), xml2);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='TimeCalcRoundTripTest' -DfailIfNoTests=false`
Expected: FAIL — `time_calcs:` absent (M4 pair) and/or `<TimeCalc` absent.

- [ ] **Step 3: M4CubeIngester — emit `time_calcs`.** In the cube child dispatch loop (~48-66) add a branch mirroring the `CalculatedMembers` one:

```java
                } else if (ce instanceof MondrianDef.TimeCalcs) {
                    tcList = timeCalcs((MondrianDef.TimeCalcs) ce);
```
(declare `List<Object> tcList = null;` next to `cmList`), and after the `calculated_members` put:
```java
            if (tcList != null && !tcList.isEmpty()) {
                out.put("time_calcs", tcList);
            }
```
Add the mappers near `calculatedMember` (~161):
```java
    private static List<Object> timeCalcs(MondrianDef.TimeCalcs wrapper) {
        List<Object> out = new ArrayList<>();
        if (wrapper.array != null) {
            for (MondrianDef.TimeCalc tc : wrapper.array) {
                out.add(timeCalc(tc));
            }
        }
        return out;
    }

    private static Map<String, Object> timeCalc(MondrianDef.TimeCalc tc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", tc.name);
        out.put("type", tc.type);
        out.put("measure", tc.measure);
        if (tc.timeDimension != null) {
            out.put("time_dimension", tc.timeDimension);
        }
        if (tc.window != null) {
            out.put("window", tc.window);
        }
        if (tc.function != null) {
            out.put("function", tc.function);
        }
        if (tc.formatString != null) {
            out.put("format_string", tc.formatString);
        }
        return out;
    }
```

- [ ] **Step 4: M4CubeBuilder — consume `time_calcs`.** In the cube-child assembly (~56-66) add:

```java
        Object tcs = body.get("time_calcs");
        if (tcs instanceof List && !((List<?>) tcs).isEmpty()) {
            cubeKids.add(buildTimeCalcs((List<?>) tcs));
        }
```
and the builders near `buildCalculatedMember` (~120):
```java
    private static MondrianDef.TimeCalcs buildTimeCalcs(List<?> list) {
        MondrianDef.TimeCalcs wrapper = new MondrianDef.TimeCalcs();
        List<MondrianDef.TimeCalc> tcs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                tcs.add(buildTimeCalc((Map<?, ?>) item));
            }
        }
        wrapper.array = tcs.toArray(new MondrianDef.TimeCalc[0]);
        return wrapper;
    }

    private static MondrianDef.TimeCalc buildTimeCalc(Map<?, ?> m) {
        MondrianDef.TimeCalc tc = new MondrianDef.TimeCalc();
        tc.name = M4YamlToXml.str(m.get("name"));
        tc.type = M4YamlToXml.str(m.get("type"));
        tc.measure = M4YamlToXml.str(m.get("measure"));
        tc.timeDimension = M4YamlToXml.str(m.get("time_dimension"));
        Object w = m.get("window");
        if (w != null) {
            tc.window = Integer.valueOf(String.valueOf(w));
        }
        tc.function = M4YamlToXml.str(m.get("function"));
        tc.formatString = M4YamlToXml.str(m.get("format_string"));
        return tc;
    }
```

- [ ] **Step 5: Run to verify both pairs pass**

Run: `mvn -q test -Dtest='TimeCalcRoundTripTest' -DfailIfNoTests=false`
Expected: PASS (2 tests). If `window` serialization differs (string vs int), align the test's `window:` assertion to the actual emitted form.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/m4/M4CubeIngester.java src/main/java/mondrian/schema/yaml/m4/M4CubeBuilder.java src/test/java/mondrian/schema/yaml/TimeCalcRoundTripTest.java
git commit -m "feat(#112): round-trip <TimeCalc> through the M4 YAML converter + test"
```

---

## Task 6: Fixture + Bank demo examples

**Files:**
- Modify: `demo/bank.sql` (add `mm_calendar`, `mm_monthly`)
- Modify: `demo/Bank.mondrian.xml` (add `Calendar` dim + `Monthly Revenue` cube with 4 `<TimeCalc>`)
- Regenerate: `demo/Bank.yaml`

- [ ] **Step 1: Add the monthly fixture to `demo/bank.sql`.** Append before the trailing "Golden values" comment block (and add the two DROPs near the top with the others):

DROP additions (next to the existing `DROP TABLE IF EXISTS "mm_txn";`):
```sql
DROP TABLE IF EXISTS "mm_calendar";
DROP TABLE IF EXISTS "mm_monthly";
```
Tables + data (append after the `mm_txn` inserts):
```sql
-- Time-intelligence fixture (#112): a monthly calendar + revenue series.
-- 3 months x 2 years. Golden values (one branch, summed Revenue):
--   2024: Jan 100, Feb 200, Mar 300 ; 2025: Jan 150, Feb 250, Mar 350
--   YoY  2025 Jan = (150-100)/100 = 0.5      (Mar = (350-300)/300 = 0.1667)
--   PoP  2024 Feb = (200-100)/100 = 1.0      (Mar = (300-200)/200 = 0.5)
--   YTD  2024 Mar = 100+200+300 = 600        (2025 Feb = 150+250 = 400)
--   Rolling-3 avg 2024 Mar = (100+200+300)/3 = 200 ; 2025 Mar = 250
CREATE TABLE "mm_calendar" (
    "month_key"  INTEGER,
    "month_name" VARCHAR(8),
    "quarter"    VARCHAR(8),
    "yr"         INTEGER
);
CREATE TABLE "mm_monthly" (
    "month_key" INTEGER,
    "revenue"   INTEGER
);
INSERT INTO "mm_calendar" ("month_key","month_name","quarter","yr") VALUES
    (202401, 'Jan', '2024-Q1', 2024),
    (202402, 'Feb', '2024-Q1', 2024),
    (202403, 'Mar', '2024-Q1', 2024),
    (202501, 'Jan', '2025-Q1', 2025),
    (202502, 'Feb', '2025-Q1', 2025),
    (202503, 'Mar', '2025-Q1', 2025);
INSERT INTO "mm_monthly" ("month_key","revenue") VALUES
    (202401, 100), (202402, 200), (202403, 300),
    (202501, 150), (202502, 250), (202503, 350);
```

- [ ] **Step 2: Verify the fixture loads.**

Run:
```bash
java -cp "$(find ~/.m2 -name 'h2-1.4.188.jar' | head -1)" org.h2.tools.RunScript \
  -url "jdbc:h2:mem:c;DB_CLOSE_DELAY=-1" -user sa -script demo/bank.sql && echo OK
```
Expected: `OK`.

- [ ] **Step 3: Add the `Calendar` dimension + `Monthly Revenue` cube** to `demo/Bank.mondrian.xml`. First register the two tables in `<PhysicalSchema>` (next to `<Table name="mm_txn"/>`):
```xml
    <Table name="mm_calendar"/>
    <Table name="mm_monthly"/>
```
Then add the dimension + cube before `</Schema>`:
```xml
  <!-- A typed Time dimension (Year > Quarter > Month) for time intelligence. -->
  <Dimension name="Calendar" table="mm_calendar" key="Month" type="TIME">
    <Attributes>
      <Attribute name="Year" levelType="TimeYears" hasHierarchy="false">
        <Key><Column name="yr"/></Key>
      </Attribute>
      <Attribute name="Quarter" levelType="TimeQuarters" hasHierarchy="false">
        <Key><Column name="quarter"/></Key>
      </Attribute>
      <Attribute name="Month" levelType="TimeMonths" hasHierarchy="false">
        <Key><Column name="month_key"/></Key>
        <Name><Column name="month_name"/></Name>
      </Attribute>
    </Attributes>
    <Hierarchies>
      <Hierarchy name="Calendar" allMemberName="All Periods">
        <Level attribute="Year"/>
        <Level attribute="Quarter"/>
        <Level attribute="Month"/>
      </Hierarchy>
    </Hierarchies>
  </Dimension>

  <!-- #112 declarative time intelligence over a monthly revenue series. -->
  <Cube name="Monthly Revenue">
    <Dimensions>
      <Dimension source="Calendar"/>
    </Dimensions>
    <MeasureGroups>
      <MeasureGroup name="Revenue" table="mm_monthly">
        <Measures>
          <Measure name="Revenue" column="revenue" aggregator="sum"
                   formatString="#,###"/>
        </Measures>
        <DimensionLinks>
          <ForeignKeyLink dimension="Calendar" foreignKeyColumn="month_key"/>
        </DimensionLinks>
      </MeasureGroup>
    </MeasureGroups>
    <TimeCalc name="Revenue YoY"  type="yoy"     measure="Revenue"
              timeDimension="Calendar" formatString="0.0%"/>
    <TimeCalc name="Revenue PoP"  type="pop"     measure="Revenue"
              timeDimension="Calendar" formatString="0.0%"/>
    <TimeCalc name="Revenue YTD"  type="ytd"     measure="Revenue"
              timeDimension="Calendar" formatString="#,###"/>
    <TimeCalc name="Revenue R3"   type="rolling" measure="Revenue"
              timeDimension="Calendar" window="3" function="avg"
              formatString="#,###"/>
  </Cube>

</Schema>
```

- [ ] **Step 4: Regenerate `demo/Bank.yaml`** (the parity test guards it):

Run:
```bash
CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime 2>/dev/null | tail -1)"
java -cp "$CP" mondrian.schema.yaml.SchemaCli to-yaml demo/Bank.mondrian.xml -o demo/Bank.yaml
grep -c "time_calcs" demo/Bank.yaml
```
Expected: `time_calcs` present (count ≥ 1). (Requires Task 4 built.)

- [ ] **Step 5: Commit**

```bash
git add demo/bank.sql demo/Bank.mondrian.xml demo/Bank.yaml
git commit -m "demo(#112): Monthly Revenue cube with TimeCalc examples + monthly fixture"
```

---

## Task 7: Integration test (golden values + validation, XML & YAML)

**Files:**
- Create: `src/test/java/mondrian/calcite/TimeIntelligenceH2EndToEndTest.java`

- [ ] **Step 1: Write the test.** Mirrors `BankShowcaseH2EndToEndTest` (RUNSCRIPT load + XML/YAML forms). Member paths use the `Calendar` hierarchy `[Calendar].[Calendar].[<year>].[<quarter>].[<month>]`.

```java
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

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * #112 Phase 2: golden time-intelligence values from the declarative
 * &lt;TimeCalc&gt; declarations on the Bank "Monthly Revenue" cube, in both XML
 * and YAML schema forms, plus a fail-closed validation case.
 */
public class TimeIntelligenceH2EndToEndTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_timeintel;DB_CLOSE_DELAY=-1";
    private static String xmlSchema;
    private static String yamlSchema;

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            st.execute("RUNSCRIPT FROM 'demo/bank.sql'");
        }
        xmlSchema = new String(
            Files.readAllBytes(Path.of("demo/Bank.mondrian.xml")));
        yamlSchema = mondrian.schema.yaml.m4.M4YamlToXml.toXml(
            mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(xmlSchema));
    }

    private static Connection connect(String form) {
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(),
            "yaml".equals(form) ? yamlSchema : xmlSchema);
        return DriverManager.getConnection(props, null, null);
    }

    private static Double cell(Connection c, String measure, String month) {
        Query q = c.parseQuery(
            "SELECT {[Measures].[" + measure + "]} ON COLUMNS,\n"
            + " {" + month + "} ON ROWS\n"
            + "FROM [Monthly Revenue]");
        Result r = c.execute(q);
        Object v = r.getCell(new int[]{0, 0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    // Member paths in the Calendar hierarchy.
    private static final String JAN25 =
        "[Calendar].[Calendar].[2025].[2025-Q1].[Jan]";
    private static final String FEB24 =
        "[Calendar].[Calendar].[2024].[2024-Q1].[Feb]";
    private static final String MAR24 =
        "[Calendar].[Calendar].[2024].[2024-Q1].[Mar]";
    private static final String MAR25 =
        "[Calendar].[Calendar].[2025].[2025-Q1].[Mar]";

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void timeIntelligenceGoldenValues(String form) {
        Connection c = connect(form);
        try {
            assertEquals(0.5, cell(c, "Revenue YoY", JAN25), 0.001,
                "YoY Jan2025 = (150-100)/100");
            assertEquals(1.0, cell(c, "Revenue PoP", FEB24), 0.001,
                "PoP Feb2024 = (200-100)/100");
            assertEquals(600.0, cell(c, "Revenue YTD", MAR24), 0.001,
                "YTD Mar2024 = 100+200+300");
            assertEquals(250.0, cell(c, "Revenue R3", MAR25), 0.001,
                "rolling-3 avg Mar2025 = (150+250+350)/3");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }

    @Test
    public void timeCalcWithUnknownMeasureFailsClosed() {
        String bad = xmlSchema.replace(
            "<TimeCalc name=\"Revenue YoY\"  type=\"yoy\"     measure=\"Revenue\"",
            "<TimeCalc name=\"Revenue YoY\"  type=\"yoy\"     measure=\"Nope\"");
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), bad);
        assertThrows(RuntimeException.class, () -> {
            Connection c = DriverManager.getConnection(props, null, null);
            c.execute(c.parseQuery(
                "SELECT {[Measures].[Revenue]} ON COLUMNS FROM [Monthly Revenue]"));
        }, "a TimeCalc on a missing measure must fail closed at load");
    }
}
```

- [ ] **Step 2: Run; pin member paths if needed.**

Run: `mvn -q test -Dtest='TimeIntelligenceH2EndToEndTest' -DfailIfNoTests=false`
Expected: PASS (2 param + 1 = 3). If a member path is wrong (e.g. quarter member name differs), run a probe query selecting `[Calendar].[Calendar].[Month].Members` to read the exact unique names, and correct the `JAN25`/`FEB24`/`MAR24`/`MAR25` constants — do NOT change the fixture numbers. If YoY/PoP on the earliest period returns an unexpected value (empty prior), note it's expected (no prior period) — the asserted cells all have a valid prior by construction.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/mondrian/calcite/TimeIntelligenceH2EndToEndTest.java
git commit -m "test(#112): time-intelligence golden values + fail-closed validation (XML & YAML)"
```

---

## Task 8: Parity + regression sweep

- [ ] **Step 1: Confirm `BankYamlParityTest` still green** (the regenerated `demo/Bank.yaml` must equal the CLI conversion and still load):

Run: `mvn -q test -Dtest='BankYamlParityTest' -DfailIfNoTests=false`
Then: `grep "Tests run" target/surefire-reports/mondrian.calcite.BankYamlParityTest.txt`
Expected: green. If the drift guard fails, re-run Task 6 Step 4 (the committed YAML must be exactly the CLI output).

- [ ] **Step 2: Full sweep of new + adjacent suites:**

Run:
```bash
mvn -q test -Dtest='TimeCalcDesugarerTest,TimeCalcRoundTripTest,TimeIntelligenceH2EndToEndTest,BankShowcaseH2EndToEndTest,BankYamlParityTest,BankLookmlMigrationH2EndToEndTest,LookmlTranspilerTest' -DfailIfNoTests=false
```
Inspect each `target/surefire-reports/*.txt`. Expected: all green, 0 failures/errors.

- [ ] **Step 3: Commit** (only if any test files were adjusted)

```bash
git commit -am "test(#112): pin time-intelligence member paths / parity" || echo "nothing to commit"
```

---

## Task 9: Docs site page (saiku-cloud docs-site)

**Files:**
- Create: a "Time intelligence" page in the saiku-cloud docs-site (the product docs repo at `../saiku-cloud`, deployed from `develop` via Cloudflare Pages).

- [ ] **Step 1: Locate the docs-site advanced/mondrian section.**

Run:
```bash
ls /Users/tombarber/Projects/saiku/saiku-cloud/docs-site 2>/dev/null || \
  find /Users/tombarber/Projects/saiku/saiku-cloud -maxdepth 3 -type d -name 'docs*' 2>/dev/null | head
grep -rln "Bridge\|advanced\|Mondrian" /Users/tombarber/Projects/saiku/saiku-cloud/docs-site 2>/dev/null | head
```
Expected: the docs content dir (e.g. `docs-site/.../mondrian/advanced.*`) — note the format (Markdown/MDX) and the existing bridge/advanced page to match style.

- [ ] **Step 2: Write the page** in the docs-site, matching the existing page format, with: what `<TimeCalc>` is; the attribute table (name/type/measure/timeDimension/window/function/formatString); the four types with their meaning and desugared MDX; the typed-Time-dimension requirement; and the worked `Monthly Revenue` example (the four declarations + the golden numbers from `demo/bank.sql`), with both the XML and YAML form. Link it from the existing advanced/bridge docs index.

- [ ] **Step 3: Commit in the saiku-cloud repo** (separate repo — branch there per its convention):

```bash
cd /Users/tombarber/Projects/saiku/saiku-cloud
git checkout -b docs/time-intelligence
git add <the new page + index link>
git commit -m "docs: time intelligence (<TimeCalc>) — declarative YoY/PoP/YTD/rolling"
```
Report the path + branch back to the user (a separate PR in saiku-cloud).

---

## Task 10: Push + PR

- [ ] **Step 1: Push the feature branch and open a PR into develop.**

```bash
cd /Users/tombarber/Projects/saiku/mondrian-saiku
git push -u origin feat/time-intelligence
gh pr create --base develop --head feat/time-intelligence \
  --title "Declarative time-intelligence (<TimeCalc>) — #112 phase 2" \
  --body "$(cat <<'BODY'
## Summary
Adds a declarative `<TimeCalc type="yoy|pop|ytd|rolling">` schema element that
desugars at load into a validated calculated member on [Measures] — authors
declare YoY/PoP/YTD/rolling instead of hand-writing MDX (#112 phase 2).

- XOM `TimeCalc` element; pure `TimeCalcDesugarer` (type+names -> MDX); loader
  desugaring with fail-closed validation; round-trip through both YAML converter
  pairs.
- Bank demo: a `Monthly Revenue` cube + monthly fixture carrying the four
  examples, shown in XML and YAML.
- Tests: desugarer unit, both converter round-trips, H2 golden values in XML &
  YAML, and a fail-closed validation case. Docs-site page in a separate
  saiku-cloud PR.

## Test Plan
- [x] TimeCalcDesugarerTest, TimeCalcRoundTripTest
- [x] TimeIntelligenceH2EndToEndTest (golden YoY/PoP/YTD/rolling, XML & YAML, + validation)
- [x] BankShowcaseH2EndToEndTest, BankYamlParityTest, BankLookmlMigrationH2EndToEndTest still green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Note: this branch is stacked on `feat/bank-yaml-demo` (PR #130). If #130 has not merged, the PR will show its commits too; merge #130 first for a clean diff.

---

## Self-review notes

- **Spec coverage:** TimeCalc element (T1), desugarer (T2), loader+validation (T3), CLI round-trip (T4), M4 round-trip (T5), fixture+examples+YAML (T6), integration golden+validation XML&YAML (T7), parity/regression (T8), docs (T9), PR (T10). ✅
- **Type/name consistency:** `desugarTimeCalcs` / `TimeCalcDesugarer.formula(type,measure,timeHierarchy,yearLevel,window,function)` used identically in T2 and T3; `MondrianDef.TimeCalc` fields (`name,type,measure,timeDimension,window,function,formatString`) consistent across T1/T3/T5. YAML key `time_calcs` + per-attr snake_case (`time_dimension`,`format_string`) consistent across T4/T5/T6. ✅
- **Known unknowns (probe-then-pin, not guessed):** exact Calendar member unique names (T7 Step 2 says how to discover); `window` int-vs-string YAML form (T5 Step 5); the xomgen success of the new element (T1 Step 3). H2 for the manual RunScript smoke is 1.4.188 (matches the earlier T1 smoke); the test classpath H2 is 2.2.224.
- **Fail-closed:** unknown measure / missing Time dimension throw at load (T3); covered by T7's validation test.
- **Risk:** the XOM addition (T1) is the one place a syntax slip blocks the build — Step 3 verifies regeneration before anything depends on it.
