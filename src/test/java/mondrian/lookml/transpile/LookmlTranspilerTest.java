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
    "CREATE TABLE \"users\" (\"user_id\" INTEGER, \"country\" VARCHAR(8))",
    "CREATE TABLE \"orders\" (\"order_id\" INTEGER, \"user_id\" INTEGER,"
        + " \"amount\" INTEGER, \"status\" VARCHAR(16))",
    "INSERT INTO \"users\" VALUES (10,'GB'),(20,'GB'),(30,'US')",
    "INSERT INTO \"orders\" VALUES"
        + " (1,10,100,'complete'),(2,10,200,'complete'),"
        + " (3,20,50,'cancelled'),(4,30,400,'complete'),"
        + " (5,30,250,'cancelled')",
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
    Util.PropertyList props = new Util.PropertyList();
    props.put("Provider", "mondrian");
    props.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
    props.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
    props.put(RolapConnectionProperties.JdbcUser.name(), "sa");
    props.put(RolapConnectionProperties.JdbcPassword.name(), "");
    props.put("UseSchemaPool", "false");
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
}

// End LookmlTranspilerTest.java
