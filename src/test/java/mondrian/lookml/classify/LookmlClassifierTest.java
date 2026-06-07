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

  /** Fan-out is detected for EVERY view on the "one" side of a one_to_many,
   * not just the explore's base. Here the base is `orders`; a non-base view
   * `users` is on the "one" side of a one_to_many to `events` (sql_on
   * references users) and carries an additive sum with no declarable PK. The
   * sum on `users` fans out and must REFUSE — it would be silently wrong
   * (#98). */
  @Test void fanOutMeasureOnSnowflakedViewStaysRefused() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: users {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.user_id} = ${users.id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "  join: events {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${users.id} = ${events.user_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: users {\n"
        + "  measure: lifetime_value { type: sum sql: ${TABLE}.ltv ;; }\n"
        + "}\n"
        + "view: events { dimension: user_id { type: number } }\n";

    final CoverageRecord rec =
        record(classify(lookml), "users.lifetime_value");
    assertEquals(Classification.REFUSE, rec.classification());
    assertEquals(ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE,
        rec.reasonCode());
  }

  /** Chained-many topology: a join reached THROUGH another join that itself
   * fans out (orders →(one_to_many) items →(one_to_many) item_taxes). The
   * intermediate view is on both the "many" side of one fan-out and the "one"
   * side of another, so the explore multiplies twice: non-star, REFUSE the
   * whole explore. */
  @Test void chainedManyTopologyRefuses() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "  join: item_taxes {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${items.id} = ${item_taxes.item_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: items { dimension: id { type: number } }\n"
        + "view: item_taxes { dimension: item_id { type: number } }\n";

    final CoverageRecord ex = record(classify(lookml), "explore:orders");
    assertEquals(Classification.REFUSE, ex.classification());
    assertEquals(ReasonCode.REFUSE_NON_STAR_TOPOLOGY, ex.reasonCode());
  }

  /** A measure on the "many" side of a one_to_many is safe: aggregating the
   * leaf grain does not fan out. Regression guard against over-refusal — a
   * count on the joined (many-side) view stays CLEAN. */
  @Test void measureOnManySideOfOneToManyStaysClean() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders { dimension: id { type: number primary_key: yes } }\n"
        + "view: items { measure: item_count { type: count } }\n";

    assertEquals(Classification.CLEAN,
        record(classify(lookml), "items.item_count").classification());
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

  /** An inner join silently drops fact rows whose FK does not match (the
   * LookML default is left_outer); it is not a fact→dim left-join star, so the
   * explore is REFUSED rather than emitting a silently-wrong cube (#98). */
  @Test void innerJoinRefusesExplore() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: customers {\n"
        + "    type: inner\n"
        + "    sql_on: ${orders.customer_id} = ${customers.id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: customers { dimension: id { primary_key: yes } }\n";

    final CoverageRecord ex = record(classify(lookml), "explore:orders");
    assertEquals(Classification.REFUSE, ex.classification());
    assertEquals(ReasonCode.REFUSE_NON_STAR_TOPOLOGY, ex.reasonCode());
  }

  /** A right_outer join inverts the grain (keeps unmatched dim rows, drops
   * unmatched fact rows from the left): not a star, REFUSE the explore. */
  @Test void rightOuterJoinRefusesExplore() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: customers {\n"
        + "    type: right_outer\n"
        + "    sql_on: ${orders.customer_id} = ${customers.id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: customers { dimension: id { primary_key: yes } }\n";

    final CoverageRecord ex = record(classify(lookml), "explore:orders");
    assertEquals(Classification.REFUSE, ex.classification());
    assertEquals(ReasonCode.REFUSE_NON_STAR_TOPOLOGY, ex.reasonCode());
  }

  /** A cross join produces a cartesian product: non-star, REFUSE. */
  @Test void crossJoinRefusesExplore() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: calendar {\n"
        + "    type: cross\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: calendar { dimension: d { type: date } }\n";

    final CoverageRecord ex = record(classify(lookml), "explore:orders");
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

  // --- bridge two-hop (#124) ---------------------------------------------

  /** The canonical many-to-many bridge two-hop (fact →one_to_many→ bridge
   * →many_to_one→ dim) with single-column keys and a fact primary key
   * reclassifies REFUSE→CLEAN: the pair maps to a <BridgeLink> (#124/#107). */
  @Test void bridgeTwoHopReclassifiesClean() {
    final String lookml = ""
        + "explore: accounts {\n"
        + "  join: owners {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${accounts.account_id} = ${owners.account_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "  join: customers {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${owners.customer_id} = ${customers.customer_id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: accounts { dimension: account_id { primary_key: yes }\n"
        + "  measure: bal { type: sum sql: ${TABLE}.balance ;; } }\n"
        + "view: owners { dimension: account_id {} dimension: customer_id {} }\n"
        + "view: customers { dimension: customer_id { primary_key: yes } }\n";

    final CoverageRecord ex = record(classify(lookml), "explore:accounts");
    assertEquals(Classification.CLEAN, ex.classification());
    assertEquals(ReasonCode.CLEAN, ex.reasonCode());
  }

  /** An explicit-direct {@code many_to_many} hop into the bridge, paired with a
   * recoverable {@code many_to_one} dim hop, also reclassifies CLEAN (#124). */
  @Test void bridgeExplicitManyToManyHopReclassifiesClean() {
    final String lookml = ""
        + "explore: accounts {\n"
        + "  join: owners {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${accounts.account_id} = ${owners.account_id} ;;\n"
        + "    relationship: many_to_many\n"
        + "  }\n"
        + "  join: customers {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${owners.customer_id} = ${customers.customer_id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: accounts { dimension: account_id { primary_key: yes }\n"
        + "  measure: c { type: count } }\n"
        + "view: owners { dimension: account_id {} dimension: customer_id {} }\n"
        + "view: customers { dimension: customer_id { primary_key: yes } }\n";

    assertEquals(Classification.CLEAN,
        record(classify(lookml), "explore:accounts").classification());
  }

  /** A bridge whose fact→bridge hop has a COMPOUND key cannot recover a single
   * bridge column pair, so it stays REFUSE (never a silently-wrong bridge). */
  @Test void compoundKeyBridgeStaysRefuse() {
    final String lookml = ""
        + "explore: accounts {\n"
        + "  join: owners {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${accounts.account_id} = ${owners.account_id}\n"
        + "      AND ${accounts.tenant} = ${owners.tenant} ;;\n"
        + "    relationship: many_to_many\n"
        + "  }\n"
        + "  join: customers {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${owners.customer_id} = ${customers.customer_id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: accounts { dimension: account_id { primary_key: yes }\n"
        + "  measure: c { type: count } }\n"
        + "view: owners { dimension: account_id {} dimension: customer_id {} }\n"
        + "view: customers { dimension: customer_id { primary_key: yes } }\n";

    final CoverageRecord ex = record(classify(lookml), "explore:accounts");
    assertEquals(Classification.REFUSE, ex.classification());
    assertEquals(ReasonCode.REFUSE_NON_STAR_TOPOLOGY, ex.reasonCode());
  }

  /** A bridge two-hop whose FACT view declares no primary key cannot supply the
   * grain key the full-count de-dup needs, so it stays REFUSE (#124). */
  @Test void bridgeWithoutFactPrimaryKeyStaysRefuse() {
    final String lookml = ""
        + "explore: accounts {\n"
        + "  join: owners {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${accounts.account_id} = ${owners.account_id} ;;\n"
        + "    relationship: many_to_many\n"
        + "  }\n"
        + "  join: customers {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${owners.customer_id} = ${customers.customer_id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: accounts { dimension: account_id {}\n"
        + "  measure: c { type: count } }\n"
        + "view: owners { dimension: account_id {} dimension: customer_id {} }\n"
        + "view: customers { dimension: customer_id { primary_key: yes } }\n";

    assertEquals(ReasonCode.REFUSE_NON_STAR_TOPOLOGY,
        record(classify(lookml), "explore:accounts").reasonCode());
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

  // --- bounded Liquid (#118) --------------------------------------------

  /** A {{ _user_attributes['region'] }} reference in a measure filter is a
   * bounded user-attribute pattern: DEGRADE routed to a session.region
   * parameter (#118), not REFUSE_LIQUID. */
  @Test void userAttributeLiquidInFilterDegradesBounded() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: regional_rev {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "    filters: [region: \"{{ _user_attributes['region'] }}\"]\n"
        + "  }\n"
        + "}\n";

    final CoverageRecord rec = record(classify(lookml), "orders.regional_rev");
    assertEquals(Classification.DEGRADE, rec.classification());
    assertEquals(ReasonCode.DEGRADE_LIQUID_BOUNDED, rec.reasonCode());
    assertEquals("#118", rec.relatedIssue().orElseThrow());
  }

  /** A {{ _user_attributes._region }} dotted reference is equally bounded. */
  @Test void userAttributeDottedLiquidDegradesBounded() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: tenant {\n"
        + "    sql: {{ _user_attributes._tenant_id }} ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.DEGRADE_LIQUID_BOUNDED,
        record(classify(lookml), "orders.tenant").reasonCode());
  }

  /** A {% parameter region %} use of a DECLARED bounded parameter is bounded:
   * DEGRADE (field-switching maps to a Saiku-layer WITH MEMBER, #118). */
  @Test void parameterUseOfDeclaredParameterDegradesBounded() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  parameter: region { type: unquoted\n"
        + "    allowed_value: { value: \"east\" } }\n"
        + "  dimension: dyn {\n"
        + "    sql: {% parameter region %} ;;\n"
        + "  }\n"
        + "}\n";

    final CoverageRecord rec = record(classify(lookml), "orders.dyn");
    assertEquals(Classification.DEGRADE, rec.classification());
    assertEquals(ReasonCode.DEGRADE_LIQUID_BOUNDED, rec.reasonCode());
    // and the declaration itself is still CLEAN (#105).
    assertEquals(Classification.CLEAN,
        record(classify(lookml), "orders.region").classification());
  }

  /** A {% parameter X %} use of an UNDECLARED parameter cannot be resolved to a
   * bounded enumeration, so it stays REFUSE_LIQUID (the safety boundary). */
  @Test void parameterUseOfUndeclaredParameterStaysRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: dyn { sql: {% parameter mystery %} ;; }\n"
        + "}\n";

    assertEquals(ReasonCode.REFUSE_LIQUID,
        record(classify(lookml), "orders.dyn").reasonCode());
  }

  /** A {% condition %} filter tied to a field is a bounded parameter-bound
   * filter: DEGRADE (#118). */
  @Test void conditionTagDegradesBounded() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: filtered {\n"
        + "    type: sum\n"
        + "    sql: SUM({% condition date_filter %} ${TABLE}.amount "
        + "{% endcondition %}) ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.DEGRADE_LIQUID_BOUNDED,
        record(classify(lookml), "orders.filtered").reasonCode());
  }

  /** Computed Liquid control flow ({% if %}) producing SQL stays REFUSE_LIQUID
   * — the safety boundary that prevents silently-wrong emits (#118). */
  @Test void computedIfLiquidStaysRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: computed {\n"
        + "    type: sum\n"
        + "    sql: {% if _user_attributes['x'] == 'a' %} ${TABLE}.a "
        + "{% else %} ${TABLE}.b {% endif %} ;;\n"
        + "  }\n"
        + "}\n";

    final CoverageRecord rec = record(classify(lookml), "orders.computed");
    assertEquals(Classification.REFUSE, rec.classification());
    assertEquals(ReasonCode.REFUSE_LIQUID, rec.reasonCode());
  }

  /** An arithmetic / field-value {{ }} output is computed, not a bounded
   * user-attribute reference: stays REFUSE_LIQUID. */
  @Test void arithmeticOutputLiquidStaysRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: m {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount * {{ value }} ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.REFUSE_LIQUID,
        record(classify(lookml), "orders.m").reasonCode());
  }

  /** A field mixing a bounded user-attribute ref with arbitrary control flow is
   * refused as a whole (any arbitrary fragment refuses the field). */
  @Test void mixedBoundedAndArbitraryLiquidStaysRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: m {\n"
        + "    type: sum\n"
        + "    sql: {{ _user_attributes['x'] }} ;;\n"
        + "    filters: [y: \"{% if z %}a{% endif %}\"]\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.REFUSE_LIQUID,
        record(classify(lookml), "orders.m").reasonCode());
  }

  /** ${TABLE}.col is NOT Liquid and is unaffected — CLEAN (regression guard). */
  @Test void tableRefIsNotLiquidAndStaysClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: rev { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n";

    assertEquals(Classification.CLEAN,
        record(classify(lookml), "orders.rev").classification());
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

  // --- sum_distinct / average_distinct (#117) ---------------------------

  /** A sum_distinct whose sql_distinct_key resolves to the base view's own
   * primary_key dimension de-duplicates on the fact grain — one row per key —
   * so it equals a plain SUM and is CLEAN. Mapped to a symmetric-aggregate-safe
   * SUM (#117 / #103). */
  @Test void sumDistinctOnPrimaryKeyIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: total {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${id} ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    final CoverageRecord rec = record(classify(lookml), "orders.total");
    assertEquals(Classification.CLEAN, rec.classification());
    assertEquals(ReasonCode.CLEAN, rec.reasonCode());
  }

  /** average_distinct keyed on the primary key is likewise CLEAN. */
  @Test void averageDistinctOnPrimaryKeyIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: avg_amt {\n"
        + "    type: average_distinct\n"
        + "    sql_distinct_key: ${TABLE}.order_id ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.CLEAN,
        record(classify(lookml), "orders.avg_amt").reasonCode());
  }

  /** A sum_distinct whose sql_distinct_key is a CROSS-VIEW reference
   * ({@code ${customer.sfid}}) cannot be de-duplicated at measure level by the
   * engine — the de-dup grain would be a foreign key — so it stays REFUSE
   * (never silently wrong). */
  @Test void sumDistinctOnCrossViewKeyStaysRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: total {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${customer.sfid} ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR,
        record(classify(lookml), "orders.total").reasonCode());
  }

  /** #119: a sum_distinct whose sql_distinct_key resolves to a real SAME-VIEW
   * column that is NOT the primary key now maps to an M4 measure-level distinct
   * grain (distinctKeyColumn) — the engine de-duplicates on the declared key
   * without a bridge — so it is CLEAN (the #117 residual recovered). */
  @Test void sumDistinctOnSameViewNonPrimaryKeyIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  dimension: basket { type: number sql: ${TABLE}.basket_id ;; }\n"
        + "  measure: total {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${TABLE}.basket_id ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.CLEAN,
        record(classify(lookml), "orders.total").reasonCode());
  }

  /** #119: an average_distinct on a same-view non-PK key is likewise CLEAN. */
  @Test void averageDistinctOnSameViewNonPrimaryKeyIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: avg_amt {\n"
        + "    type: average_distinct\n"
        + "    sql_distinct_key: ${TABLE}.basket_id ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.CLEAN,
        record(classify(lookml), "orders.avg_amt").reasonCode());
  }

  /** #119: a same-view non-PK distinct key is CLEAN even with NO primary_key
   * declared — the de-dup grain is the declared key itself, not the fact PK. */
  @Test void sumDistinctOnSameViewKeyWithoutPrimaryKeyIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: total {\n"
        + "    type: sum_distinct\n"
        + "    sql_distinct_key: ${TABLE}.basket_id ;;\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.CLEAN,
        record(classify(lookml), "orders.total").reasonCode());
  }

  /** A sum_distinct with no sql_distinct_key and no primary_key fallback stays
   * REFUSE (no resolvable de-dup grain). */
  @Test void sumDistinctWithNoResolvableKeyStaysRefused() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: total {\n"
        + "    type: sum_distinct\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR,
        record(classify(lookml), "orders.total").reasonCode());
  }

  /** A sum_distinct with no explicit sql_distinct_key falls back to the base
   * view's primary_key dimension — CLEAN. */
  @Test void sumDistinctFallsBackToPrimaryKeyIsClean() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.order_id ;; }\n"
        + "  measure: total {\n"
        + "    type: sum_distinct\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "}\n";

    assertEquals(ReasonCode.CLEAN,
        record(classify(lookml), "orders.total").reasonCode());
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

  /** An access_filter whose {@code field:} is a Liquid-templated reference
   * cannot be mapped to either a dimension-key grant (#115) or a usable
   * predicate column (#106): the emitter's {@code RowSecurity.column()} drops a
   * Liquid field, so a DEGRADE here would emit the cube with NO row security at
   * all (fail-open). The classifier MUST REFUSE the explore so the unsecured
   * cube is never emitted (fail-closed). */
  @Test void accessFilterWithLiquidFieldRefusesExplore() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  access_filter: {\n"
        + "    field: \"{{ _parameters.scope_field }}\"\n"
        + "    user_attribute: scope\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n";
    final CoverageRecord rec = record(classify(lookml), "explore:orders");
    assertEquals(Classification.REFUSE, rec.classification(),
        "an unmappable (Liquid field:) access_filter must refuse the explore,"
        + " never emit an unsecured cube");
    assertEquals(ReasonCode.REFUSE_ACCESS_FILTER_UNMAPPABLE, rec.reasonCode());
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

  // --- documented v1 limitations (pinned so they cannot silently drift) ---

  /** v1 limitation: the model is classified AS PARSED — {@code extends} /
   * refinements are NOT flattened. A measure that is only additive AFTER a
   * refinement adds {@code type: sum} is classified on its literal text here,
   * so an additive-only-after-refinement measure with no type in its own block
   * is NOT detected as a fan-out sum. This pins that documented behaviour: the
   * base measure (no type) classifies CLEAN despite sitting on a fan-out view,
   * because the classifier never sees the refinement's type. */
  @Test void measureAdditiveOnlyAfterRefinementIsClassifiedAsParsed() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        // No type: in this block — a refinement (+orders) would add type: sum
        // elsewhere. The classifier sees only this literal text.
        + "  measure: revenue { sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "view: items { dimension: order_id { type: number } }\n";

    final CoverageRecord rec = record(classify(lookml), "orders.revenue");
    // Classified as parsed: no type → not an additive aggregate → not a
    // fan-out refusal. Documented v1 limitation, pinned.
    assertEquals(Classification.CLEAN, rec.classification());
  }

  /** An access_filter targeting a JOINED view's dimension key
   * ({@code users.country}, modelled on the users view) is still a simple
   * modelled-dimension reference, so the explore stays CLEAN (not a
   * PredicateGrant DEGRADE). Guards the dimension-index across views. */
  @Test void accessFilterCleanDimensionStillCleanWithJoinedDimKey() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: users {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.user_id} = ${users.id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "  access_filter: { field: users.country user_attribute: country }\n"
        + "}\n"
        + "view: orders { measure: c { type: count } }\n"
        + "view: users {\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n";

    assertEquals(Classification.CLEAN,
        record(classify(lookml), "explore:orders").classification());
  }

  /** Liquid in a presentation-only key (here {@code label}) does NOT shape
   * engine SQL, so a bounded user-attribute reference there DEGRADEs (the
   * templated label is dropped) rather than refusing the whole field. Pins the
   * resolution of the LIQUID_PRESENTATION_KEYS branch: arbitrary Liquid in a
   * presentation-only key degrades, it does not refuse. */
  @Test void arbitraryLiquidInPresentationKeyDegradesNotRefuses() {
    final String lookml = ""
        + "explore: orders {}\n"
        + "view: orders {\n"
        + "  measure: rev {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "    label: \"Revenue for {% if x %}A{% else %}B{% endif %}\"\n"
        + "  }\n"
        + "}\n";

    final CoverageRecord rec = record(classify(lookml), "orders.rev");
    assertEquals(Classification.DEGRADE, rec.classification());
    assertEquals(ReasonCode.DEGRADE_LIQUID_BOUNDED, rec.reasonCode());
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

  // --- #115 gap 6: un-parseable sql_on join → DEGRADE note --------------

  /** A star-eligible join whose sql_on cannot be reduced to a single
   * fact/dimension key pair (a multi-column / expression condition) DEGRADEs
   * with DEGRADE_JOIN_SQL_ON_UNPARSEABLE instead of vanishing silently (#115). */
  @Test void unparseableSqlOnJoinDegrades() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: c { type: count }\n"
        + "}\n"
        + "view: users {\n"
        + "  sql_table_name: users ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  join: users { type: left_outer relationship: many_to_one\n"
        + "    sql_on: LOWER(${orders.region}) = LOWER('x') ;; }\n"
        + "}\n";
    final ClassificationResult r = classify(lookml);
    final CoverageRecord join = record(r, "explore:orders.join:users");
    assertEquals(ReasonCode.DEGRADE_JOIN_SQL_ON_UNPARSEABLE,
        join.reasonCode());
    assertEquals(Classification.DEGRADE, join.classification());
    // The explore itself still classifies (star-eligible, left_outer).
    assertEquals(Classification.CLEAN,
        record(r, "explore:orders").classification());
  }

  /** A resolvable single-key join records NO unparseable-join DEGRADE. */
  @Test void resolvableSqlOnJoinHasNoDegradeNote() {
    String lookml =
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: c { type: count }\n"
        + "}\n"
        + "view: users {\n"
        + "  sql_table_name: users ;;\n"
        + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  join: users { type: left_outer relationship: many_to_one\n"
        + "    sql_on: ${orders.user_id} = ${users.user_id} ;; }\n"
        + "}\n";
    final ClassificationResult r = classify(lookml);
    assertTrue(r.records().stream().noneMatch(rec ->
            rec.reasonCode() == ReasonCode.DEGRADE_JOIN_SQL_ON_UNPARSEABLE),
        () -> r.records().toString());
  }

  // --- #125: from:-aliased join sql_on keyed by join name --------------

  /** A {@code from:}-aliased join whose {@code sql_on} references the join by
   * its JOIN NAME (not the {@code from:} target) is resolvable and records NO
   * unparseable-join DEGRADE — the #125 core fix. Before #125 it degraded
   * because the classifier looked for {@code ${logical_subs.col}} instead of
   * {@code ${current_state.col}}. */
  @Test void fromAliasedJoinKeyedByJoinNameIsClean() {
    String lookml =
        "view: daily { sql_table_name: daily ;;\n"
        + "  dimension: subscription_id { type: number"
        + "    sql: ${TABLE}.subscription_id ;; }\n"
        + "  measure: c { type: count }\n"
        + "}\n"
        + "view: logical_subs { sql_table_name: logical_subs ;;\n"
        + "  dimension: id { type: number primary_key: yes"
        + "    sql: ${TABLE}.id ;; }\n"
        + "}\n"
        + "explore: daily {\n"
        + "  join: current_state {\n"
        + "    from: logical_subs\n"
        + "    relationship: many_to_one\n"
        + "    sql_on: ${daily.subscription_id} = ${current_state.id} ;;\n"
        + "  }\n"
        + "}\n";
    final ClassificationResult r = classify(lookml);
    assertEquals(Classification.CLEAN,
        record(r, "explore:daily").classification());
    assertTrue(r.records().stream().noneMatch(rec ->
            rec.reasonCode() == ReasonCode.DEGRADE_JOIN_SQL_ON_UNPARSEABLE),
        () -> r.records().toString());
  }

  /** A from:-aliased join keyed by the {@code from:} TARGET (the old, wrong way)
   * is NOT resolvable — the join name is the only valid namespace — so it
   * correctly DEGRADEs (proves we did not loosen the gate to accept either). */
  @Test void fromAliasedJoinKeyedByTargetStillDegrades() {
    String lookml =
        "view: daily { sql_table_name: daily ;;\n"
        + "  dimension: subscription_id { type: number"
        + "    sql: ${TABLE}.subscription_id ;; }\n"
        + "  measure: c { type: count }\n"
        + "}\n"
        + "view: logical_subs { sql_table_name: logical_subs ;;\n"
        + "  dimension: id { type: number sql: ${TABLE}.id ;; }\n"
        + "}\n"
        + "explore: daily {\n"
        + "  join: current_state {\n"
        + "    from: logical_subs\n"
        + "    relationship: many_to_one\n"
        + "    sql_on: ${daily.subscription_id} = ${logical_subs.id} ;;\n"
        + "  }\n"
        + "}\n";
    final ClassificationResult r = classify(lookml);
    final CoverageRecord join =
        record(r, "explore:daily.join:current_state");
    assertEquals(ReasonCode.DEGRADE_JOIN_SQL_ON_UNPARSEABLE,
        join.reasonCode());
  }

  /** A constant/metadata join (one view ref + a string literal, no fact-side
   * key) must REMAIN DEGRADE — do not over-convert (#125). */
  @Test void constantMetadataJoinStillDegrades() {
    String lookml =
        "view: f { sql_table_name: f ;; measure: c { type: count } }\n"
        + "view: table_metadata { sql_table_name: table_metadata ;;\n"
        + "  dimension: table_name { type: string"
        + "    sql: ${TABLE}.table_name ;; }\n"
        + "}\n"
        + "explore: f {\n"
        + "  join: meta {\n"
        + "    from: table_metadata\n"
        + "    relationship: many_to_one\n"
        + "    sql_on: ${meta.table_name} = 'foo_v1' ;;\n"
        + "  }\n"
        + "}\n";
    final ClassificationResult r = classify(lookml);
    final CoverageRecord join = record(r, "explore:f.join:meta");
    assertEquals(ReasonCode.DEGRADE_JOIN_SQL_ON_UNPARSEABLE,
        join.reasonCode());
  }

  /** A compound (AND-chained multi-column) from:-aliased join must NOT be
   * mis-recovered to a single wrong key — it stays DEGRADE (#125). */
  @Test void compoundKeyFromAliasedJoinStillDegrades() {
    String lookml =
        "view: f { sql_table_name: f ;;\n"
        + "  dimension: a { type: number sql: ${TABLE}.a ;; }\n"
        + "  dimension: b { type: number sql: ${TABLE}.b ;; }\n"
        + "  measure: c { type: count }\n"
        + "}\n"
        + "view: dim { sql_table_name: dim ;;\n"
        + "  dimension: x { type: number sql: ${TABLE}.x ;; }\n"
        + "  dimension: y { type: number sql: ${TABLE}.y ;; }\n"
        + "}\n"
        + "explore: f {\n"
        + "  join: d {\n"
        + "    from: dim\n"
        + "    relationship: many_to_one\n"
        + "    sql_on: ${f.a} = ${d.x} AND ${f.b} = ${d.y} ;;\n"
        + "  }\n"
        + "}\n";
    final ClassificationResult r = classify(lookml);
    final CoverageRecord join = record(r, "explore:f.join:d");
    assertEquals(ReasonCode.DEGRADE_JOIN_SQL_ON_UNPARSEABLE,
        join.reasonCode());
  }

  // --- #115 gap 2: unknown value_format_name → DEGRADE note ------------

  /** A measure with an unknown value_format_name DEGRADEs; a known preset
   * stays CLEAN (#115). */
  @Test void unknownValueFormatNameDegrades() {
    String lookml =
        "view: f {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: known { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format_name: usd }\n"
        + "  measure: unknown { type: sum sql: ${TABLE}.amount ;;"
        + "    value_format_name: weird_custom }\n"
        + "}\n"
        + "explore: f { }\n";
    final ClassificationResult r = classify(lookml);
    assertEquals(Classification.CLEAN, record(r, "f.known").classification());
    assertEquals(ReasonCode.DEGRADE_VALUE_FORMAT_NAME_UNKNOWN,
        record(r, "f.unknown").reasonCode());
  }
}

// End LookmlClassifierTest.java
