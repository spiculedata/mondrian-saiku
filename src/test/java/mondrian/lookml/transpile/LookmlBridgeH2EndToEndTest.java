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
 * Issue #124: end-to-end proof that a LookML many-to-many bridge explore
 * transpiles to a Mondrian {@code <BridgeLink>} (#107) and returns the
 * <em>de-duplicated</em> (bridge-correct) total, NOT the fanned-out one — and
 * that a bridge member grant restricts correctly with no cross-member leak.
 *
 * <p>The canonical two-hop is {@code accounts --(one_to_many)--> owners (bridge)
 * --(many_to_one)--> customers (dim)}: an account fans out across its owner
 * rows, and each owner row maps the account to a customer. A naive
 * {@code SUM(balance)} over the fan-out double-counts joint accounts; the
 * full-count bridge de-duplicates on the fact grain ({@code account_id}).
 *
 * <pre>
 *   br_accounts (fact)             br_owners (bridge, full-count)
 *   account_id balance            account_id customer_id
 *     1          1000               1          Alice
 *     2           500               1          Bob      (joint Alice+Bob)
 *     3           300               2          Bob
 *     4           700               3          Alice
 *                                   3          Carol    (joint Alice+Carol)
 *   full fact total = 2500          4          Carol    (Carol-only)
 *
 *   de-duped [All] balance       = 1000+500+300+700 = 2500 (every account once)
 *   Alice (full-count)           = acct1 1000 + acct3 300 = 1300
 *   Bob   (full-count)           = acct1 1000 + acct2 500 = 1500
 *   naive fanned SUM over owners = 1000+1000+500+300+300+700 = 3800 (WRONG)
 * </pre>
 */
public class LookmlBridgeH2EndToEndTest {

  private static final String[] DDL = {
    "DROP TABLE IF EXISTS \"br_accounts\"",
    "DROP TABLE IF EXISTS \"br_owners\"",
    "DROP TABLE IF EXISTS \"br_customers\"",
    "CREATE TABLE \"br_accounts\" (\"account_id\" INTEGER,"
        + " \"balance\" INTEGER)",
    "CREATE TABLE \"br_owners\" (\"account_id\" INTEGER,"
        + " \"customer_id\" VARCHAR(16))",
    "CREATE TABLE \"br_customers\" (\"customer_id\" VARCHAR(16),"
        + " \"customer_name\" VARCHAR(32))",
    "INSERT INTO \"br_accounts\" VALUES (1,1000),(2,500),(3,300),(4,700)",
    "INSERT INTO \"br_owners\" VALUES"
        + " (1,'Alice'),(1,'Bob'),(2,'Bob'),(3,'Alice'),(3,'Carol'),"
        + " (4,'Carol')",
    "INSERT INTO \"br_customers\" VALUES"
        + " ('Alice','Alice'),('Bob','Bob'),('Carol','Carol')",
  };

  /** The bridge explore: accounts fact, owners bridge (one_to_many), customers
   * dim (many_to_one). The fact declares a primary key (the bridge grain). */
  private static final String LOOKML =
      "view: br_accounts {\n"
      + "  sql_table_name: br_accounts ;;\n"
      + "  dimension: account_id { type: number primary_key: yes"
      + "    sql: ${TABLE}.account_id ;; }\n"
      + "  measure: balance { type: sum sql: ${TABLE}.balance ;; }\n"
      + "}\n"
      + "view: br_owners {\n"
      + "  sql_table_name: br_owners ;;\n"
      + "  dimension: account_id { type: number sql: ${TABLE}.account_id ;; }\n"
      + "  dimension: customer_id { type: string"
      + "    sql: ${TABLE}.customer_id ;; }\n"
      + "}\n"
      + "view: br_customers {\n"
      + "  sql_table_name: br_customers ;;\n"
      + "  dimension: customer_id { type: string primary_key: yes"
      + "    sql: ${TABLE}.customer_id ;; }\n"
      + "  dimension: customer_name { type: string"
      + "    sql: ${TABLE}.customer_name ;; }\n"
      + "}\n"
      + "explore: br_accounts {\n"
      + "  join: br_owners {\n"
      + "    type: left_outer relationship: one_to_many\n"
      + "    sql_on: ${br_accounts.account_id}"
      + " = ${br_owners.account_id} ;;\n"
      + "  }\n"
      + "  join: br_customers {\n"
      + "    type: left_outer relationship: many_to_one\n"
      + "    sql_on: ${br_owners.customer_id}"
      + " = ${br_customers.customer_id} ;;\n"
      + "  }\n"
      + "}\n";

  /** A role granting only Alice + Bob on the bridged Customer dimension, hiding
   * Carol — proves the bridge member grant restricts the fan-out with no leak. */
  private static final String ROLE_ALICE_BOB =
      "  <Role name='AliceBob'>\n"
      + "    <SchemaGrant access='all'>\n"
      + "      <CubeGrant cube='br_accounts' access='all'>\n"
      + "        <HierarchyGrant"
      + " hierarchy='[br_customers].[customer_name]' access='custom'"
      + " bottomLevel='[br_customers].[customer_name].[customer_name]'>\n"
      + "          <MemberGrant"
      + " member='[br_customers].[customer_name].[Alice]' access='all'/>\n"
      + "          <MemberGrant"
      + " member='[br_customers].[customer_name].[Bob]' access='all'/>\n"
      + "        </HierarchyGrant>\n"
      + "      </CubeGrant>\n"
      + "    </SchemaGrant>\n"
      + "  </Role>\n";

  private static final String H2_URL =
      "jdbc:h2:mem:lookml_bridge_e2e;DB_CLOSE_DELAY=-1";

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
    return DriverManager.getConnection(props, null, null);
  }

  private static Double scalar(Connection conn, String mdx) {
    Query q = conn.parseQuery(mdx);
    Result r = conn.execute(q);
    Object v = r.getCell(new int[]{0}).getValue();
    r.close();
    return v == null ? null : ((Number) v).doubleValue();
  }

  private static Map<String, Double> custMap(Connection conn, String mdx) {
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

  // ---- 0) the importer actually emits a <BridgeLink> ---------------------

  @Test
  public void importerEmitsBridgeLink() {
    String yaml = new LookmlTranspiler()
        .transpile(LookmlParser.parse(LOOKML)).yaml();
    assertTrue(yaml.contains("type: \"bridge\""), yaml);
    assertTrue(yaml.contains("bridge_table: \"br_owners\""), yaml);
    assertTrue(yaml.contains("fact_foreign_key_column: \"account_id\""), yaml);
    assertTrue(yaml.contains("bridge_fact_key_column: \"account_id\""), yaml);
    assertTrue(
        yaml.contains("bridge_dimension_key_column: \"customer_id\""), yaml);
    // No allocation weight modelled → fullCount default (no aggregation key).
    assertTrue(!yaml.contains("aggregation:"), yaml);
  }

  // ---- 1) [All] de-dups to the true fact total (not the fanned one) ------

  @Test
  public void bridgeAllDeDupesFanout() {
    Connection conn = connect(xml(), null);
    try {
      // Every account counted exactly once: 1000+500+300+700 = 2500. A naive
      // SUM over the fanned owner rows would be 3800.
      assertEquals(2500.0,
          scalar(conn,
              "SELECT {[Measures].[balance]} ON COLUMNS FROM [br_accounts]"),
          0.001,
          "full-count bridge de-dups on account_id: 2500, not the fanned 3800");
    } finally {
      conn.close();
    }
  }

  // ---- 2) per-customer full-count cells ---------------------------------

  @Test
  public void bridgeByCustomerFullCount() {
    Connection conn = connect(xml(), null);
    try {
      Map<String, Double> m = custMap(conn,
          "SELECT {[Measures].[balance]} ON COLUMNS,\n"
          + " [br_customers].[customer_name].Members ON ROWS\n"
          + "FROM [br_accounts]");
      assertEquals(1300.0, m.get("Alice"), 0.001,
          "Alice full-count: acct1 1000 + acct3 300");
      assertEquals(1500.0, m.get("Bob"), 0.001,
          "Bob full-count: acct1 1000 + acct2 500");
      assertEquals(1000.0, m.get("Carol"), 0.001,
          "Carol full-count: acct3 300 + acct4 700");
    } finally {
      conn.close();
    }
  }

  // ---- 3) RLS: a bridge member grant restricts with no cross-member leak -

  @Test
  public void bridgeMemberGrantRestrictsNoLeak() {
    String catalog = xml().replace("</Schema>", ROLE_ALICE_BOB + "</Schema>");
    Connection conn = connect(catalog, "AliceBob");
    try {
      // Carol is hidden. Account 4 (Carol-only) must be excluded entirely; the
      // restricted [All] de-dups over accounts with >=1 VISIBLE owner:
      // acct1(1000)+acct2(500)+acct3(300) = 1800 — NOT the full 2500.
      assertEquals(1800.0,
          scalar(conn,
              "SELECT {[Measures].[balance]} ON COLUMNS FROM [br_accounts]"),
          0.001,
          "restricted [All] excludes Carol-only acct4: 1800");

      Map<String, Double> m = custMap(conn,
          "SELECT {[Measures].[balance]} ON COLUMNS,\n"
          + " [br_customers].[customer_name].Members ON ROWS\n"
          + "FROM [br_accounts]");
      assertNull(m.get("Carol"), "Carol hidden by the bridge member grant");
      assertEquals(1300.0, m.get("Alice"), 0.001,
          "Alice still sees her full-count accounts");
      assertEquals(1500.0, m.get("Bob"), 0.001,
          "Bob still sees his full-count accounts");
    } finally {
      conn.close();
    }
  }
}

// End LookmlBridgeH2EndToEndTest.java
