/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mondrian.lookml.transpile;

import mondrian.lookml.model.Classification;
import mondrian.lookml.model.ReasonCode;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParser;
import mondrian.olap.Axis;
import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Position;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #101: end-to-end proof of the LookML&rarr;Mondrian-M4 transpiler
 * (clean-port path). A small LookML model is parsed, transpiled to an M4 YAML
 * schema, loaded through the existing {@code M4YamlToXml} converter, and run
 * against an in-memory H2 warehouse — asserting the numbers come out right.
 *
 * <p>Modelled on {@code mondrian.calcite.PercentileH2EndToEndTest}: an H2
 * star (orders fact left-joined many_to_one to users), so a {@code sum} by a
 * conformed-dimension attribute can be checked against hand-computed totals.
 */
public class LookmlTranspilerTest {

  /** orders: 5 rows; users: 3 rows in 2 countries.
   * <pre>
   *   order   user  amount  status      country (via user)
   *   1       10    100     complete    GB
   *   2       10    200     complete    GB
   *   3       20    50      cancelled   GB
   *   4       30    400     complete    US
   *   5       30    250     cancelled   US
   * </pre>
   * total_amount by country: GB=350, US=650; complete-only: GB=300, US=400. */
  private static final String[] DDL = {
    "DROP TABLE IF EXISTS \"orders\"",
    "DROP TABLE IF EXISTS \"users\"",
    "DROP TABLE IF EXISTS \"payments\"",
    "CREATE TABLE \"users\" (\"user_id\" INTEGER, \"country\" VARCHAR(8))",
    "CREATE TABLE \"orders\" (\"order_id\" INTEGER, \"user_id\" INTEGER,"
        + " \"amount\" INTEGER, \"status\" VARCHAR(16))",
    // payments: a second fact conformed by the same users (country) dimension.
    "CREATE TABLE \"payments\" (\"payment_id\" INTEGER, \"user_id\" INTEGER,"
        + " \"paid\" INTEGER)",
    "INSERT INTO \"users\" VALUES (10,'GB'),(20,'GB'),(30,'US')",
    "INSERT INTO \"orders\" VALUES"
        + " (1,10,100,'complete'),(2,10,200,'complete'),"
        + " (3,20,50,'cancelled'),(4,30,400,'complete'),"
        + " (5,30,250,'cancelled')",
    // payments by user: GB users (10,20) → 30+70+20 = 120; US user (30) → 80.
    "INSERT INTO \"payments\" VALUES"
        + " (1,10,30),(2,10,70),(3,20,20),(4,30,80)",
    // #119: an already-fanned-out fact — one row per (basket, line), repeating
    // the basket's amount on every line. A sum_distinct on basket_id must
    // de-dup to the true per-basket total.
    //   basket 1: amount 100, country GB, 3 lines
    //   basket 2: amount 50,  country GB, 1 line
    //   basket 3: amount 300, country US, 2 lines
    //   distinct total = 450; naive SUM over the fan-out = 950
    "DROP TABLE IF EXISTS \"basket_line\"",
    "CREATE TABLE \"basket_line\" (\"basket_id\" INTEGER, \"line\" VARCHAR(4),"
        + " \"country\" VARCHAR(8), \"amount\" INTEGER)",
    "INSERT INTO \"basket_line\" VALUES"
        + " (1,'a','GB',100),(1,'b','GB',100),(1,'c','GB',100),"
        + " (2,'a','GB',50),(3,'a','US',300),(3,'b','US',300)",
  };

  /** The core fixture: single-base star, two clean measures + a filtered
   * measure, two dimensions (one on the joined view with value_format/label). */
  private static final String CORE_LOOKML =
      "view: orders {\n"
      + "  sql_table_name: orders ;;\n"
      + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
      + "  measure: total_amount { type: sum sql: ${TABLE}.amount ;;"
      + "    value_format_name: usd label: \"Total Amount\" }\n"
      + "  measure: order_count { type: count }\n"
      + "  measure: complete_amount { type: sum sql: ${TABLE}.amount ;;\n"
      + "    filters: [status: \"complete\"] }\n"
      + "}\n"
      + "view: users {\n"
      + "  sql_table_name: users ;;\n"
      + "  dimension: country { type: string sql: ${TABLE}.country ;;\n"
      + "    value_format: \"0.00\" label: \"User Country\""
      + "    description: \"Country of the user\" }\n"
      + "}\n"
      + "explore: orders {\n"
      + "  join: users { type: left_outer relationship: many_to_one\n"
      + "    sql_on: ${orders.user_id} = ${users.user_id} ;; }\n"
      + "}\n";

  private static final String H2_URL =
      "jdbc:h2:mem:lookml_e2e;DB_CLOSE_DELAY=-1";

  @BeforeAll
  public static void boot() throws Exception {
    // Same classloader/server warmup the other H2 query tests use.
    mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
    Class.forName("org.h2.Driver");
    try (java.sql.Connection c =
             java.sql.DriverManager.getConnection(H2_URL, "sa", "");
         Statement st = c.createStatement()) {
      for (String sql : DDL) {
        st.execute(sql);
      }
    }
  }

  @AfterAll
  public static void close() {
    // H2 mem DB closed by DB_CLOSE_DELAY=-1 lifecycle with the JVM.
  }

  private static TranspileResult transpile(String lookml) {
    final LookmlNode doc = LookmlParser.parse(lookml);
    return new LookmlTranspiler().transpile(doc);
  }

  private static Connection connect(String catalogXml) {
    return connect(catalogXml, null);
  }

  private static Connection connect(String catalogXml, String role) {
    Util.PropertyList props = new Util.PropertyList();
    props.put("Provider", "mondrian");
    props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
    props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
    props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
    props.put(RolapConnectionProperties.JdbcPassword.name(), "");
    props.put("UseSchemaPool", "false");
    if (role != null) {
      props.put(RolapConnectionProperties.Role.name(), role);
    }
    props.put(RolapConnectionProperties.CatalogContent.name(), catalogXml);
    return DriverManager.getConnection(props, null, null);
  }

  /** "row|col" → value. */
  private Map<String, Double> grid(Connection conn, String mdx) {
    Query q = conn.parseQuery(mdx);
    Result r = conn.execute(q);
    Map<String, Double> out = new LinkedHashMap<>();
    Axis cols = r.getAxes()[0];
    Axis rows = r.getAxes()[1];
    int ri = 0;
    for (Position rp : rows.getPositions()) {
      int ci = 0;
      for (Position cp : cols.getPositions()) {
        Object v = r.getCell(new int[]{ci, ri}).getValue();
        out.put(rp.get(0).getName() + "|" + cp.get(0).getName(),
            v == null ? null : ((Number) v).doubleValue());
        ci++;
      }
      ri++;
    }
    r.close();
    return out;
  }

  // --- Test 1: end-to-end star (the must-pass acceptance test) ------------

  @Test
  public void endToEndStarTotalAmountByCountry() {
    TranspileResult result = transpile(CORE_LOOKML);
    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[total_amount]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [orders]");
      assertEquals(350.0, g.get("GB|total_amount"), 0.001);
      assertEquals(650.0, g.get("US|total_amount"), 0.001);
    } finally {
      conn.close();
    }
  }

  @Test
  public void endToEndStarOrderCount() {
    TranspileResult result = transpile(CORE_LOOKML);
    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[order_count]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [orders]");
      assertEquals(3.0, g.get("GB|order_count"), 0.001);
      assertEquals(2.0, g.get("US|order_count"), 0.001);
    } finally {
      conn.close();
    }
  }

  // --- Test 2: aggregator mapping -----------------------------------------

  @Test
  public void aggregatorMapping() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: m_sum { type: sum sql: ${TABLE}.amount ;; }\n"
        + "  measure: m_min { type: min sql: ${TABLE}.amount ;; }\n"
        + "  measure: m_max { type: max sql: ${TABLE}.amount ;; }\n"
        + "  measure: m_avg { type: average sql: ${TABLE}.amount ;; }\n"
        + "  measure: m_cd { type: count_distinct sql: ${TABLE}.user_id ;; }\n"
        + "}\n"
        + "explore: f { }\n";
    TranspileResult result = transpile(lookml);
    String yaml = result.yaml();
    assertTrue(yaml.contains("aggregator: \"sum\""), yaml);
    assertTrue(yaml.contains("aggregator: \"min\""), yaml);
    assertTrue(yaml.contains("aggregator: \"max\""), yaml);
    assertTrue(yaml.contains("aggregator: \"avg\""), yaml);
    assertTrue(yaml.contains("aggregator: \"distinct-count\""), yaml);
  }

  /** #117: a sum_distinct / average_distinct keyed on the base view primary
   * key emits a plain SUM / AVG measure (de-dup is a no-op on the fact grain),
   * with NO distinct_key_column attribute. #119: a same-view non-PK distinct
   * key emits the attribute (a measure-level distinct grain). A cross-view key
   * is refused and not emitted. */
  @Test
  public void distinctKeyAggregatorMapping() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  dimension: basket { type: number sql: ${TABLE}.basket_id ;; }\n"
        + "  measure: total_d {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${id} ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "  measure: avg_d {\n"
        + "    type: average_distinct\n"
        + "    sql_distinct_key: ${TABLE}.order_id ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "  measure: basket_d {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${TABLE}.basket_id ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "  measure: bad_d {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${other.k} ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n"
        + "explore: f { }\n";
    TranspileResult result = transpile(lookml);
    String yaml = result.yaml();
    // total_d → sum, avg_d → avg, both on the amount column.
    assertTrue(yaml.contains("name: \"total_d\""), yaml);
    assertTrue(yaml.contains("aggregator: \"sum\""), yaml);
    assertTrue(yaml.contains("name: \"avg_d\""), yaml);
    assertTrue(yaml.contains("aggregator: \"avg\""), yaml);
    // #119: basket_d (same-view non-PK key) emits a distinct_key_column.
    assertTrue(yaml.contains("name: \"basket_d\""), yaml);
    assertTrue(yaml.contains("distinct_key_column: \"basket_id\""), yaml);
    // PK-keyed measures collapse to plain agg: no distinct_key_column for the
    // order_id key (only basket_id appears).
    assertFalse(yaml.contains("distinct_key_column: \"order_id\""), yaml);
    // bad_d (cross-view distinct key) is refused: not emitted.
    assertFalse(yaml.contains("bad_d"), yaml);
  }

  /** #124: a LookML many-to-many bridge two-hop emits a {@code bridge}
   * dimension_link carrying the bridge table and the three bridge columns, with
   * the dim view (not the bridge view) as the conformed dimension. No allocation
   * weight is modelled, so no aggregation/weight attribute is emitted (the
   * engine defaults to fullCount). */
  @Test
  public void bridgeTwoHopEmitsBridgeLink() {
    String lookml =
        "view: accounts {\n"
        + "  sql_table_name: br_accounts ;;\n"
        + "  dimension: account_id { type: number primary_key: yes"
        + "    sql: ${TABLE}.account_id ;; }\n"
        + "  measure: balance { type: sum sql: ${TABLE}.balance ;; }\n"
        + "}\n"
        + "view: owners {\n"
        + "  sql_table_name: br_owners ;;\n"
        + "  dimension: account_id { sql: ${TABLE}.account_id ;; }\n"
        + "  dimension: customer_id { sql: ${TABLE}.customer_id ;; }\n"
        + "}\n"
        + "view: customers {\n"
        + "  sql_table_name: br_customers ;;\n"
        + "  dimension: customer_id { primary_key: yes"
        + "    sql: ${TABLE}.customer_id ;; }\n"
        + "  dimension: customer_name { sql: ${TABLE}.customer_name ;; }\n"
        + "}\n"
        + "explore: accounts {\n"
        + "  join: owners { type: left_outer relationship: one_to_many\n"
        + "    sql_on: ${accounts.account_id} = ${owners.account_id} ;; }\n"
        + "  join: customers { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${owners.customer_id} = ${customers.customer_id} ;; }\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    String yaml = result.yaml();
    assertTrue(yaml.contains("type: \"bridge\""), yaml);
    assertTrue(yaml.contains("dimension: \"customers\""), yaml);
    assertTrue(yaml.contains("bridge_table: \"br_owners\""), yaml);
    assertTrue(
        yaml.contains("fact_foreign_key_column: \"account_id\""), yaml);
    assertTrue(yaml.contains("bridge_fact_key_column: \"account_id\""), yaml);
    assertTrue(
        yaml.contains("bridge_dimension_key_column: \"customer_id\""), yaml);
    // fullCount default: no aggregation/weight attributes emitted.
    assertFalse(yaml.contains("aggregation:"), yaml);
    assertFalse(yaml.contains("weight_column:"), yaml);
    // The bridge view itself is NOT emitted as a conformed dimension.
    assertFalse(yaml.contains("dimension: \"owners\""), yaml);
  }

  /** #124: a bridge two-hop with a COMPOUND fact→bridge key cannot recover a
   * single bridge column pair, so the explore stays REFUSE — no cube, no bridge
   * link is emitted (never a silently-wrong bridge). */
  @Test
  public void compoundKeyBridgeNotEmitted() {
    String lookml =
        "view: accounts {\n"
        + "  sql_table_name: br_accounts ;;\n"
        + "  dimension: account_id { type: number primary_key: yes"
        + "    sql: ${TABLE}.account_id ;; }\n"
        + "  measure: balance { type: sum sql: ${TABLE}.balance ;; }\n"
        + "}\n"
        + "view: owners {\n"
        + "  sql_table_name: br_owners ;;\n"
        + "  dimension: account_id { sql: ${TABLE}.account_id ;; }\n"
        + "  dimension: customer_id { sql: ${TABLE}.customer_id ;; }\n"
        + "}\n"
        + "view: customers {\n"
        + "  sql_table_name: br_customers ;;\n"
        + "  dimension: customer_id { primary_key: yes"
        + "    sql: ${TABLE}.customer_id ;; }\n"
        + "}\n"
        + "explore: accounts {\n"
        + "  join: owners { type: left_outer relationship: many_to_many\n"
        + "    sql_on: ${accounts.account_id} = ${owners.account_id}\n"
        + "      AND ${accounts.tenant} = ${owners.tenant} ;; }\n"
        + "  join: customers { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${owners.customer_id} = ${customers.customer_id} ;; }\n"
        + "}\n";
    String yaml = transpile(lookml).yaml();
    assertFalse(yaml.contains("type: \"bridge\""), yaml);
    // The explore is REFUSED: no cube emitted for it.
    assertFalse(yaml.contains("accounts:"), yaml);
  }

  /** #119 end-to-end: a LookML sum_distinct on a same-view NON-PK key
   * (basket_id) over an already-fanned-out fact transpiles to an M4
   * measure-level distinct grain, loads, and returns the DE-DUPLICATED total
   * (450), not the fanned-out one (950) — the exact case #117 could not do. */
  @Test
  public void endToEndDistinctGrainNonPrimaryKey() {
    String lookml =
        "view: baskets {\n"
        + "  sql_table_name: basket_line ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "  measure: distinct_amount {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${TABLE}.basket_id ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "  measure: naive_amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: baskets { }\n";
    TranspileResult result = transpile(lookml);
    assertTrue(result.yaml().contains("distinct_key_column: \"basket_id\""),
        result.yaml());
    Connection conn = connect(result.toXml());
    try {
      // Distinct de-dups on basket_id: 100+50+300 = 450; naive double-counts.
      assertEquals(450.0, scalar(conn,
          "SELECT {[Measures].[distinct_amount]} ON COLUMNS FROM [baskets]"),
          0.001, "distinct_amount de-dups to the true per-basket total");
      assertEquals(950.0, scalar(conn,
          "SELECT {[Measures].[naive_amount]} ON COLUMNS FROM [baskets]"),
          0.001, "naive_amount double-counts the fanned-out rows");
    } finally {
      conn.close();
    }
  }

  /** Single cell from a COLUMNS-only query. */
  private Double scalar(Connection conn, String mdx) {
    Query q = conn.parseQuery(mdx);
    Result r = conn.execute(q);
    Object v = r.getCell(new int[]{0}).getValue();
    r.close();
    return v == null ? null : ((Number) v).doubleValue();
  }

  // --- Test 3: filtered measure → calculated member -----------------------

  @Test
  public void filteredMeasureReturnsFilteredTotal() {
    TranspileResult result = transpile(CORE_LOOKML);
    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[complete_amount]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [orders]");
      // complete-only amounts: GB = 100+200 = 300; US = 400.
      assertEquals(300.0, g.get("GB|complete_amount"), 0.001);
      assertEquals(400.0, g.get("US|complete_amount"), 0.001);
    } finally {
      conn.close();
    }
  }

  // --- Test 4: provenance --------------------------------------------------

  @Test
  public void provenanceLinksLookmlToM4() {
    TranspileResult result = transpile(CORE_LOOKML);
    ProvenanceMap prov = result.provenance();
    assertTrue(prov.m4Path("orders.total_amount").isPresent(),
        "missing provenance for orders.total_amount: " + prov);
    assertTrue(prov.m4Path("orders.total_amount").get().contains("total_amount"),
        prov.toString());
    assertTrue(prov.m4Path("users.country").isPresent(),
        "missing provenance for users.country: " + prov);
    assertTrue(prov.m4Path("explore:orders").isPresent(),
        "missing provenance for the explore cube: " + prov);
  }

  // --- Test 5: REFUSE skipped ---------------------------------------------

  @Test
  public void refusedMeasureIsNotEmitted() {
    // A fan-out sum: orders explore fans out across one_to_many to items,
    // so the additive sum on orders is REFUSED by the classifier.
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
        + "  measure: top_amount { type: max sql: ${TABLE}.amount ;; }\n"
        + "  measure: fanned_sum { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "view: items {\n"
        + "  sql_table_name: users ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  join: items { type: left_outer relationship: one_to_many\n"
        + "    sql_on: ${orders.order_id} = ${items.user_id} ;; }\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    // The classifier refused fanned_sum (additive sum fanned out one_to_many).
    assertTrue(result.classification().withClassification(Classification.REFUSE)
        .stream().anyMatch(r -> r.qualifiedName().equals("orders.fanned_sum")),
        result.classification().toString());
    // The emitted schema must not contain the refused measure...
    assertFalse(result.yaml().contains("fanned_sum"), result.yaml());
    // ...and nothing was recorded in provenance for it.
    assertTrue(result.provenance().m4Path("orders.fanned_sum").isEmpty(),
        result.provenance().toString());
    // The non-additive max measure (fan-out-safe, CLEAN) is still emitted.
    assertTrue(result.yaml().contains("top_amount"), result.yaml());
  }

  // --- #104 median / percentile -------------------------------------------

  /** A type: median / percentile measure emits an M4 median/percentile
   * aggregator (with the percentile attribute), #104. */
  @Test
  public void percentileFamilyEmitsAggregators() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: med { type: median sql: ${TABLE}.amount ;; }\n"
        + "  measure: p90 { type: percentile percentile: 90"
        + "    sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: f { }\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("aggregator: \"median\""), yaml);
    assertTrue(yaml.contains("aggregator: \"percentile\""), yaml);
    assertTrue(yaml.contains("percentile: \"90\"")
        || yaml.contains("percentile: 90"), yaml);
  }

  /** End-to-end: a percentile measure loads and computes on H2
   * (PERCENTILE_CONT-capable). amounts = 100,200,50,400,250 → P50 = 200. */
  @Test
  public void endToEndPercentileMedian() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
        + "  measure: median_amount { type: median sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: orders { }\n";
    TranspileResult result = transpile(lookml);
    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[median_amount]} ON COLUMNS,\n"
          + " {[status].[status].Members} ON ROWS\n"
          + "FROM [orders]");
      // complete: 100,200,400 → median 200; cancelled: 50,250 → median 150.
      assertEquals(200.0, g.get("complete|median_amount"), 0.001);
      assertEquals(150.0, g.get("cancelled|median_amount"), 0.001);
    } finally {
      conn.close();
    }
  }

  // --- #117 sum_distinct / average_distinct -------------------------------

  /** End-to-end: a sum_distinct keyed on the fact primary key (one row per
   * order) equals a plain SUM — the de-dup is a no-op on the fact grain. Run
   * through a many_to_one join to a conformed country dimension; totals match
   * the plain {@code total_amount} acceptance test (GB=350, US=650). #117. */
  @Test
  public void endToEndSumDistinctOnPrimaryKey() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: distinct_total {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${id} ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n"
        + "view: users {\n"
        + "  sql_table_name: users ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  join: users { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${orders.user_id} = ${users.user_id} ;; }\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    assertTrue(result.yaml().contains("name: \"distinct_total\""),
        result.yaml());
    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[distinct_total]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [orders]");
      assertEquals(350.0, g.get("GB|distinct_total"), 0.001);
      assertEquals(650.0, g.get("US|distinct_total"), 0.001);
    } finally {
      conn.close();
    }
  }

  // --- #108 tier / duration -----------------------------------------------

  /** A type: tier dimension emits an M4 attribute with a <Tier> + bins. */
  @Test
  public void tierDimensionEmitsTierBins() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: size_tier { type: tier tiers: [100, 300]\n"
        + "    sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: f { }\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("tier:"), yaml);
    assertTrue(yaml.contains("bins:"), yaml);
    assertTrue(yaml.contains("boundary: \"100\"")
        || yaml.contains("boundary: 100"), yaml);
  }

  /** A duration dimension_group emits an M4 attribute with a <Duration>. */
  @Test
  public void durationDimensionGroupEmitsDuration() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension_group: lead { type: duration\n"
        + "    intervals: [day]\n"
        + "    sql_start: ${TABLE}.order_date ;;\n"
        + "    sql_end: ${TABLE}.ship_date ;; }\n"
        + "}\n"
        + "explore: f { }\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("duration:"), yaml);
    assertTrue(yaml.contains("start_column: \"order_date\""), yaml);
    assertTrue(yaml.contains("end_column: \"ship_date\""), yaml);
  }

  /** End-to-end: a tier dimension bins rows and counts per bin on H2.
   * amounts = 100,200,50,400,250; tiers [100,300] → bins &lt;100 (50),
   * 100–300 (100,200,250), &ge;300 (400). */
  @Test
  public void endToEndTierBinsCount() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: amount_tier { type: tier tiers: [100, 300]\n"
        + "    sql: ${TABLE}.amount ;; }\n"
        + "  measure: order_count { type: count }\n"
        + "}\n"
        + "explore: orders { }\n";
    TranspileResult result = transpile(lookml);
    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[order_count]} ON COLUMNS,\n"
          + " {[amount_tier].[amount_tier].Members} ON ROWS\n"
          + "FROM [orders]");
      assertEquals(1.0, g.get("< 100|order_count"), 0.001, g.toString());
      assertEquals(3.0, g.get("100–300|order_count"), 0.001, g.toString());
      assertEquals(1.0, g.get("≥ 300|order_count"), 0.001, g.toString());
    } finally {
      conn.close();
    }
  }

  // --- #105 parameter declaration → QueryParameter ------------------------

  /** A bounded parameter declaration emits a top-level <QueryParameter>. */
  @Test
  public void parameterDeclarationEmitsQueryParameter() {
    // Realistic LookML: `default_value:` is a quoted scalar (the parser no
    // longer treats it as a code-valued key — see the parser-robustness fix
    // that removed it from CODE_PROPERTY_NAMES).
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  parameter: region {\n"
        + "    type: string\n"
        + "    default_value: \"EAST\"\n"
        + "    allowed_value: { label: \"East\" value: \"EAST\" }\n"
        + "    allowed_value: { label: \"West\" value: \"WEST\" }\n"
        + "  }\n"
        + "  measure: c { type: count }\n"
        + "}\n"
        + "explore: f { }\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("parameters:"), yaml);
    assertTrue(yaml.contains("name: \"region\""), yaml);
    assertTrue(yaml.contains("default_value: \"EAST\""), yaml);
    assertTrue(yaml.contains("allowed_values:"), yaml);
    assertTrue(yaml.contains("WEST"), yaml);
  }

  // --- #106 arbitrary access_filter → PredicateGrant ----------------------

  /** An access_filter on an arbitrary fact column emits a generated Role with
   * a PredicateGrant bound to a generated query parameter. */
  @Test
  public void arbitraryAccessFilterEmitsPredicateGrant() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  access_filter: { field: orders.tenant_id"
        + "    user_attribute: tenant_id }\n"
        + "}\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("predicate_grants:"), yaml);
    assertTrue(yaml.contains("measure_group:"), yaml);
    assertTrue(yaml.contains("column: \"tenant_id\""), yaml);
    assertTrue(yaml.contains("roles:") || yaml.contains("parameters:"), yaml);
  }

  // --- #118 bounded Liquid: user-attribute access_filter → PredicateGrant -

  /** A {{ _user_attributes['region'] }} reference in an access_filter on a fact
   * column emits a <PredicateGrant> bound to a generated session.region
   * <QueryParameter> (#118 → reuses the #106 RowSecurity emitter). */
  @Test
  public void userAttributeLiquidAccessFilterEmitsPredicateGrant() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  access_filter: { field: orders.region\n"
        + "    value: \"{{ _user_attributes['region'] }}\" }\n"
        + "}\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("predicate_grants:"), yaml);
    assertTrue(yaml.contains("column: \"region\""), yaml);
    // the bound parameter is the session.region user attribute (#118).
    assertTrue(yaml.contains("session.region"), yaml);
  }

  /** A declared bounded parameter used via {% parameter %} still emits its
   * top-level <QueryParameter> (#105); the use DEGRADEs but the parameter is
   * mapped (#118). */
  @Test
  public void declaredParameterUsedInLiquidStillEmitsQueryParameter() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  parameter: region {\n"
        + "    type: unquoted\n"
        + "    allowed_value: { value: \"EAST\" }\n"
        + "    allowed_value: { value: \"WEST\" }\n"
        + "  }\n"
        + "  dimension: dyn { sql: {% parameter region %} ;; }\n"
        + "  measure: c { type: count }\n"
        + "}\n"
        + "explore: f { }\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("parameters:"), yaml);
    assertTrue(yaml.contains("name: \"region\""), yaml);
    assertTrue(yaml.contains("EAST"), yaml);
  }

  // --- #103 fan-out symmetric aggregate (with declared fact PK) -----------

  /** A sum on the one-side of a one_to_many, with a declared primary key, is
   * emitted (the fact table declares its grain key so symmetric aggregation
   * applies). The fact table's physical key is declared. */
  @Test
  public void fanOutSumWithPkEmitsFactGrainKey() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: revenue { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "view: items {\n"
        + "  sql_table_name: users ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  join: items { type: left_outer relationship: one_to_many\n"
        + "    sql_on: ${orders.order_id} = ${items.user_id} ;; }\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    // revenue classified CLEAN (symmetric-safe) and emitted.
    assertTrue(result.yaml().contains("revenue"), result.yaml());
    // the fact table declares its grain key (order_id) in the physical schema.
    assertTrue(result.yaml().contains("order_id"), result.yaml());
  }

  // --- #115 gap 1: dimension-key access_filter → member-grant Role ---------

  /** An access_filter on a modelled dimension key emits a Role with a
   * member-level HierarchyGrant, and a static {@code value:} bakes the granted
   * member. Two users (two generated roles) see only their granted country's
   * data (#115). orders→users star: GB total = 350, US total = 650. */
  @Test
  public void dimensionKeyAccessFilterRestrictsToGrantedMembers() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: total_amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "view: users {\n"
        + "  sql_table_name: users ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n"
        + "explore: orders_gb {\n"
        + "  from: orders\n"
        + "  join: users { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${orders.user_id} = ${users.user_id} ;; }\n"
        + "  access_filter: { field: users.country value: \"GB\" }\n"
        + "}\n"
        + "explore: orders_us {\n"
        + "  from: orders\n"
        + "  join: users { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${orders.user_id} = ${users.user_id} ;; }\n"
        + "  access_filter: { field: users.country value: \"US\" }\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    String yaml = result.yaml();
    // A member-grant role is emitted (not a predicate grant) for the dim key.
    assertTrue(yaml.contains("orders_gb_dim_security"), yaml);
    assertTrue(yaml.contains("hierarchies:"), yaml);
    assertTrue(yaml.contains("member: \"[users].[country].[GB]\""), yaml);
    assertTrue(yaml.contains("member: \"[users].[country].[US]\""), yaml);
    assertFalse(yaml.contains("predicate_grants:"), yaml);

    String xml = result.toXml();
    // GB role sees only GB (350); US is hidden.
    Connection gb = connect(xml, "orders_gb_dim_security");
    try {
      Map<String, Double> g = grid(gb,
          "SELECT {[Measures].[total_amount]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [orders_gb]");
      assertEquals(350.0, g.get("GB|total_amount"), 0.001, g.toString());
      assertFalse(g.containsKey("US|total_amount"), g.toString());
    } finally {
      gb.close();
    }
    // US role sees only US (650); GB is hidden.
    Connection us = connect(xml, "orders_us_dim_security");
    try {
      Map<String, Double> g = grid(us,
          "SELECT {[Measures].[total_amount]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [orders_us]");
      assertEquals(650.0, g.get("US|total_amount"), 0.001, g.toString());
      assertFalse(g.containsKey("GB|total_amount"), g.toString());
    } finally {
      us.close();
    }
  }

  // --- #115 gap 5: conformed multi-base → one cube, multiple MeasureGroups -

  /** A conformed two-fact explore (orders + payments, both joined to users)
   * becomes one cube with a MeasureGroup per fact base over the shared
   * conformed country dimension. A measure from each base slices by country
   * (#115). orders total by country: GB=350, US=650; payments: GB=120, US=80. */
  @Test
  public void conformedMultiBaseEmitsMeasureGroupPerFact() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: total_amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "view: payments {\n"
        + "  sql_table_name: payments ;;\n"
        + "  measure: total_paid { type: sum sql: ${TABLE}.paid ;; }\n"
        + "}\n"
        + "view: users {\n"
        + "  sql_table_name: users ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n"
        + "explore: sales {\n"
        + "  from: orders\n"
        + "  join: users { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${orders.user_id} = ${users.user_id} ;; }\n"
        + "  join: payments { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${payments.user_id} = ${users.user_id} ;; }\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    String yaml = result.yaml();
    // One cube, two measure groups (orders + payments) over conformed users.
    assertTrue(yaml.contains("name: \"orders\""), yaml);
    assertTrue(yaml.contains("name: \"payments\""), yaml);
    assertTrue(yaml.contains("total_amount"), yaml);
    assertTrue(yaml.contains("total_paid"), yaml);
    // Both fact groups link to the conformed users dimension.
    assertTrue(yaml.contains("dimension: \"users\""), yaml);

    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> orders = grid(conn,
          "SELECT {[Measures].[total_amount]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [sales]");
      assertEquals(350.0, orders.get("GB|total_amount"), 0.001,
          orders.toString());
      assertEquals(650.0, orders.get("US|total_amount"), 0.001,
          orders.toString());
      Map<String, Double> pay = grid(conn,
          "SELECT {[Measures].[total_paid]} ON COLUMNS,\n"
          + " [users].[country].Members ON ROWS\n"
          + "FROM [sales]");
      assertEquals(120.0, pay.get("GB|total_paid"), 0.001, pay.toString());
      assertEquals(80.0, pay.get("US|total_paid"), 0.001, pay.toString());
    } finally {
      conn.close();
    }
  }

  // --- #115 gap 4: drill_fields → drillthrough RETURN column set -----------

  /** An explore's {@code drill_fields} maps to the M4 drillthrough RETURN set,
   * carried as a cube-level annotation (M4 has no &lt;DrillThrough&gt; element).
   * The set is asserted and the cube still loads (#115). */
  @Test
  public void drillFieldsEmitDrillthroughReturnSet() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
        + "  measure: total_amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  drill_fields: [status, total_amount]\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    String yaml = result.yaml();
    assertTrue(yaml.contains("annotations:"), yaml);
    assertTrue(yaml.contains("drill_fields: \"status,total_amount\""), yaml);
    assertTrue(result.provenance().m4Path("explore:orders.drill_fields")
        .isPresent(), result.provenance().toString());
    // The cube still loads and queries.
    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[total_amount]} ON COLUMNS,\n"
          + " {[status].[status].Members} ON ROWS\n"
          + "FROM [orders]");
      assertEquals(700.0, g.get("complete|total_amount"), 0.001, g.toString());
    } finally {
      conn.close();
    }
  }

  /** A view-level {@code drill_fields} (no explore-level one) is used as the
   * cube's drillthrough RETURN set (#115). */
  @Test
  public void viewLevelDrillFieldsUsedWhenExploreHasNone() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
        + "  measure: total_amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "  drill_fields: [status]\n"
        + "}\n"
        + "explore: orders { }\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("drill_fields: \"status\""), yaml);
  }

  // --- #115 gap 3: derived table → <Query> SQL-backed physical table ------

  /** A derived_table view emits a <Query> physical table (SQL-backed), and a
   * dimension/measure over it loads and queries end-to-end (#115). The derived
   * SQL aggregates orders by status; a sum over it matches hand totals. */
  @Test
  public void derivedTableEmitsQueryAndLoads() {
    String lookml =
        "view: order_summary {\n"
        + "  derived_table: {\n"
        + "    sql: SELECT \"status\" AS \"status\","
        + " SUM(\"amount\") AS \"total\""
        + " FROM \"orders\" GROUP BY \"status\" ;;\n"
        + "    datagroup_trigger: nightly\n"
        + "  }\n"
        + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
        + "  measure: total_sum { type: sum sql: ${TABLE}.total ;; }\n"
        + "}\n"
        + "explore: order_summary { }\n";
    TranspileResult result = transpile(lookml);
    String yaml = result.yaml();
    // A <Query> physical table (not a plain table) with the derived SQL.
    assertTrue(yaml.contains("queries:"), yaml);
    assertTrue(yaml.contains("alias: \"order_summary\""), yaml);
    assertTrue(yaml.contains("expression:"), yaml);
    assertTrue(yaml.contains("GROUP BY"), yaml);
    // Persistence policy dropped → DEGRADE note recorded.
    assertTrue(result.classification()
            .withClassification(Classification.DEGRADE).stream()
            .anyMatch(r -> r.qualifiedName().equals("view:order_summary")),
        result.classification().toString());

    Connection conn = connect(result.toXml());
    try {
      Map<String, Double> g = grid(conn,
          "SELECT {[Measures].[total_sum]} ON COLUMNS,\n"
          + " {[status].[status].Members} ON ROWS\n"
          + "FROM [order_summary]");
      // orders grouped by status: complete = 100+200+400 = 700;
      // cancelled = 50+250 = 300.
      assertEquals(700.0, g.get("complete|total_sum"), 0.001, g.toString());
      assertEquals(300.0, g.get("cancelled|total_sum"), 0.001, g.toString());
    } finally {
      conn.close();
    }
  }

  // --- #115 gap 2: Looker named formats → Mondrian format_string ----------

  /** A measure's {@code value_format_name} (usd / percent_2 / decimal_0 / gbp)
   * is translated to the Mondrian format-string mask, while a literal
   * {@code value_format} mask still passes straight through (#115). */
  @Test
  public void namedValueFormatsTranslateToMondrianMasks() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: m_usd { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format_name: usd }\n"
        + "  measure: m_usd0 { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format_name: usd_0 }\n"
        + "  measure: m_pct { type: average sql: ${TABLE}.amount ;;"
        + "    value_format_name: percent_2 }\n"
        + "  measure: m_dec { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format_name: decimal_0 }\n"
        + "  measure: m_gbp { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format_name: gbp }\n"
        + "  measure: m_lit { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format: \"0.000\" }\n"
        + "}\n"
        + "explore: f { }\n";
    String yaml = transpile(lookml).yaml();
    assertTrue(yaml.contains("format_string: \"$#,##0.00\""), yaml);
    assertTrue(yaml.contains("format_string: \"$#,##0\""), yaml);
    assertTrue(yaml.contains("format_string: \"0.00%\""), yaml);
    assertTrue(yaml.contains("format_string: \"#,##0\""), yaml);
    assertTrue(yaml.contains("format_string: \"\\xA3#,##0.00\"")
        || yaml.contains("format_string: \"£#,##0.00\""), yaml);
    // literal value_format mask still passes straight through.
    assertTrue(yaml.contains("format_string: \"0.000\""), yaml);
  }

  /** An unknown {@code value_format_name} is kept verbatim as the
   * format_string and the field DEGRADEs (#115). */
  @Test
  public void unknownNamedValueFormatKeptVerbatimAndDegrades() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: m { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format_name: my_custom_format }\n"
        + "}\n"
        + "explore: f { }\n";
    TranspileResult result = transpile(lookml);
    assertTrue(result.yaml().contains("format_string: \"my_custom_format\""),
        result.yaml());
    assertTrue(result.classification()
            .withClassification(Classification.DEGRADE).stream()
            .anyMatch(r -> r.qualifiedName().equals("f.m")),
        result.classification().toString());
  }

  // --- Test 6: golden-compare the core YAML (stable shape) ----------------

  @Test
  public void coreYamlGoldenShape() {
    TranspileResult result = transpile(CORE_LOOKML);
    String yaml = result.yaml();
    // Structural anchors — not a byte-for-byte snapshot, but the stable
    // shape the #102 report and downstream tooling depend on.
    assertTrue(yaml.contains("physical_schema:"), yaml);
    assertTrue(yaml.contains("name: \"orders\""), yaml);
    assertTrue(yaml.contains("name: \"users\""), yaml);
    assertTrue(yaml.contains("cubes:"), yaml);
    assertTrue(yaml.contains("measure_groups:"), yaml);
    assertTrue(yaml.contains("type: \"foreign_key\""), yaml);
    assertTrue(yaml.contains("dimension: \"users\""), yaml);
    assertTrue(yaml.contains("calculated_members:"), yaml);
    // value_format → format_string; label → caption.
    assertTrue(yaml.contains("format_string:"), yaml);
    assertTrue(yaml.contains("caption: \"User Country\""), yaml);
  }

  // --- #106 fail-closed: unmappable access_filter must not ship open -------

  /** An access_filter whose {@code field:} is a Liquid-templated reference maps
   * to neither a dimension-key grant nor a usable predicate column. Emitting
   * the cube would leave it with NO row security for a declared filter, so the
   * importer must REFUSE the explore: no cube and no predicate grant are
   * emitted. Fail-closed — the data is never exposed via an unsecured cube. */
  @Test
  public void unmappableLiquidAccessFilterRefusesCubeNotEmittedOpen() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  access_filter: { field: \"{{ _parameters.scope_field }}\"\n"
        + "    user_attribute: scope }\n"
        + "}\n";
    TranspileResult result = transpile(lookml);
    // The explore is REFUSED by the classifier (#106 fail-closed).
    assertTrue(result.classification()
            .withClassification(Classification.REFUSE).stream()
            .anyMatch(r -> r.reasonCode()
                == ReasonCode.REFUSE_ACCESS_FILTER_UNMAPPABLE),
        "unmappable access_filter must be recorded REFUSE");
    String yaml = result.yaml();
    // No queryable cube and no grant-free predicate role: never shipped open.
    assertFalse(yaml.contains("cubes:"), yaml);
    assertFalse(yaml.contains("predicate_grants:"), yaml);
  }
}

// End LookmlTranspilerTest.java
