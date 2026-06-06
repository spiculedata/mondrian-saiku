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
package mondrian.lookml.json;

import mondrian.lookml.classify.LookmlClassifier;
import mondrian.lookml.model.Classification;
import mondrian.lookml.model.ClassificationResult;
import mondrian.lookml.model.CoverageRecord;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParser;
import mondrian.lookml.transpile.LookmlTranspiler;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD spec for the Looker SDK Explore-JSON front-end (issue #116, part B).
 *
 * <p>The headline guarantee: a sample {@code LookmlModelExplore} JSON maps to
 * the SAME classification (and the same transpiled M4 measures) as the
 * equivalent hand-written {@code .lkml} — proving the JSON path reuses the
 * classifier / transpiler pipeline unchanged.
 */
class ExploreJsonReaderTest {

  /** A clean single-base star with a sum measure, expressed as Explore JSON. */
  private static final String EXPLORE_JSON = ""
      + "{\n"
      + "  \"name\": \"orders\",\n"
      + "  \"view_name\": \"orders\",\n"
      + "  \"joins\": [\n"
      + "    { \"name\": \"customers\", \"type\": \"left_outer\",\n"
      + "      \"relationship\": \"many_to_one\",\n"
      + "      \"sql_on\": \"${orders.customer_id} = ${customers.id}\" }\n"
      + "  ],\n"
      + "  \"fields\": {\n"
      + "    \"dimensions\": [\n"
      + "      { \"name\": \"orders.id\", \"view\": \"orders\",\n"
      + "        \"type\": \"number\", \"primary_key\": true,\n"
      + "        \"sql\": \"${TABLE}.id\" },\n"
      + "      { \"name\": \"customers.id\", \"view\": \"customers\",\n"
      + "        \"type\": \"number\", \"sql\": \"${TABLE}.id\" }\n"
      + "    ],\n"
      + "    \"measures\": [\n"
      + "      { \"name\": \"orders.revenue\", \"view\": \"orders\",\n"
      + "        \"type\": \"sum\", \"sql\": \"${TABLE}.amount\" }\n"
      + "    ]\n"
      + "  }\n"
      + "}\n";

  /** The hand-written .lkml equivalent of {@link #EXPLORE_JSON}. */
  private static final String EQUIVALENT_LKML = ""
      + "view: orders {\n"
      + "  dimension: id { type: number primary_key: yes sql: ${TABLE}.id ;; }\n"
      + "  measure: revenue { type: sum sql: ${TABLE}.amount ;; }\n"
      + "}\n"
      + "view: customers {\n"
      + "  dimension: id { type: number sql: ${TABLE}.id ;; }\n"
      + "}\n"
      + "explore: orders {\n"
      + "  join: customers {\n"
      + "    type: left_outer\n"
      + "    relationship: many_to_one\n"
      + "    sql_on: ${orders.customer_id} = ${customers.id} ;;\n"
      + "  }\n"
      + "}\n";

  /** The JSON maps to the same per-construct classification as the .lkml. */
  @Test void jsonClassifiesIdenticallyToEquivalentLkml() {
    final LookmlNode fromJson = new ExploreJsonReader().read(EXPLORE_JSON);
    final LookmlNode fromLkml = LookmlParser.parse(EQUIVALENT_LKML);

    assertEquals(classificationByName(fromLkml),
        classificationByName(fromJson),
        "Explore JSON must classify identically to the equivalent .lkml");
  }

  /** The JSON explore + sum measure both classify CLEAN. */
  @Test void cleanStarFromJsonIsClean() {
    final LookmlNode doc = new ExploreJsonReader().read(EXPLORE_JSON);
    final ClassificationResult r = new LookmlClassifier().classify(doc);
    assertEquals(Classification.CLEAN, byName(r, "explore:orders"));
    assertEquals(Classification.CLEAN, byName(r, "orders.revenue"));
  }

  /** The JSON transpiles to a sum measure in the M4 schema. */
  @Test void jsonTranspilesSumMeasure() {
    final LookmlNode doc = new ExploreJsonReader().read(EXPLORE_JSON);
    final String yaml = new LookmlTranspiler().transpile(doc).yaml();
    assertTrue(yaml.contains("name: \"revenue\""), yaml);
    assertTrue(yaml.contains("aggregator: \"sum\""), yaml);
  }

  /** An access_filter on a fact column in the JSON yields a PredicateGrant,
   * the same row-security role the .lkml path emits. */
  @Test void jsonAccessFilterEmitsPredicateGrant() {
    final String json = ""
        + "{\n"
        + "  \"name\": \"orders\", \"view_name\": \"orders\",\n"
        + "  \"fields\": { \"dimensions\": [], \"measures\": [\n"
        + "    { \"name\": \"orders.amount\", \"view\": \"orders\",\n"
        + "      \"type\": \"sum\", \"sql\": \"${TABLE}.amount\" } ] },\n"
        + "  \"access_filters\": [\n"
        + "    { \"field\": \"orders.tenant_id\",\n"
        + "      \"user_attribute\": \"tenant_id\" } ]\n"
        + "}\n";
    final LookmlNode doc = new ExploreJsonReader().read(json);
    final String yaml = new LookmlTranspiler().transpile(doc).yaml();
    assertTrue(yaml.contains("predicate_grants:"), yaml);
    assertTrue(yaml.contains("column: \"tenant_id\""), yaml);
  }

  // --- helpers -----------------------------------------------------------

  private static Map<String, Classification> classificationByName(
      LookmlNode doc) {
    final ClassificationResult r = new LookmlClassifier().classify(doc);
    final Map<String, Classification> out = new TreeMap<>();
    for (CoverageRecord rec : r.records()) {
      out.put(rec.qualifiedName(), rec.classification());
    }
    return out;
  }

  private static Classification byName(ClassificationResult r, String name) {
    return r.records().stream()
        .filter(rec -> rec.qualifiedName().equals(name))
        .map(CoverageRecord::classification)
        .findFirst().orElseThrow(() ->
            new AssertionError("no record for " + name + " in " + r.records()));
  }
}

// End ExploreJsonReaderTest.java
