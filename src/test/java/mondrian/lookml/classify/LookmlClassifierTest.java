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
package mondrian.lookml.classify;

import mondrian.lookml.model.Classification;
import mondrian.lookml.model.ClassificationResult;
import mondrian.lookml.model.CoverageRecord;
import mondrian.lookml.model.ReasonCode;
import mondrian.lookml.model.Scope;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParser;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD spec for the LookML join-graph classifier + safety refusal gate (#100).
 *
 * <p>Each test parses a tiny inline LookML fixture and asserts the exact
 * {@link Classification} / {@link ReasonCode} the classifier emits for the
 * relevant explore or measure.
 */
class LookmlClassifierTest {

  private static ClassificationResult classify(String lookml) {
    final LookmlNode doc = LookmlParser.parse(lookml);
    return new LookmlClassifier().classify(doc);
  }

  /** Finds the single record for a qualified name, failing if absent. */
  private static CoverageRecord record(ClassificationResult r, String name) {
    final Optional<CoverageRecord> found = r.records().stream()
        .filter(rec -> rec.qualifiedName().equals(name))
        .findFirst();
    assertTrue(found.isPresent(),
        () -> "no record for " + name + " in " + r.records());
    return found.get();
  }

  // --- clean single-base star -------------------------------------------

  /** A clean star: left_outer + many_to_one join, sum measure on the fact's
   * own grain. Explore CLEAN, measure CLEAN. */
  @Test void cleanStarExploreAndSumMeasureAreClean() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: customers {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.customer_id} = ${customers.id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        + "  measure: revenue {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n"
        + "view: customers { dimension: id { primary_key: yes } }\n";

    final ClassificationResult r = classify(lookml);

    assertEquals(Classification.CLEAN,
        record(r, "explore:orders").classification());
    final CoverageRecord rev = record(r, "orders.revenue");
    assertEquals(Classification.CLEAN, rev.classification());
    assertEquals(ReasonCode.CLEAN, rev.reasonCode());
  }

  // --- fan-out / symmetric aggregate ------------------------------------

  /** A sum measure on the ONE side of a one_to_many the explore fans out is
   * now CLEAN when the fact declares a primary key: symmetric (fan-out-safe)
   * aggregation shipped (#103), and the declared grain lets the engine
   * pre-aggregate. A count_distinct is always fan-out safe: CLEAN. */
  @Test void sumOnOneSideOfOneToManyWithPkIsCleanViaSymmetricAggregate() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: revenue {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "  measure: distinct_customers {\n"
        + "    type: count_distinct\n"
        + "    sql: ${TABLE}.customer_id ;;\n"
        + "  }\n"
        + "}\n"
        + "view: items { dimension: order_id { type: number } }\n";

    final ClassificationResult r = classify(lookml);

    final CoverageRecord rev = record(r, "orders.revenue");
    assertEquals(Classification.CLEAN, rev.classification());

    final CoverageRecord cd = record(r, "orders.distinct_customers");
    assertEquals(Classification.CLEAN, cd.classification());
  }

  /** A fan-out additive aggregate with NO declared fact primary key stays
   * REFUSE: without a grain to pre-aggregate the symmetric path cannot fire,
   * so emitting the sum would be silently wrong. */
  @Test void sumOnOneSideOfOneToManyWithoutPkStaysRefused() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        + "  measure: revenue { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "view: items { dimension: order_id { type: number } }\n";

    final CoverageRecord rev = record(classify(lookml), "orders.revenue");
    assertEquals(Classification.REFUSE, rev.classification());
    assertEquals(ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE,
        rev.reasonCode());
  }

  // --- non-star topology -------------------------------------------------

  /** A full_outer join makes the explore non-star: REFUSE the explore. */
  @Test void fullOuterJoinRefusesExplore() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: customers {\n"
        + "    type: full_outer\n"
        + "    sql_on: ${orders.customer_id} = ${customers.id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: customers { dimension: id { primary_key: yes } }\n";

    final ClassificationResult r = classify(lookml);

    final CoverageRecord ex = record(r, "explore:orders");
    assertEquals(Classification.REFUSE, ex.classification());
    assertEquals(ReasonCode.REFUSE_NON_STAR_TOPOLOGY, ex.reasonCode());
  }

  /** A many_to_many join without a bridge table is non-star: REFUSE. */
  @Test void manyToManyWithoutBridgeRefusesExplore() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: tags {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${tags.order_id} ;;\n"
        + "    relationship: many_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: tags { dimension: order_id { type: number } }\n";

    final ClassificationResult r = classify(lookml);

    assertEquals(ReasonCode.REFUSE_NON_STAR_TOPOLOGY,
        record(r, "explore:orders").reasonCode());
  }

  // --- Liquid ------------------------------------------------------------

  /** Liquid {{ }} in a measure sql is refused; a plain ${TABLE}.col is not. */
  @Test void liquidInMeasureSqlRefusedPlainTableRefIsNot() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: liquid_rev {\n"
        + "    type: sum\n"
        + "    sql: {% if user %} ${TABLE}.amount {% endif %} ;;\n"
        + "  }\n"
        + "  measure: plain_rev {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    final ClassificationResult r = classify(lookml);

    assertEquals(ReasonCode.REFUSE_LIQUID,
        record(r, "orders.liquid_rev").reasonCode());
    assertEquals(Classification.CLEAN,
        record(r, "orders.plain_rev").classification());
  }

  // --- per-field metadata refusals --------------------------------------

  /** A bounded parameter DECLARATION is now CLEAN: it maps to an M4
   * {@code <QueryParameter>} (#105). (A parameter's USE inside {% parameter %}
   * Liquid SQL is still caught as REFUSE_LIQUID on the using field.) */
  @Test void parameterDeclarationIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  parameter: metric_picker {\n"
        + "    type: unquoted\n"
        + "    allowed_value: { label: \"Revenue\" value: \"revenue\" }\n"
        + "  }\n"
        + "}\n";

    assertEquals(Classification.CLEAN,
        record(classify(lookml), "orders.metric_picker").classification());
  }

  /** type: median measure now DEGRADEs (emitted as an M4 median aggregator,
   * #104) with the PERCENTILE_CONT dialect caveat noted. */
  @Test void medianMeasureDegradesWithDialectNote() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: med { type: median sql: ${TABLE}.amount ;; }\n"
        + "}\n";

    final CoverageRecord rec = record(classify(lookml), "orders.med");
    assertEquals(Classification.DEGRADE, rec.classification());
    assertEquals(ReasonCode.DEGRADE_PERCENTILE_DIALECT, rec.reasonCode());
    assertEquals("#104", rec.relatedIssue().orElseThrow());
  }

  /** type: percentile measure DEGRADEs the same way (#104). */
  @Test void percentileMeasureDegradesWithDialectNote() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: p90 { type: percentile percentile: 90"
        + "    sql: ${TABLE}.amount ;; }\n"
        + "}\n";

    assertEquals(ReasonCode.DEGRADE_PERCENTILE_DIALECT,
        record(classify(lookml), "orders.p90").reasonCode());
  }

  /** A type: tier dimension is CLEAN (maps to an M4 &lt;Tier&gt;, #108). */
  @Test void tierDimensionIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: size_tier { type: tier tiers: [10, 100]\n"
        + "    sql: ${TABLE}.units ;; }\n"
        + "}\n";

    assertEquals(Classification.CLEAN,
        record(classify(lookml), "orders.size_tier").classification());
  }

  /** A type: duration dimension_group is CLEAN (maps to &lt;Duration&gt;). */
  @Test void durationDimensionGroupIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension_group: lead { type: duration\n"
        + "    intervals: [day]\n"
        + "    sql_start: ${TABLE}.order_date ;;\n"
        + "    sql_end: ${TABLE}.ship_date ;; }\n"
        + "}\n";

    assertEquals(Classification.CLEAN,
        record(classify(lookml), "orders.lead").classification());
  }

  /** type: list field is refused. */
  @Test void typeListFieldRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: skus { type: list list_field: orders.sku }\n"
        + "}\n";

    assertEquals(ReasonCode.REFUSE_TYPE_LIST,
        record(classify(lookml), "orders.skus").reasonCode());
  }

  // --- access_filter / access grants ------------------------------------

  /** An access_filter on a simple modelled dimension key is CLEAN; an
   * arbitrary-predicate access_filter is refused. */
  @Test void accessFilterDimensionCleanArbitraryRefused() {
    final String clean = ""
        + "explore: orders {\n"
        + "  access_filter: { field: orders.region user_attribute: region }\n"
        + "}\n"
        + "view: orders {\n"
        + "  dimension: region { type: string sql: ${TABLE}.region ;; }\n"
        + "}\n";
    assertEquals(Classification.CLEAN,
        record(classify(clean), "explore:orders").classification());

    // An access_filter on an arbitrary fact COLUMN now DEGRADEs: it maps to a
    // <PredicateGrant> bound to a query-context parameter (#106).
    final String arbitrary = ""
        + "explore: orders {\n"
        + "  access_filter: {\n"
        + "    field: orders.tenant_id\n"
        + "    user_attribute: tenant_id\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n";
    final CoverageRecord rec = record(classify(arbitrary), "explore:orders");
    assertEquals(Classification.DEGRADE, rec.classification());
    assertEquals(ReasonCode.DEGRADE_PREDICATE_ROW_SECURITY, rec.reasonCode());
    assertEquals("#106", rec.relatedIssue().orElseThrow());
  }

  /** required_access_grants on a field is refused. */
  @Test void requiredAccessGrantsRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: secret {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "    required_access_grants: [can_see_revenue]\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.REFUSE_REQUIRED_ACCESS_GRANTS,
        record(classify(lookml), "orders.secret").reasonCode());
  }

  // --- DEGRADE cases -----------------------------------------------------

  /** A derived_table with a datagroup_trigger degrades (persistence dropped). */
  @Test void derivedTableWithTriggerDegrades() {
    final String lookml = ""
        + "explore: daily {}\n"
        + "view: daily {\n"
        + "  derived_table: {\n"
        + "    sql: SELECT 1 ;;\n"
        + "    datagroup_trigger: orders_default\n"
        + "  }\n"
        + "}\n";

    final CoverageRecord rec = record(classify(lookml), "view:daily");
    assertEquals(Classification.DEGRADE, rec.classification());
    assertEquals(ReasonCode.DEGRADE_PDT_PERSISTENCE_DROPPED, rec.reasonCode());
  }

  /** An aggregate_table block degrades (not converted). */
  @Test void aggregateTableDegrades() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  aggregate_table: rollup {\n"
        + "    query: { dimensions: [orders.created_date] measures: [orders.count] }\n"
        + "    materialization: { datagroup_trigger: orders_default }\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: count { type: count } }\n";

    final CoverageRecord rec =
        record(classify(lookml), "explore:orders.aggregate_table:rollup");
    assertEquals(Classification.DEGRADE, rec.classification());
    assertEquals(ReasonCode.DEGRADE_AGGREGATE_TABLE_NOT_CONVERTED,
        rec.reasonCode());
  }

  // --- multi-object document --------------------------------------------

  /** A multi-object document classifies every explore and every measure. */
  @Test void multiObjectDocumentClassifiesAll() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "explore: customers {}\n"
        + "view: orders {\n"
        + "  measure: revenue { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "view: customers {\n"
        + "  measure: c { type: count }\n"
        + "}\n";

    final ClassificationResult r = classify(lookml);

    // both explores classified
    assertNotNull(record(r, "explore:orders"));
    assertEquals(Classification.CLEAN,
        record(r, "explore:customers").classification());
    // orders.revenue refused for fan-out; customers.c clean
    assertEquals(ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE,
        record(r, "orders.revenue").reasonCode());
    assertEquals(Classification.CLEAN,
        record(r, "customers.c").classification());
  }
}

// End LookmlClassifierTest.java
