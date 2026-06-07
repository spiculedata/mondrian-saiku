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
package mondrian.lookml.equivalence;

import mondrian.lookml.equivalence.EquivalenceComparator.ComparisonResult;
import mondrian.lookml.equivalence.EquivalenceComparator.DivergenceCategory;
import mondrian.lookml.equivalence.LookerQueryToMdx.Plan;
import mondrian.lookml.parse.LookmlParser;
import mondrian.lookml.transpile.LookmlTranspiler;
import mondrian.lookml.transpile.TranspileResult;
import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #128: the offline numerical-equivalence proof — the migration analogue
 * of the #90 Calcite parity guard. There is NO live Looker instance in this
 * environment, so the oracle is a hand-authored {@link LookerQueryResult}
 * fixture: the numbers Looker WOULD return for the chosen query. The harness
 * then transpiles the LookML, loads it into H2, rewrites the query to MDX via
 * provenance, runs it through Mondrian, and asserts the converted cube returns
 * the SAME numbers.
 *
 * <p>The chosen query is non-trivial: <em>total order amount grouped by the
 * joined customer's country</em> — a measure aggregated over a dimension that
 * lives in a different table reached through a {@code many_to_one} join. A wrong
 * join (or a wrong aggregation) would change the per-country numbers, so a clean
 * MATCH actually means something.
 *
 * <pre>
 *   eq_orders (fact)              eq_customers (dim, many_to_one on customer_id)
 *   order_id customer_id amount    customer_id country
 *     1        C1         100        C1         USA
 *     2        C1         200        C2         USA
 *     3        C2          50        C3         UK
 *     4        C3         400
 *     5        C3         300
 *
 *   amount by country:  USA = (100+200) + 50  = 350
 *                       UK  = 400 + 300        = 700
 * </pre>
 */
public class LookmlEquivalenceH2EndToEndTest {

  private static final String[] DDL = {
    "DROP TABLE IF EXISTS \"eq_orders\"",
    "DROP TABLE IF EXISTS \"eq_customers\"",
    "CREATE TABLE \"eq_orders\" (\"order_id\" INTEGER,"
        + " \"customer_id\" VARCHAR(8), \"amount\" INTEGER)",
    "CREATE TABLE \"eq_customers\" (\"customer_id\" VARCHAR(8),"
        + " \"country\" VARCHAR(16))",
    "INSERT INTO \"eq_orders\" VALUES"
        + " (1,'C1',100),(2,'C1',200),(3,'C2',50),(4,'C3',400),(5,'C3',300)",
    "INSERT INTO \"eq_customers\" VALUES"
        + " ('C1','USA'),('C2','USA'),('C3','UK')",
  };

  /** Fact joins a customer dimension many_to_one; we group a measure by the
   * joined dimension's country attribute. */
  private static final String LOOKML =
      "view: eq_orders {\n"
      + "  sql_table_name: eq_orders ;;\n"
      + "  dimension: order_id { type: number primary_key: yes"
      + "    sql: ${TABLE}.order_id ;; }\n"
      + "  dimension: customer_id { type: string"
      + "    sql: ${TABLE}.customer_id ;; }\n"
      + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
      + "}\n"
      + "view: eq_customers {\n"
      + "  sql_table_name: eq_customers ;;\n"
      + "  dimension: customer_id { type: string primary_key: yes"
      + "    sql: ${TABLE}.customer_id ;; }\n"
      + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
      + "}\n"
      + "explore: eq_orders {\n"
      + "  join: eq_customers {\n"
      + "    type: left_outer relationship: many_to_one\n"
      + "    sql_on: ${eq_orders.customer_id}"
      + " = ${eq_customers.customer_id} ;;\n"
      + "  }\n"
      + "}\n";

  /** A role restricting the joined customer dimension to USA countries only —
   * the Looker {@code access_filter: { field: eq_customers.country }} analogue.
   * Restricting to USA hides UK, so the measure must drop to the USA-only 350. */
  private static final String ROLE_USA_ONLY =
      "  <Role name='UsaOnly'>\n"
      + "    <SchemaGrant access='all'>\n"
      + "      <CubeGrant cube='eq_orders' access='all'>\n"
      + "        <HierarchyGrant"
      + " hierarchy='[eq_customers].[country]' access='custom'"
      + " rollupPolicy='partial'"
      + " bottomLevel='[eq_customers].[country].[country]'>\n"
      + "          <MemberGrant"
      + " member='[eq_customers].[country].[USA]' access='all'/>\n"
      + "        </HierarchyGrant>\n"
      + "      </CubeGrant>\n"
      + "    </SchemaGrant>\n"
      + "  </Role>\n";

  private static final String H2_URL =
      "jdbc:h2:mem:lookml_equiv_e2e;DB_CLOSE_DELAY=-1";

  /** The query both sides answer: amount by joined-customer country. */
  private static final LookerQuerySpec SPEC = LookerQuerySpec.builder()
      .explore("eq_orders")
      .dimension("eq_customers.country")
      .measure("eq_orders.amount")
      .build();

  @BeforeAll
  public static void boot() throws Exception {
    mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
    Class.forName("org.h2.Driver");
    try (java.sql.Connection c =
             java.sql.DriverManager.getConnection(H2_URL, "sa", "");
         Statement st = c.createStatement()) {
      for (String sql : DDL) {
        st.execute(sql);
      }
    }
    mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
  }

  @AfterEach
  public void clearCache() {
    mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
  }

  @AfterAll
  public static void close() {
    mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
  }

  private static TranspileResult transpile() {
    return new LookmlTranspiler().transpile(LookmlParser.parse(LOOKML));
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
    Connection conn = DriverManager.getConnection(props, null, null);
    conn.getCacheControl(null).flushSchemaCache();
    return conn;
  }

  private static Map<String, Object> row(String country, double amount) {
    final Map<String, Object> r = new LinkedHashMap<>();
    r.put("eq_customers.country", country);
    r.put("eq_orders.amount", amount);
    return r;
  }

  // ---- 1) POSITIVE: oracle == converted cube → MATCH ---------------------

  @Test
  public void offlineFixtureMatchesConvertedCube() {
    final TranspileResult tr = transpile();
    final Plan plan = new LookerQueryToMdx(tr.provenance()).toMdx(SPEC);
    // Sanity: the field actually resolved CLEAN (nothing silently skipped).
    assertTrue(plan.skippedFields().isEmpty(), plan.skippedFields().toString());

    // The known-correct Looker oracle for "amount by country".
    final LookerQueryResult oracle = LookerQueryResult.builder()
        .row(row("USA", 350.0))
        .row(row("UK", 700.0))
        .build();

    final Connection conn = connect(tr.toXml(), null);
    try {
      final Result saiku = execute(conn, plan.mdx());
      final ComparisonResult result =
          new EquivalenceComparator().compare(oracle, saiku, plan);
      saiku.close();
      assertTrue(result.matched(),
          () -> "converted cube must equal the Looker oracle: " + result);
    } finally {
      conn.close();
    }
  }

  // ---- 2) NEGATIVE: a deliberately-wrong oracle cell → divergence --------

  @Test
  public void offlineFixtureWithWrongCellIsCaught() {
    final TranspileResult tr = transpile();
    final Plan plan = new LookerQueryToMdx(tr.provenance()).toMdx(SPEC);

    // Inject ONE wrong measure cell (USA 999 instead of 350). A harness never
    // shown to catch a mismatch is worthless — this proves it does.
    final LookerQueryResult oracle = LookerQueryResult.builder()
        .row(row("USA", 999.0))
        .row(row("UK", 700.0))
        .build();

    final Connection conn = connect(tr.toXml(), null);
    try {
      final Result saiku = execute(conn, plan.mdx());
      final ComparisonResult result =
          new EquivalenceComparator().compare(oracle, saiku, plan);
      saiku.close();
      assertFalse(result.matched(), "the injected wrong cell must diverge");
      assertTrue(result.hasCategory(DivergenceCategory.MEASURE_VALUE),
          "category must be MEASURE_VALUE: " + result);
    } finally {
      conn.close();
    }
  }

  // ---- 3) NEGATIVE: a missing oracle row → dimension-set divergence ------

  @Test
  public void offlineFixtureWithMissingRowIsCaught() {
    final TranspileResult tr = transpile();
    final Plan plan = new LookerQueryToMdx(tr.provenance()).toMdx(SPEC);

    // Drop the UK row entirely: oracle has one row, cube has two.
    final LookerQueryResult oracle = LookerQueryResult.builder()
        .row(row("USA", 350.0))
        .build();

    final Connection conn = connect(tr.toXml(), null);
    try {
      final Result saiku = execute(conn, plan.mdx());
      final ComparisonResult result =
          new EquivalenceComparator().compare(oracle, saiku, plan);
      saiku.close();
      assertFalse(result.matched());
      assertTrue(result.hasCategory(DivergenceCategory.ROW_COUNT)
              || result.hasCategory(DivergenceCategory.DIMENSION_SET),
          "missing row must surface as row-count/dimension-set: " + result);
    } finally {
      conn.close();
    }
  }

  // ---- 4) RLS: access_filter oracle vs member-grant role → MATCH ---------

  @Test
  public void rlsRestrictedOracleMatchesGrantedRole() {
    final TranspileResult tr = transpile();
    final Plan plan = new LookerQueryToMdx(tr.provenance()).toMdx(SPEC);

    // Under a Looker access_filter restricting country to USA, Looker returns
    // ONLY the USA row (350). The converted cube under the equivalent member
    // grant must return the same single restricted row — proving row-security
    // converts to the same restricted numbers.
    final LookerQueryResult restrictedOracle = LookerQueryResult.builder()
        .row(row("USA", 350.0))
        .build();

    final String catalog =
        tr.toXml().replace("</Schema>", ROLE_USA_ONLY + "</Schema>");
    final Connection conn = connect(catalog, "UsaOnly");
    try {
      final Result saiku = execute(conn, plan.mdx());
      final ComparisonResult result =
          new EquivalenceComparator().compare(restrictedOracle, saiku, plan);
      saiku.close();
      assertTrue(result.matched(),
          () -> "restricted cube must equal the access_filter oracle: "
              + result);
    } finally {
      conn.close();
    }
  }

  private static Result execute(Connection conn, String mdx) {
    final Query q = conn.parseQuery(mdx);
    return conn.execute(q);
  }
}

// End LookmlEquivalenceH2EndToEndTest.java
