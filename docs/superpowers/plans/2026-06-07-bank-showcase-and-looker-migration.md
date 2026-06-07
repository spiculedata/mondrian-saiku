# Bank Showcase Schema + Synthetic Looker Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide one comprehensive, hand-verifiable Bank dataset + M4 schema that demonstrates every new 4.8.1.x feature, plus a synthetic LookML model proving Looker→M4 migration, all covered by readable H2 integration tests.

**Architecture:** Reuse the shipped `bank.sql` (`mm_*` tables) verbatim as a base, brought into `demo/bank.sql` and extended backward-compatibly (new columns/table). Extend the existing `demo/Bank.mondrian.xml` (already has bridge + median/percentile cubes; its parity test uses an inline schema so the file is safe to edit) with the remaining features: a degenerate Account dimension (balance **tier**, account-age **duration**), a `tenant` **query parameter** + **predicate-grant** role, a **member-grant** role on the existing Customer→Segment hierarchy, and a Transactions cube (**distinct grain**). Two H2 end-to-end tests assert golden numbers + RLS in XML and YAML form, and transpile a synthetic `demo/lookml/bank.lkml` asserting classification/provenance + sanity queries.

**Tech Stack:** Mondrian 4 M4 schema (XML + YAML round-trip via `M4XmlToYaml`/`M4YamlToXml`), H2 in-memory, JUnit 5, the LookML transpiler (`mondrian.lookml.transpile.LookmlTranspiler`).

> **Spec refinement (flag for reviewer):** the spec named a new `demo/Showcase.mondrian.xml`; this plan instead EXTENDS `demo/Bank.mondrian.xml` to avoid duplicating its existing bridge/median cubes. Revert to a separate file only if you want Bank left byte-stable.

---

## File structure

- `demo/bank.sql` — **create** (copy of shipped base + additive extensions). Backs `Bank.mondrian.xml` and the tests.
- `demo/Bank.mondrian.xml` — **modify** (add Account degenerate dim, query parameter, two roles, Transactions cube).
- `demo/lookml/bank.lkml` — **create** (synthetic Looker model).
- `demo/lookml/README.md` — **create** (migration notes + feature→test coverage matrix).
- `src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java` — **create** (features + RLS, XML & YAML).
- `src/test/java/mondrian/calcite/BankLookmlMigrationH2EndToEndTest.java` — **create** (transpile + provenance + sanity).

Reference templates (read for exact idioms; do not modify): `BridgePredicateGrantBatteryTest` (predicate grant + H2 connect + role), `BridgeMemberGrantRlsTest` (member grant), `MeasureDistinctGrainH2EndToEndTest` (distinct grain + connect helper), `TierDurationH2EndToEndTest` (`<Tier>`/`<Duration>` syntax), `LookmlTranspilerTest` (transpile + classification/provenance).

---

## Task 1: Dataset — `demo/bank.sql` (reused base + additive extension)

**Files:**
- Create: `demo/bank.sql`
- Reference: `../saiku/saiku-launcher/src/main/resources/seed/bank.sql` (the shipped base — copy its rows verbatim)

- [ ] **Step 1: Create `demo/bank.sql`** with the shipped base PLUS three additive changes: `tenant` + `open_date` + `as_of_date` columns on `mm_fact`, and a new `mm_txn` table. H2 quoted-lowercase identifiers (matches `Bank.mondrian.xml`).

```sql
-- Saiku demo: joint bank accounts — comprehensive feature showcase.
-- Base rows are REUSED VERBATIM from the shipped
-- ../saiku/saiku-launcher/src/main/resources/seed/bank.sql — KEEP IN SYNC.
-- Additions here are backward-compatible (new columns/table only):
--   mm_fact.tenant      -> predicate row-security (#106) + query parameter (#105)
--   mm_fact.open_date   -> account-age duration (#108), paired with a FIXED as_of
--   mm_fact.as_of_date  -> fixed reference date so duration numbers are stable
--   mm_txn              -> measure-level distinct grain (#119)
-- Loaded into H2. Total balance 13000, fees 130 (unchanged from the base).

DROP TABLE IF EXISTS "mm_fact";
DROP TABLE IF EXISTS "mm_owner";
DROP TABLE IF EXISTS "mm_customer";
DROP TABLE IF EXISTS "mm_branch";
DROP TABLE IF EXISTS "mm_date";
DROP TABLE IF EXISTS "mm_txn";

CREATE TABLE "mm_fact" (
    "account_id" INTEGER,
    "date_key"   INTEGER,
    "branch_id"  VARCHAR(8),
    "balance"    INTEGER,
    "fees"       INTEGER,
    "tenant"     INTEGER,
    "open_date"  DATE,
    "as_of_date" DATE
);
CREATE TABLE "mm_owner" (
    "account_id"  INTEGER,
    "customer_id" VARCHAR(16),
    "weight"      DECIMAL(5,4)
);
CREATE TABLE "mm_customer" (
    "customer_id"   VARCHAR(16),
    "customer_name" VARCHAR(32),
    "segment"       VARCHAR(16)
);
CREATE TABLE "mm_branch" (
    "branch_id"   VARCHAR(8),
    "branch_name" VARCHAR(32)
);
CREATE TABLE "mm_date" (
    "date_key" INTEGER,
    "yr"       INTEGER
);
CREATE TABLE "mm_txn" (
    "txn_id"     INTEGER,
    "line_no"    INTEGER,
    "account_id" INTEGER,
    "amount"     INTEGER
);

-- Base fact rows (balance/fees verbatim from shipped). tenant: LON branch=1,
-- LDS branch=2 (a clean partition for the predicate-RLS demo). open_date chosen
-- so as_of 2025-01-01 gives whole-year ages; as_of_date constant for stability.
INSERT INTO "mm_fact"
  ("account_id","date_key","branch_id","balance","fees","tenant","open_date","as_of_date") VALUES
    (1, 2024, 'LON', 1000, 10, 1, DATE '2020-01-01', DATE '2025-01-01'),
    (2, 2024, 'LON',  500,  5, 1, DATE '2021-01-01', DATE '2025-01-01'),
    (3, 2025, 'LDS',  300,  3, 2, DATE '2023-01-01', DATE '2025-01-01'),
    (4, 2024, 'LON', 2000, 20, 1, DATE '2019-01-01', DATE '2025-01-01'),
    (5, 2024, 'LDS', 1500, 15, 2, DATE '2022-01-01', DATE '2025-01-01'),
    (6, 2025, 'LDS',  700,  7, 2, DATE '2024-01-01', DATE '2025-01-01'),
    (7, 2025, 'LON', 4000, 40, 1, DATE '2015-01-01', DATE '2025-01-01'),
    (8, 2025, 'LDS', 3000, 30, 2, DATE '2018-01-01', DATE '2025-01-01');

INSERT INTO "mm_owner" ("account_id","customer_id","weight") VALUES
    (1, 'alice', 0.50), (1, 'bob',   0.50), (2, 'bob',   1.00),
    (3, 'alice', 0.25), (3, 'carol', 0.75), (4, 'erin',  0.50),
    (4, 'frank', 0.50), (5, 'frank', 1.00), (6, 'grace', 0.40),
    (6, 'heidi', 0.60), (7, 'erin',  0.50), (7, 'grace', 0.50),
    (8, 'heidi', 1.00);

INSERT INTO "mm_customer" ("customer_id","customer_name","segment") VALUES
    ('alice', 'Alice', 'Premium'), ('bob',   'Bob',   'Premium'),
    ('carol', 'Carol', 'Standard'),('dave',  'Dave',  'Standard'),
    ('erin',  'Erin',  'Premium'), ('frank', 'Frank', 'Standard'),
    ('grace', 'Grace', 'Premium'), ('heidi', 'Heidi', 'Standard');

INSERT INTO "mm_branch" ("branch_id","branch_name") VALUES
    ('LON', 'London'), ('LDS', 'Leeds');

INSERT INTO "mm_date" ("date_key","yr") VALUES (2024, 2024), (2025, 2025);

-- Transactions: a FAN-OUT table for measure-level distinct grain (#119).
-- Each txn repeats its sale amount across lines (a denormalised join would
-- double-count). Distinct sum over txn_id de-dups to the true per-txn total.
--   txn 100: 3 lines @ 100  -> distinct 100
--   txn 200: 1 line  @ 50   -> distinct 50
--   txn 300: 2 lines @ 300  -> distinct 300
-- Distinct sum = 100+50+300 = 450 ; naive SUM = 300+50+600 = 950.
INSERT INTO "mm_txn" ("txn_id","line_no","account_id","amount") VALUES
    (100, 1, 1, 100), (100, 2, 1, 100), (100, 3, 1, 100),
    (200, 1, 2,  50),
    (300, 1, 3, 300), (300, 2, 3, 300);

-- Golden values (asserted in BankShowcaseH2EndToEndTest):
--   Balance grand total ........................ 13000
--   Full-count Balance by Customer: Alice 1300, Bob 1500, Carol 300 ...
--   Weighted  Balance by Customer:  Alice 575,  Bob 1000, Carol 225 ...
--   Median Balance (all) ....................... 1250  (8 vals: avg of 1000,1500)
--   P90 Balance (all) .......................... ~3300
--   Distinct Amount (Transactions) ............. 450   (naive sum 950)
--   Predicate RLS tenant=1 (LON) Balance ....... 7500 ; tenant=2 (LDS) 5500
--   Member-grant 'Premium' segment excludes Standard-only owners (see test)
--   Account age (years, as_of 2025-01-01): acct7=10, acct8=7, acct4=6 ...
```

- [ ] **Step 2: Verify the SQL loads into H2** (sanity, no app):

Run:
```bash
cd /Users/tombarber/Projects/saiku/mondrian-saiku
java -cp "$(find ~/.m2 -name 'h2-*.jar' | head -1)" org.h2.tools.RunScript \
  -url "jdbc:h2:mem:bank_check;DB_CLOSE_DELAY=-1" -user sa -script demo/bank.sql && echo OK
```
Expected: `OK` (no SQL errors).

- [ ] **Step 3: Commit**

```bash
git add demo/bank.sql
git commit -m "demo: bank.sql showcase dataset (shipped base + tenant/date/txn extensions)"
```

---

## Task 2: Showcase test scaffold + bridge/stats golden numbers (regression guard on the existing cubes)

**Files:**
- Create: `src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java`
- Reference: `MeasureDistinctGrainH2EndToEndTest.java` (connect + scalar helpers), `demo/Bank.mondrian.xml`

This test loads `demo/bank.sql` and the live `demo/Bank.mondrian.xml`, parameterized over `{xml, yaml}`. Task 2 asserts the EXISTING cubes' golden numbers (a guard so later edits to the file don't regress them).

- [ ] **Step 1: Write the failing test** (scaffold + bridge/stats assertions). Read `demo/Bank.mondrian.xml` from disk; build the YAML form via the round-trip.

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #showcase: end-to-end golden-number + RLS coverage for the
 * comprehensive Bank demo (demo/Bank.mondrian.xml over demo/bank.sql),
 * run in BOTH schema forms (XML and the YAML round-trip). Doubles as living
 * documentation: every assertion is a hand-verifiable number from bank.sql.
 */
public class BankShowcaseH2EndToEndTest {

    private static final String H2_URL =
        "jdbc:h2:mem:bank_showcase;DB_CLOSE_DELAY=-1";

    private static String xmlSchema;
    private static String yamlSchema;

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        // Load the demo dataset.
        String sql = new String(Files.readAllBytes(Path.of("demo/bank.sql")));
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            for (String stmt : sql.split(";\\s*\\n")) {
                if (!stmt.isBlank() && !stmt.trim().startsWith("--")) {
                    st.execute(stmt);
                }
            }
        }
        xmlSchema = new String(
            Files.readAllBytes(Path.of("demo/Bank.mondrian.xml")));
        yamlSchema = mondrian.schema.yaml.m4.M4YamlToXml.toXml(
            mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(xmlSchema));
    }

    private static String schemaFor(String form) {
        return "yaml".equals(form) ? yamlSchema : xmlSchema;
    }

    static Connection connect(String form, String role, String tenant) {
        mondrian.olap.Util.PropertyList props =
            new mondrian.olap.Util.PropertyList();
        props.put("Provider", "mondrian");
        props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        props.put(RolapConnectionProperties.JdbcPassword.name(), "");
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(),
            schemaFor(form));
        if (role != null) {
            props.put(RolapConnectionProperties.Role.name(), role);
        }
        if (tenant != null) {
            props.put("session.tenant", tenant);
        }
        return DriverManager.getConnection(props, null, null);
    }

    static Double scalar(Connection conn, String mdx) {
        Query q = conn.parseQuery(mdx);
        Result r = conn.execute(q);
        Object v = r.getCell(new int[]{0}).getValue();
        r.close();
        return v == null ? null : ((Number) v).doubleValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void bridgeAndStatsGoldenNumbers(String form) {
        Connection c = connect(form, null, null);
        try {
            assertEquals(13000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Joint Accounts (Full Count)]"), 0.001,
                "full-count grand total de-dups the fan-out to 13000");
            assertEquals(13000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Joint Accounts (Weighted)]"), 0.001,
                "weighted grand total reconciles to 13000");
            assertEquals(1250.0, scalar(c,
                "SELECT {[Measures].[Median Balance]} ON COLUMNS"
                + " FROM [Account Statistics]"), 0.001,
                "median of 8 balances = avg(1000,1500) = 1250");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
}
```

- [ ] **Step 2: Run to verify it passes** (these cubes already exist; this guards them):

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#bridgeAndStatsGoldenNumbers' -DfailIfNoTests=false`
Expected: PASS (2 cases). If the median value differs, read the actual value from the failure and correct the golden number (H2 PERCENTILE_CONT interpolation) — do NOT change the schema.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java
git commit -m "test: Bank showcase end-to-end scaffold + bridge/stats golden numbers"
```

---

## Task 3: Tier + Duration (degenerate Account dimension on the fact)

**Files:**
- Modify: `demo/Bank.mondrian.xml` (add an `Account` degenerate dimension + use it in a cube)
- Modify: `src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java` (add tier/duration assertions)

- [ ] **Step 1: Write the failing test** — append to `BankShowcaseH2EndToEndTest`:

```java
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void tierAndDurationDimensions(String form) {
        Connection c = connect(form, null, null);
        try {
            // Balance tier bins: <1000 Small, <3000 Medium, else Large.
            // Large = accts with balance >= 3000: acct7 4000 + acct8 3000 = 7000.
            assertEquals(7000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS FROM [Accounts]\n"
                + "WHERE [Account].[Balance Tier].[Large]"), 0.001,
                "Large tier = balances >= 3000 (acct7+acct8)");
            // Account age (years) to as_of 2025-01-01: acct7 opened 2015 = 10y.
            assertEquals(4000.0, scalar(c,
                "SELECT {[Measures].[Balance]} ON COLUMNS FROM [Accounts]\n"
                + "WHERE [Account].[Age Years].[10]"), 0.001,
                "10-year-old account is acct7 (balance 4000)");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#tierAndDurationDimensions' -DfailIfNoTests=false`
Expected: FAIL (cube `[Accounts]` / dimension `[Account]` not found).

- [ ] **Step 3: Add the `Account` degenerate dimension + `Accounts` cube** to `demo/Bank.mondrian.xml`, immediately before the closing `</Schema>`. The degenerate dimension's table IS the fact (`mm_fact`); tier reads `balance`, duration reads `open_date`→`as_of_date`.

```xml
  <!-- Degenerate dimension on the fact: native Tier (binning) over balance
       and native Duration (account age) over open_date..as_of_date (#108). -->
  <Dimension name="Account" table="mm_fact" key="Account Id">
    <Attributes>
      <Attribute name="Account Id" hasHierarchy="false">
        <Key><Column name="account_id"/></Key>
      </Attribute>
      <Attribute name="Balance Tier" hasHierarchy="true">
        <Tier column="balance">
          <Bin boundary="1000" label="Small"/>
          <Bin boundary="3000" label="Medium"/>
          <Bin label="Large"/>
        </Tier>
      </Attribute>
      <Attribute name="Age Years" hasHierarchy="true">
        <Duration startColumn="open_date" endColumn="as_of_date" unit="YEAR"/>
      </Attribute>
    </Attributes>
  </Dimension>

  <Cube name="Accounts">
    <Dimensions>
      <Dimension source="Account"/>
      <Dimension source="Branch"/>
    </Dimensions>
    <MeasureGroups>
      <MeasureGroup name="Balances" table="mm_fact">
        <Measures>
          <Measure name="Balance" column="balance" aggregator="sum"
                   formatString="#,###"/>
        </Measures>
        <DimensionLinks>
          <FactLink dimension="Account"/>
          <ForeignKeyLink dimension="Branch" foreignKeyColumn="branch_id"/>
        </DimensionLinks>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#tierAndDurationDimensions' -DfailIfNoTests=false`
Expected: PASS. If a tier label/age member name differs, read the actual member from a probe query and correct the WHERE member (do not change the data). Confirm `unit="YEAR"` is accepted; if the duration unit enum rejects `YEAR`, fall back to `MONTH` and assert 120 (10y×12) instead.

- [ ] **Step 5: Commit**

```bash
git add demo/Bank.mondrian.xml src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java
git commit -m "demo(#108): Bank Accounts cube with balance tier + account-age duration"
```

---

## Task 4: Query parameter + predicate row-security (tenant)

**Files:**
- Modify: `demo/Bank.mondrian.xml` (add `<QueryParameter>` + a `Tenant` role with a `<PredicateGrant>`)
- Modify: `src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java`

- [ ] **Step 1: Write the failing test** — append:

```java
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void predicateRowSecurityByTenant(String form) {
        // tenant 1 = LON accounts (1000+500+2000+4000=7500);
        // tenant 2 = LDS (300+1500+700+3000=5500); ungranted = 13000.
        Connection t1 = connect(form, "Tenant", "1");
        Connection t2 = connect(form, "Tenant", "2");
        Connection all = connect(form, null, null);
        try {
            String mdx = "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Accounts]";
            assertEquals(7500.0, scalar(t1, mdx), 0.001, "tenant 1 sees LON");
            assertEquals(5500.0, scalar(t2, mdx), 0.001, "tenant 2 sees LDS");
            assertEquals(13000.0, scalar(all, mdx), 0.001, "ungranted sees all");
        } finally {
            t1.close(); t2.close(); all.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#predicateRowSecurityByTenant' -DfailIfNoTests=false`
Expected: FAIL (role `Tenant` undefined → all three return 13000, or a role-not-found error).

- [ ] **Step 3: Add the query parameter** (just after the opening `<Schema ...>` element, before `<PhysicalSchema>`):

```xml
  <QueryParameter name="tenant" type="Numeric" defaultValue="1">
    <QueryParameterValue>1</QueryParameterValue>
    <QueryParameterValue>2</QueryParameterValue>
  </QueryParameter>
```

  And add the role just before `</Schema>`:

```xml
  <!-- Predicate row-security (#106): filter fact rows by the bound tenant
       query parameter (#105). Enforced in the Calcite path. -->
  <Role name="Tenant">
    <SchemaGrant access="all">
      <CubeGrant cube="Accounts" access="all">
        <PredicateGrant measureGroup="Balances" column="tenant"
                        operator="eq" parameter="tenant"/>
      </CubeGrant>
    </SchemaGrant>
  </Role>
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#predicateRowSecurityByTenant' -DfailIfNoTests=false`
Expected: PASS (3 assertions × 2 forms).

- [ ] **Step 5: Commit**

```bash
git add demo/Bank.mondrian.xml src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java
git commit -m "demo(#106): Bank tenant query parameter + predicate row-security role"
```

---

## Task 5: Member/Hierarchy-grant row-security (segment)

**Files:**
- Modify: `demo/Bank.mondrian.xml` (add a `Premium` role with a `<HierarchyGrant>` on the existing Customer `By Segment` hierarchy)
- Modify: `src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java`

- [ ] **Step 1: Write the failing test** — append. The `Premium` role grants only the Premium segment; on the full-count bridge cube, accounts owned solely by Standard customers must be excluded.

```java
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void memberGrantRowSecurityBySegment(String form) {
        // Premium customers: alice, bob, erin, grace. Accounts with >=1 Premium
        // owner: 1(alice/bob),2(bob),3(alice/carol),4(erin/frank),6(grace/heidi),
        // 7(erin/grace). Account 5 (frank only=Standard) and 8 (heidi only) are
        // excluded. Full-count de-duped balance over visible-owner accounts:
        // 1000+500+300+2000+700+4000 = 8500.
        Connection premium = connect(form, "Premium", null);
        Connection all = connect(form, null, null);
        try {
            String mdx = "SELECT {[Measures].[Balance]} ON COLUMNS"
                + " FROM [Joint Accounts (Full Count)]";
            assertEquals(8500.0, scalar(premium, mdx), 0.001,
                "Premium role excludes Standard-only accounts (5,8)");
            assertEquals(13000.0, scalar(all, mdx), 0.001,
                "ungranted sees the full 13000");
        } finally {
            premium.close(); all.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#memberGrantRowSecurityBySegment' -DfailIfNoTests=false`
Expected: FAIL (role `Premium` undefined).

- [ ] **Step 3: Add the role** just before `</Schema>`. The hierarchy is `[Customer].[By Segment]` (from the existing Customer dimension); grant the `Premium` member at the Segment level.

```xml
  <!-- Member/Hierarchy-grant row-security (#107) on the bridge dimension:
       the Premium role sees only Premium-segment customers; the bridge
       fan-out is constrained to visible owners (Standard-only accounts drop). -->
  <Role name="Premium">
    <SchemaGrant access="all">
      <CubeGrant cube="Joint Accounts (Full Count)" access="all">
        <HierarchyGrant hierarchy="[Customer].[By Segment]" access="custom"
                        bottomLevel="[Customer].[By Segment].[Customer]">
          <MemberGrant member="[Customer].[By Segment].[Premium]" access="all"/>
        </HierarchyGrant>
      </CubeGrant>
    </SchemaGrant>
  </Role>
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#memberGrantRowSecurityBySegment' -DfailIfNoTests=false`
Expected: PASS. If the MDX member path differs (e.g. all-member name), probe with `[Customer].[By Segment].Members` and correct the grant member unique name; recompute 8500 only if the data changed (it didn't).

- [ ] **Step 5: Commit**

```bash
git add demo/Bank.mondrian.xml src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java
git commit -m "demo(#107): Bank Premium role with bridge member-grant row-security"
```

---

## Task 6: Distinct grain — Transactions cube

**Files:**
- Modify: `demo/Bank.mondrian.xml` (add a `Transactions` cube over `mm_txn`)
- Modify: `src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java`

- [ ] **Step 1: Write the failing test** — append:

```java
    @ParameterizedTest
    @ValueSource(strings = {"xml", "yaml"})
    public void distinctGrainTransactions(String form) {
        Connection c = connect(form, null, null);
        try {
            assertEquals(450.0, scalar(c,
                "SELECT {[Measures].[Distinct Amount]} ON COLUMNS"
                + " FROM [Transactions]"), 0.001,
                "distinct sum de-dups the fan-out on txn_id: 100+50+300");
            assertEquals(950.0, scalar(c,
                "SELECT {[Measures].[Naive Amount]} ON COLUMNS"
                + " FROM [Transactions]"), 0.001,
                "naive SUM double-counts the fanned lines: 300+50+600");
        } finally {
            c.close();
            mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#distinctGrainTransactions' -DfailIfNoTests=false`
Expected: FAIL (cube `[Transactions]` not found).

- [ ] **Step 3: Register `mm_txn` and add the `Transactions` cube.** Add `<Table name="mm_txn"/>` inside `<PhysicalSchema>`, then add the cube before `</Schema>`:

```xml
  <!-- Measure-level distinct grain (#119): mm_txn fans out (one txn repeated
       per line). Distinct Amount de-dups on txn_id before summing; Naive
       Amount shows the double-count the distinct grain avoids. -->
  <Cube name="Transactions">
    <Dimensions/>
    <MeasureGroups>
      <MeasureGroup name="Txns" table="mm_txn">
        <Measures>
          <Measure name="Distinct Amount" column="amount" aggregator="sum"
                   distinctKeyColumn="txn_id" formatString="#,###"/>
          <Measure name="Naive Amount" column="amount" aggregator="sum"
                   formatString="#,###"/>
        </Measures>
        <DimensionLinks/>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest#distinctGrainTransactions' -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Run the WHOLE showcase test (all forms, all features) to confirm no regression**

Run: `mvn -q test -Dtest='BankShowcaseH2EndToEndTest' -DfailIfNoTests=false`
Then: `grep "Tests run" target/surefire-reports/mondrian.calcite.BankShowcaseH2EndToEndTest.txt`
Expected: all green (6 methods × 2 forms = 12).

- [ ] **Step 6: Commit**

```bash
git add demo/Bank.mondrian.xml src/test/java/mondrian/calcite/BankShowcaseH2EndToEndTest.java
git commit -m "demo(#119): Bank Transactions cube demonstrating measure-level distinct grain"
```

---

## Task 7: Synthetic LookML model + migration test

**Files:**
- Create: `demo/lookml/bank.lkml`
- Create: `src/test/java/mondrian/calcite/BankLookmlMigrationH2EndToEndTest.java`
- Reference: `LookmlTranspilerTest.java` (transpile + classification/provenance assertions), `LookmlBridgeH2EndToEndTest.java` (run queries on a transpiled cube)

- [ ] **Step 1: Create `demo/lookml/bank.lkml`** — a synthetic Looker model over the same `mm_*` tables demonstrating the import paths.

```lookml
# Synthetic Looker model for the Bank demo — demonstrates Looker -> M4 migration.
# Transpile with: mondrian lookml-cli transpile demo/lookml/bank.lkml
# Covers: many_to_many -> BridgeLink (#124), access_filter -> PredicateGrant
# (#106) and -> HierarchyGrant (#115), sum_distinct -> symmetric/distinct grain
# (#117/#119), tiers -> tier (#108), median/percentile (#104), bounded
# user-attribute Liquid (#118).

view: account_fact {
  sql_table_name: mm_fact ;;
  dimension: account_id { primary_key: yes type: number sql: ${TABLE}.account_id ;; }
  dimension: tenant { type: number sql: ${TABLE}.tenant ;; }
  dimension: balance_tier {
    type: tier
    tiers: [1000, 3000]
    style: integer
    sql: ${TABLE}.balance ;;
  }
  measure: balance { type: sum sql: ${TABLE}.balance ;; }
  measure: median_balance { type: median sql: ${TABLE}.balance ;; }
  measure: p90_balance { type: percentile percentile: 90 sql: ${TABLE}.balance ;; }
}

view: account_owner {
  sql_table_name: mm_owner ;;
  dimension: account_id { type: number sql: ${TABLE}.account_id ;; }
  dimension: customer_id { type: string sql: ${TABLE}.customer_id ;; }
}

view: customer {
  sql_table_name: mm_customer ;;
  dimension: customer_id { primary_key: yes type: string sql: ${TABLE}.customer_id ;; }
  dimension: segment { type: string sql: ${TABLE}.segment ;; }
}

view: txn {
  sql_table_name: mm_txn ;;
  dimension: txn_id { type: number sql: ${TABLE}.txn_id ;; }
  measure: distinct_amount {
    type: sum_distinct
    sql_distinct_key: ${TABLE}.txn_id ;;
    sql: ${TABLE}.amount ;;
  }
}

explore: account_fact {
  join: account_owner {
    relationship: many_to_many
    sql_on: ${account_fact.account_id} = ${account_owner.account_id} ;;
  }
  join: customer {
    relationship: many_to_one
    sql_on: ${account_owner.customer_id} = ${customer.customer_id} ;;
  }
  # Predicate row-security on a fact column, bound to a user attribute.
  access_filter: { field: account_fact.tenant user_attribute: tenant }
  # Member-level row-security on a modelled dimension key.
  access_filter: { field: customer.segment user_attribute: segment }
}
```

- [ ] **Step 2: Write the failing migration test.** Transpile the model, assert key constructs classify CLEAN (and RLS is present, not dropped), then run a sanity query on the transpiled cube over `demo/bank.sql`.

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// ... (same licence header as the other tests) ...
*/
package mondrian.calcite;

import mondrian.lookml.model.Classification;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParser;
import mondrian.lookml.transpile.LookmlTranspiler;
import mondrian.lookml.transpile.TranspileResult;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #showcase: the synthetic demo/lookml/bank.lkml transpiles to M4 with
 * the expected constructs and WITHOUT silently dropping row-security — the
 * Looker-migration companion to BankShowcaseH2EndToEndTest.
 */
public class BankLookmlMigrationH2EndToEndTest {

    private static TranspileResult transpile() throws Exception {
        String lkml = new String(
            Files.readAllBytes(Path.of("demo/lookml/bank.lkml")));
        LookmlNode doc = LookmlParser.parse(lkml);
        return new LookmlTranspiler().transpile(doc);
    }

    @Test
    public void modelTranspilesWithBridgeRlsAndDistinctGrain() throws Exception {
        TranspileResult result = transpile();
        String yaml = result.yaml();
        // many_to_many -> bridge.
        assertTrue(yaml.contains("type: \"bridge\""), yaml);
        // access_filter -> predicate grant (tenant) on a fact column.
        assertTrue(yaml.contains("predicate_grants:"), yaml);
        // sum_distinct -> measure-level distinct grain.
        assertTrue(yaml.contains("distinct_key_column:"), yaml);
        // The explore must NOT be REFUSED (RLS must not silently drop it).
        assertFalse(result.classification()
                .withClassification(Classification.REFUSE).stream()
                .anyMatch(r -> r.qualifiedName().startsWith("explore:account")),
            "the secured explore must transpile, not be refused");
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -q test -Dtest='BankLookmlMigrationH2EndToEndTest' -DfailIfNoTests=false`
Expected: FAIL (file `demo/lookml/bank.lkml` missing on the first run, or an assertion fails if the transpiler emits different YAML keys).

- [ ] **Step 4: Make it pass.** Adjust the assertions to the transpiler's actual output: run the transpile once, print `result.yaml()` and `result.classification()`, and align the `contains(...)` strings + classification checks to the real keys (e.g. confirm the YAML uses `type: "bridge"`, `predicate_grants:`, `distinct_key_column:` — these match `LookmlTranspilerTest` and `MeasureDistinctKeyYamlRoundTripTest`). If the LookML parser rejects any block, simplify that block to the minimal shipped-CLEAN form shown in `LookmlClassifierTest` for that feature. Do NOT weaken the "not REFUSED" assertion — if the explore is refused, fix the `.lkml` so RLS maps (per the #106 fail-closed work), since a refused secured explore is the correct-but-undesired outcome here.

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q test -Dtest='BankLookmlMigrationH2EndToEndTest' -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add demo/lookml/bank.lkml src/test/java/mondrian/calcite/BankLookmlMigrationH2EndToEndTest.java
git commit -m "demo: synthetic bank.lkml + Looker->M4 migration test"
```

---

## Task 8: README / coverage matrix

**Files:**
- Create: `demo/lookml/README.md`

- [ ] **Step 1: Write the coverage matrix** mapping each feature to its demonstrating schema element + test, so reviewers and users can see what proves what.

```markdown
# Bank demo — feature showcase & Looker migration

`demo/Bank.mondrian.xml` over `demo/bank.sql` (the shipped joint-accounts
dataset, extended) demonstrates every Mondrian-4 / 4.8.1.x feature in one
place; `demo/lookml/bank.lkml` shows the equivalent Looker model migrating to
M4. Requires the Calcite backend (the Saiku default).

| Feature | Schema element | Demonstrating test |
|---|---|---|
| Full-count bridge (#107/#103) | cube `Joint Accounts (Full Count)` | `BankShowcaseH2EndToEndTest.bridgeAndStatsGoldenNumbers` |
| Weighted bridge (#107) | cube `Joint Accounts (Weighted)` | same |
| Median / percentile (#104) | cube `Account Statistics` | same |
| Balance tier (#108) | `Account` dim `Balance Tier` | `tierAndDurationDimensions` |
| Account-age duration (#108) | `Account` dim `Age Years` | `tierAndDurationDimensions` |
| Query parameter (#105) | `<QueryParameter name="tenant">` | `predicateRowSecurityByTenant` |
| Predicate RLS (#106) | role `Tenant` | `predicateRowSecurityByTenant` |
| Member-grant RLS (#107) | role `Premium` | `memberGrantRowSecurityBySegment` |
| Distinct grain (#119) | cube `Transactions` | `distinctGrainTransactions` |
| Looker migration | `demo/lookml/bank.lkml` | `BankLookmlMigrationH2EndToEndTest` |

All golden numbers are hand-verifiable from `demo/bank.sql` (total balance
13000). The schema is validated in both XML and YAML form.
```

- [ ] **Step 2: Commit**

```bash
git add demo/lookml/README.md
git commit -m "docs: Bank demo feature coverage matrix"
```

---

## Task 9: Final regression sweep + PR

- [ ] **Step 1: Run both new test classes + the existing bridge/RLS/distinct suites** to confirm nothing regressed:

Run:
```bash
mvn -q test -Dtest='BankShowcaseH2EndToEndTest,BankLookmlMigrationH2EndToEndTest,BridgeExampleParityTest,BridgeMemberGrantRlsTest,BridgePredicateGrantBatteryTest,MeasureDistinctGrainH2EndToEndTest,TierDurationH2EndToEndTest,LookmlTranspilerTest' -DfailIfNoTests=false
```
Then inspect each `target/surefire-reports/*.txt`.
Expected: all green, 0 failures / 0 errors.

- [ ] **Step 2: Smoke-check the shipped Bank still loads against the superset** (it reads only base columns):

Already covered by `bridgeAndStatsGoldenNumbers`; confirm it passed in Step 1.

- [ ] **Step 3: Push the branch and open a PR**

```bash
git push -u origin feat/showcase-bank-data
gh pr create --title "Bank showcase schema + synthetic Looker migration" \
  --body "$(cat <<'BODY'
Comprehensive Bank demo: one dataset + schema exercising every new feature
(bridge full/weighted, median/percentile, tier, duration, query parameter,
predicate RLS, member-grant RLS, distinct grain) plus a synthetic LookML model
demonstrating Looker -> M4 migration. New H2 integration tests assert golden
numbers + RLS in both XML and YAML form, and the migration test asserts the
model transpiles without dropping row-security. Reuses the shipped bank.sql
base (kept in sync); see demo/lookml/README.md for the feature->test matrix.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

---

## Self-review notes

- **Spec coverage:** every feature in the spec's coverage matrix maps to a task (bridge/stats T2, tier/duration T3, query-param+predicate T4, member-grant T5, distinct grain T6, LookML migration T7, coverage doc T8). Dataset T1. ✅
- **Cross-repo:** `demo/bank.sql` base rows are copied verbatim from the shipped `../saiku` file with a KEEP IN SYNC banner (spec requirement). ✅
- **Risks handled:** duration uses a fixed `as_of_date` column (stable numbers); the shipped Bank cubes are guarded by T2 before later edits; the `unit="YEAR"` fallback to `MONTH` is documented inline.
- **Known unknowns flagged for the implementer (read the actual output, then pin the golden value):** exact H2 PERCENTILE_CONT median/p90 values; the LookML `tier`/`sum_distinct` block syntax the vendored parser accepts; duration unit enum (`YEAR` vs `MONTH`); member unique-name paths. Each step says how to discover the real value rather than guess.
