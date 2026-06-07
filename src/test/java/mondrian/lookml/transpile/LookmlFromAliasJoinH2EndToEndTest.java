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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #125: end-to-end proof that a {@code from:}-aliased dimension join —
 * where the {@code sql_on} references the join by its <em>join name</em>, not
 * the {@code from:}/{@code view_name:} target — transpiles to a queryable
 * conformed dimension whose columns and physical table are resolved from the
 * <em>underlying</em> view, and returns correct values. Also proves that two
 * joins that {@code from:} the SAME underlying base view (two aliases) become
 * two DISTINCT conformed dimensions with correct, independent per-alias results,
 * and that a member grant on a {@code from:}-aliased dimension restricts
 * correctly (RLS prong).
 *
 * <p>Schema: an {@code orders} fact joins {@code account} twice — once as
 * {@code account_csm} (the CSM owner) and once as {@code account_owner} (the
 * sales owner) — both with {@code from: account}. Each join's {@code sql_on}
 * references the join name (e.g. {@code ${account_csm.id} = ${orders.csm_id}}),
 * which before #125 degraded because the importer looked for {@code
 * ${account.id}} instead.
 *
 * <pre>
 *   orders (fact)                 account (one physical table, two aliases)
 *   order_id csm_id owner_id amt   id  name   region
 *     1       10     20      100   10  Ann    North
 *     2       10     21      200   11  Bea    South
 *     3       11     20      300   20  Xan    East
 *     4       11     21      400   21  Yvo    West
 *
 *   total amt = 1000
 *   by csm (account_csm.name):   Ann (orders 1,2) = 300, Bea (3,4) = 700
 *   by owner (account_owner.name): Xan (orders 1,3) = 400, Yvo (2,4) = 600
 * </pre>
 */
public class LookmlFromAliasJoinH2EndToEndTest {

  private static final String[] DDL = {
    "DROP TABLE IF EXISTS \"fa_orders\"",
    "DROP TABLE IF EXISTS \"fa_account\"",
    "CREATE TABLE \"fa_orders\" (\"order_id\" INTEGER, \"csm_id\" INTEGER,"
        + " \"owner_id\" INTEGER, \"amt\" INTEGER)",
    "CREATE TABLE \"fa_account\" (\"id\" INTEGER, \"name\" VARCHAR(16),"
        + " \"region\" VARCHAR(16))",
    "INSERT INTO \"fa_orders\" VALUES"
        + " (1,10,20,100),(2,10,21,200),(3,11,20,300),(4,11,21,400)",
    "INSERT INTO \"fa_account\" VALUES"
        + " (10,'Ann','North'),(11,'Bea','South'),"
        + " (20,'Xan','East'),(21,'Yvo','West')",
  };

  /** The fact joins the SAME underlying {@code account} view twice via
   * {@code from:}, each referenced in its {@code sql_on} by the JOIN NAME. */
  private static final String LOOKML =
      "view: fa_orders {\n"
      + "  sql_table_name: fa_orders ;;\n"
      + "  dimension: order_id { type: number primary_key: yes"
      + "    sql: ${TABLE}.order_id ;; }\n"
      + "  dimension: csm_id { type: number sql: ${TABLE}.csm_id ;; }\n"
      + "  dimension: owner_id { type: number sql: ${TABLE}.owner_id ;; }\n"
      + "  measure: amt { type: sum sql: ${TABLE}.amt ;; }\n"
      + "}\n"
      + "view: fa_account {\n"
      + "  sql_table_name: fa_account ;;\n"
      + "  dimension: id { type: number primary_key: yes"
      + "    sql: ${TABLE}.id ;; }\n"
      + "  dimension: name { type: string sql: ${TABLE}.name ;; }\n"
      + "  dimension: region { type: string sql: ${TABLE}.region ;; }\n"
      + "}\n"
      + "explore: fa_orders {\n"
      + "  join: account_csm {\n"
      + "    from: fa_account\n"
      + "    type: left_outer relationship: many_to_one\n"
      + "    sql_on: ${account_csm.id} = ${fa_orders.csm_id} ;;\n"
      + "  }\n"
      + "  join: account_owner {\n"
      + "    from: fa_account\n"
      + "    type: left_outer relationship: many_to_one\n"
      + "    sql_on: ${account_owner.id} = ${fa_orders.owner_id} ;;\n"
      + "  }\n"
      + "}\n";

  /** A role granting only Ann on the from:-aliased {@code account_csm}
   * dimension — proves a member grant carries through to the alias target. */
  private static final String ROLE_ONLY_ANN =
      "  <Role name='OnlyAnn'>\n"
      + "    <SchemaGrant access='all'>\n"
      + "      <CubeGrant cube='fa_orders' access='all'>\n"
      + "        <HierarchyGrant"
      + " hierarchy='[account_csm].[name]' access='custom'"
      + " rollupPolicy='partial'"
      + " bottomLevel='[account_csm].[name].[name]'>\n"
      + "          <MemberGrant"
      + " member='[account_csm].[name].[Ann]' access='all'/>\n"
      + "        </HierarchyGrant>\n"
      + "      </CubeGrant>\n"
      + "    </SchemaGrant>\n"
      + "  </Role>\n";

  private static final String H2_URL =
      "jdbc:h2:mem:lookml_fromalias_e2e;DB_CLOSE_DELAY=-1";

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

  private static String xml() {
    return new LookmlTranspiler()
        .transpile(LookmlParser.parse(LOOKML)).toXml();
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
    // Fully isolate from any pooled/cached schema built by a sibling test so a
    // role-restricted catalog never leaks its member visibility into a later
    // plain query (#125 test hardening).
    conn.getCacheControl(null).flushSchemaCache();
    return conn;
  }

  private static Double scalar(Connection conn, String mdx) {
    Query q = conn.parseQuery(mdx);
    Result r = conn.execute(q);
    Object v = r.getCell(new int[]{0}).getValue();
    r.close();
    return v == null ? null : ((Number) v).doubleValue();
  }

  private static Map<String, Double> byRow(Connection conn, String mdx) {
    Query q = conn.parseQuery(mdx);
    Result r = conn.execute(q);
    Map<String, Double> out = new LinkedHashMap<>();
    Axis rows = r.getAxes()[1];
    int i = 0;
    for (Position p : rows.getPositions()) {
      Object v = r.getCell(new int[]{0, i}).getValue();
      out.put(p.get(0).getName(), v == null ? null : ((Number) v).doubleValue());
      i++;
    }
    r.close();
    return out;
  }

  // ---- 0) the importer emits two DISTINCT conformed dimensions ----------

  @Test
  public void importerEmitsTwoDistinctAliasedDimensions() {
    String yaml = new LookmlTranspiler()
        .transpile(LookmlParser.parse(LOOKML)).yaml();
    // The conformed dimensions are named by the JOIN names, not the from:-target.
    assertTrue(yaml.contains("name: \"account_csm\""), yaml);
    assertTrue(yaml.contains("name: \"account_owner\""), yaml);
    // Both resolve their physical table from the underlying view (fa_account).
    assertTrue(yaml.contains("table: \"fa_account\""), yaml);
    // Each dimension link targets its own join-name dimension with its own FK,
    // proving the two aliases are kept distinct (#125).
    assertTrue(
        yaml.contains("dimension: \"account_csm\"")
            && yaml.contains("foreign_key_column: \"csm_id\""), yaml);
    assertTrue(
        yaml.contains("dimension: \"account_owner\"")
            && yaml.contains("foreign_key_column: \"owner_id\""), yaml);
  }

  // ---- 1) a from:-aliased dimension returns correct, real-column values --

  @Test
  public void aliasedDimensionQueriesUnderlyingColumns() {
    Connection conn = connect(xml(), null);
    try {
      // The grand total over the fact is unaffected by the join.
      assertEquals(1000.0,
          scalar(conn,
              "SELECT {[Measures].[amt]} ON COLUMNS FROM [fa_orders]"),
          0.001, "grand total amt");

      // The CSM alias resolves account.name from the underlying view, keyed by
      // the join name (account_csm.id = orders.csm_id).
      Map<String, Double> byCsm = byRow(conn,
          "SELECT {[Measures].[amt]} ON COLUMNS,\n"
          + " [account_csm].[name].Members ON ROWS\n"
          + "FROM [fa_orders]");
      assertEquals(300.0, byCsm.get("Ann"), 0.001,
          "Ann is CSM of orders 1+2 = 300");
      assertEquals(700.0, byCsm.get("Bea"), 0.001,
          "Bea is CSM of orders 3+4 = 700");
    } finally {
      conn.close();
    }
  }

  // ---- 2) two aliases of one base view stay DISTINCT --------------------

  @Test
  public void twoAliasesOfSameBaseViewReturnDistinctResults() {
    Connection conn = connect(xml(), null);
    try {
      Map<String, Double> byCsm = byRow(conn,
          "SELECT {[Measures].[amt]} ON COLUMNS,\n"
          + " [account_csm].[name].Members ON ROWS\n"
          + "FROM [fa_orders]");
      Map<String, Double> byOwner = byRow(conn,
          "SELECT {[Measures].[amt]} ON COLUMNS,\n"
          + " [account_owner].[name].Members ON ROWS\n"
          + "FROM [fa_orders]");

      // CSM grouping: Ann 300, Bea 700 (keyed on csm_id).
      assertEquals(300.0, byCsm.get("Ann"), 0.001);
      assertEquals(700.0, byCsm.get("Bea"), 0.001);
      // OWNER grouping: Xan 400 (orders 1,3), Yvo 600 (orders 2,4) — DISTINCT
      // from the CSM grouping, proving the two aliases are independent.
      assertEquals(400.0, byOwner.get("Xan"), 0.001,
          "Xan owns orders 1+3 = 400");
      assertEquals(600.0, byOwner.get("Yvo"), 0.001,
          "Yvo owns orders 2+4 = 600");
    } finally {
      conn.close();
    }
  }

  // ---- 3) RLS: a member grant on a from:-aliased dimension restricts -----

  @Test
  public void memberGrantOnAliasedDimensionRestricts() {
    String catalog = xml().replace("</Schema>", ROLE_ONLY_ANN + "</Schema>");
    Connection conn = connect(catalog, "OnlyAnn");
    try {
      // Only Ann's CSM orders are visible: orders 1+2 = 300 (not the full 1000).
      assertEquals(300.0,
          scalar(conn,
              "SELECT {[Measures].[amt]} ON COLUMNS FROM [fa_orders]"),
          0.001, "OnlyAnn role restricts to Ann's CSM orders = 300");

      Map<String, Double> byCsm = byRow(conn,
          "SELECT {[Measures].[amt]} ON COLUMNS,\n"
          + " [account_csm].[name].Members ON ROWS\n"
          + "FROM [fa_orders]");
      assertEquals(300.0, byCsm.get("Ann"), 0.001, "Ann still visible");
      assertNull(byCsm.get("Bea"), "Bea hidden by the OnlyAnn member grant");
    } finally {
      conn.close();
    }
  }
}

// End LookmlFromAliasJoinH2EndToEndTest.java
