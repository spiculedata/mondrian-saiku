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
package mondrian.lookml.parse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the vendored + patched LookML parser ({@link LookmlParser}).
 *
 * <p>Each grammar patch from issue #98 is regression-guarded by a named test:
 * <ul>
 *   <li>patch (b) multiple top-level objects: {@link #parsesAllTopLevelObjects}
 *   <li>patch (a) refinements / dotted refs: {@link #parsesRefinement},
 *       {@link #parsesDottedRefsInFieldsAndSqlOn}
 *   <li>patch (c) verbatim code blocks / Liquid:
 *       {@link #preservesLiquidInSqlVerbatim}
 * </ul>
 */
class LookmlParserTest {

  /** PATCH (b): a file with 2 views + 1 explore must parse ALL of them.
   * Upstream silently dropped all but the first top-level object. */
  @Test void parsesAllTopLevelObjects() {
    // Arrange
    final String lookml = ""
        + "view: orders {\n"
        + "  sql_table_name: public.orders ;;\n"
        + "}\n"
        + "view: customers {\n"
        + "  sql_table_name: public.customers ;;\n"
        + "}\n"
        + "explore: orders {\n"
        + "  join: customers {\n"
        + "    type: left_outer\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode doc = LookmlParser.parse(lookml);

    // Assert
    final List<LookmlNode> views = doc.children("view");
    final List<LookmlNode> explores = doc.children("explore");
    assertEquals(2, views.size(), "both views must survive (patch b)");
    assertEquals(1, explores.size());
    assertEquals("orders", views.get(0).name().orElseThrow());
    assertEquals("customers", views.get(1).name().orElseThrow());
    assertEquals("orders", explores.get(0).name().orElseThrow());
  }

  /** PATCH (a): a refinement '+orders { ... }' must parse, with the leading
   * '+' preserved in the object name. */
  @Test void parsesRefinement() {
    // Arrange
    final String lookml = ""
        + "view: +orders {\n"
        + "  dimension: status {\n"
        + "    type: string\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode doc = LookmlParser.parse(lookml);

    // Assert
    final LookmlNode refinement = doc.children("view").get(0);
    assertEquals("+orders", refinement.name().orElseThrow());
    assertEquals("status",
        refinement.children("dimension").get(0).name().orElseThrow());
  }

  /** PATCH (a) + (c): dotted refs in a fields list and inside a sql_on code
   * block. */
  @Test void parsesDottedRefsInFieldsAndSqlOn() {
    // Arrange
    final String lookml = ""
        + "explore: orders {\n"
        + "  fields: [orders.id, customers.name]\n"
        + "  join: customers {\n"
        + "    sql_on: ${orders.id} = ${customers.order_id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode explore = LookmlParser.parse(lookml).children("explore")
        .get(0);

    // Assert: the fields list holds two dotted identifiers.
    final Value fields = explore.value("fields").orElseThrow();
    assertTrue(fields instanceof Values.ListValue);
    final List<ValueImpl> items = ((Values.ListValue) fields).list;
    assertEquals(2, items.size());
    assertEquals("orders.id", ((Values.IdentifierValue) items.get(0)).id);
    assertEquals("customers.name", ((Values.IdentifierValue) items.get(1)).id);

    // Assert: sql_on is a code block with the dotted ${...} refs verbatim.
    final LookmlNode join = explore.children("join").get(0);
    final Value sqlOn = join.value("sql_on").orElseThrow();
    assertTrue(sqlOn instanceof Values.CodeValue);
    final String code = ((Values.CodeValue) sqlOn).s;
    assertTrue(code.contains("${orders.id}"), code);
    assertTrue(code.contains("${customers.order_id}"), code);
  }

  /** PATCH (c): Liquid inside an sql block must be preserved byte-for-byte. */
  @Test void preservesLiquidInSqlVerbatim() {
    // Arrange
    final String liquid =
        "{% if user_attributes['region'] %} region = {{ _user_attributes"
            + "['region'] }} {% else %} 1=1 {% endif %}";
    final String lookml = ""
        + "view: orders {\n"
        + "  dimension: scoped {\n"
        + "    sql: " + liquid + " ;;\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode dim = LookmlParser.parse(lookml)
        .children("view").get(0)
        .children("dimension").get(0);

    // Assert
    final Value sql = dim.value("sql").orElseThrow();
    assertTrue(sql instanceof Values.CodeValue);
    assertTrue(((Values.CodeValue) sql).s.contains(liquid),
        "Liquid must be preserved verbatim: " + ((Values.CodeValue) sql).s);
  }

  /** Modern LookML field shapes: count_distinct, dimension_group (time and
   * duration), parameter with allowed_value blocks. */
  @Test void parsesModernFieldShapes() {
    // Arrange
    final String lookml = ""
        + "view: orders {\n"
        + "  measure: distinct_users {\n"
        + "    type: count_distinct\n"
        + "    sql: ${user_id} ;;\n"
        + "  }\n"
        + "  dimension_group: created {\n"
        + "    type: time\n"
        + "    timeframes: [date, week, month]\n"
        + "    sql: ${TABLE}.created_at ;;\n"
        + "  }\n"
        + "  dimension_group: since_signup {\n"
        + "    type: duration\n"
        + "    intervals: [day, month]\n"
        + "    sql_start: ${signup_raw} ;;\n"
        + "    sql_end: ${TABLE}.now ;;\n"
        + "  }\n"
        + "  parameter: metric_picker {\n"
        + "    type: unquoted\n"
        + "    allowed_value: { label: \"Revenue\" value: \"rev\" }\n"
        + "    allowed_value: { label: \"Count\" value: \"cnt\" }\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode view = LookmlParser.parse(lookml).children("view").get(0);

    // Assert
    final LookmlNode measure = view.children("measure").get(0);
    assertEquals("count_distinct", measure.stringValue("type").orElseThrow());

    final List<LookmlNode> dimGroups = view.children("dimension_group");
    assertEquals(2, dimGroups.size());
    assertEquals("time", dimGroups.get(0).stringValue("type").orElseThrow());
    assertEquals("duration",
        dimGroups.get(1).stringValue("type").orElseThrow());

    final LookmlNode param = view.children("parameter").get(0);
    assertEquals(2, param.children("allowed_value").size());
    assertEquals("Revenue",
        param.children("allowed_value").get(0).stringValue("label")
            .orElseThrow());
  }

  /** A derived_table with an sql block and a datagroup_trigger, plus an
   * access_filter. */
  @Test void parsesDerivedTableAndAccessFilter() {
    // Arrange
    final String lookml = ""
        + "view: daily_orders {\n"
        + "  derived_table: {\n"
        + "    sql: SELECT 1 ;;\n"
        + "    datagroup_trigger: orders_default\n"
        + "  }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  access_filter: {\n"
        + "    field: orders.region\n"
        + "    user_attribute: region\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode doc = LookmlParser.parse(lookml);

    // Assert
    final LookmlNode derived = doc.children("view").get(0)
        .child("derived_table").orElseThrow();
    // The code block is captured verbatim, including the leading space after
    // the colon and the trailing space before ';;'.
    assertEquals(" SELECT 1 ", ((Values.CodeValue)
        derived.value("sql").orElseThrow()).s);
    assertEquals("orders_default",
        derived.stringValue("datagroup_trigger").orElseThrow());

    final LookmlNode accessFilter = doc.children("explore").get(0)
        .child("access_filter").orElseThrow();
    assertEquals("orders.region",
        accessFilter.stringValue("field").orElseThrow());
  }

  /** A realistic multi-object .model-style fixture: assert key nodes are
   * reachable through the public API. */
  @Test void roundTripsRealisticModelFixture() {
    // Arrange
    final String lookml = ""
        + "connection: \"warehouse\"\n"
        + "include: \"*.view.lkml\"\n"
        + "explore: orders {\n"
        + "  label: \"Orders\"\n"
        + "  join: customers {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.customer_id} = ${customers.id} ;;\n"
        + "    relationship: many_to_one\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        + "  sql_table_name: public.orders ;;\n"
        + "  dimension: id {\n"
        + "    primary_key: yes\n"
        + "    type: number\n"
        + "    sql: ${TABLE}.id ;;\n"
        + "  }\n"
        + "  measure: total_revenue {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "    value_format_name: usd\n"
        + "  }\n"
        + "}\n"
        + "view: customers {\n"
        + "  dimension: id { primary_key: yes }\n"
        + "  dimension: name { type: string }\n"
        + "}\n";

    // Act
    final LookmlNode doc = LookmlParser.parse(lookml);

    // Assert: top-level scalars and objects.
    assertEquals("warehouse", doc.stringValue("connection").orElseThrow());
    assertEquals(1, doc.children("explore").size());
    assertEquals(2, doc.children("view").size());

    final LookmlNode ordersView = doc.children("view").get(0);
    assertEquals("orders", ordersView.name().orElseThrow());
    final LookmlNode revenue = ordersView.children("measure").get(0);
    assertEquals("sum", revenue.stringValue("type").orElseThrow());
    assertEquals("yes",
        ordersView.children("dimension").get(0).stringValue("primary_key")
            .orElseThrow());

    // the explore join reaches its conformed dimension key
    final LookmlNode join = doc.children("explore").get(0)
        .children("join").get(0);
    assertEquals("many_to_one",
        join.stringValue("relationship").orElseThrow());
  }

  /** Malformed LookML must throw a clear, typed exception (boundary
   * validation). */
  @Test void rejectsMalformedLookmlWithClearException() {
    // Arrange: unterminated object.
    final String bad = "view: orders {\n  dimension: id {\n";

    // Act / Assert
    final LookmlParseException ex = assertThrows(LookmlParseException.class,
        () -> LookmlParser.parse(bad));
    assertTrue(ex.getMessage().toLowerCase().contains("lookml"),
        ex.getMessage());
  }

  // ------------------------------------------------------------------
  // Issue #98 real-world hardening: each fix below has a confirmed minimal
  // repro drawn from the validation sweep over 6 public Looker repos.
  // ------------------------------------------------------------------

  /** FIX 1: a trailing comma in a {@code [...]} list must be tolerated, and a
   * leading/empty element skipped. Repro: 68 files
   * (e.g. {@code timeframes: [, date, week]}). */
  @Test void parsesTrailingAndLeadingCommaInList() {
    // Arrange
    final String lookml = ""
        + "view: v {\n"
        + "  set: s { fields: [a, b,] }\n"
        + "  dimension_group: created {\n"
        + "    timeframes: [, date, week]\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode view = LookmlParser.parse(lookml).children("view").get(0);

    // Assert: trailing comma dropped, two real elements survive.
    final Value fields = view.children("set").get(0).value("fields")
        .orElseThrow();
    final List<ValueImpl> items = ((Values.ListValue) fields).list;
    assertEquals(2, items.size(), "trailing comma must be ignored");
    assertEquals("a", ((Values.IdentifierValue) items.get(0)).id);
    assertEquals("b", ((Values.IdentifierValue) items.get(1)).id);

    // Assert: leading empty slot dropped, two timeframes survive.
    final Value tf = view.children("dimension_group").get(0).value("timeframes")
        .orElseThrow();
    final List<ValueImpl> tfs = ((Values.ListValue) tf).list;
    assertEquals(2, tfs.size(), "leading empty slot must be ignored");
    assertEquals("date", ((Values.IdentifierValue) tfs.get(0)).id);
    assertEquals("week", ((Values.IdentifierValue) tfs.get(1)).id);
  }

  /** FIX 2: a {@code *} wildcard as the trailing char of a field reference,
   * incl. {@code view.*}. Repro: 50 files
   * (e.g. {@code drill_fields: [detail*]}, {@code fields: [orders.*]}). */
  @Test void parsesWildcardFieldRefs() {
    // Arrange
    final String lookml = ""
        + "explore: e {\n"
        + "  fields: [orders.*, detail*, all]\n"
        + "}\n";

    // Act
    final LookmlNode explore = LookmlParser.parse(lookml).children("explore")
        .get(0);

    // Assert
    final List<ValueImpl> items =
        ((Values.ListValue) explore.value("fields").orElseThrow()).list;
    assertEquals(3, items.size());
    assertEquals("orders.*", ((Values.IdentifierValue) items.get(0)).id);
    assertEquals("detail*", ((Values.IdentifierValue) items.get(1)).id);
    assertEquals("all", ((Values.IdentifierValue) items.get(2)).id);
  }

  /** FIX 3: a leading-underscore identifier (object name and ref). Repro: 37
   * files (e.g. {@code dimension: _dbt_source {}}). */
  @Test void parsesLeadingUnderscoreIdentifiers() {
    // Arrange
    final String lookml = ""
        + "view: v {\n"
        + "  dimension: _dbt_source {\n"
        + "    type: string\n"
        + "    sql: ${TABLE}._loaded_at ;;\n"
        + "  }\n"
        + "  set: _hidden { fields: [_dbt_source] }\n"
        + "}\n";

    // Act
    final LookmlNode view = LookmlParser.parse(lookml).children("view").get(0);

    // Assert
    assertEquals("_dbt_source",
        view.children("dimension").get(0).name().orElseThrow());
    assertEquals("_hidden", view.children("set").get(0).name().orElseThrow());
  }

  /** FIX 4: backslash escape sequences inside a quoted string must be consumed
   * (kept verbatim, incl. the backslash). Repro: 6 files
   * (e.g. {@code value_format: "0.00\%"}). */
  @Test void parsesBackslashEscapesInQuotedStrings() {
    // Arrange
    final String lookml = ""
        + "view: v {\n"
        + "  dimension: pct {\n"
        + "    value_format: \"0.00\\%\"\n"
        + "    label: \"say \\\"hi\\\" now\"\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode dim = LookmlParser.parse(lookml).children("view").get(0)
        .children("dimension").get(0);

    // Assert: backslash-escapes are kept verbatim (we don't unescape).
    assertEquals("0.00\\%", dim.stringValue("value_format").orElseThrow());
    assertEquals("say \\\"hi\\\" now", dim.stringValue("label").orElseThrow());
  }

  /** FIX 5: a {@code ref: asc|desc} bare-identifier pair in a list. Repro: 5
   * files (e.g. {@code sorts: [orders.first_seen_month: desc]}). */
  @Test void parsesSortsWithBareIdentifierPair() {
    // Arrange
    final String lookml = ""
        + "explore: e {\n"
        + "  sorts: [orders.first_seen_month: desc, orders.id: asc]\n"
        + "}\n";

    // Act
    final LookmlNode explore = LookmlParser.parse(lookml).children("explore")
        .get(0);

    // Assert
    final List<ValueImpl> items =
        ((Values.ListValue) explore.value("sorts").orElseThrow()).list;
    assertEquals(2, items.size());
    final Values.PairValue p0 = (Values.PairValue) items.get(0);
    assertEquals("orders.first_seen_month", p0.ref);
    assertEquals("desc", p0.s);
    final Values.PairValue p1 = (Values.PairValue) items.get(1);
    assertEquals("orders.id", p1.ref);
    assertEquals("asc", p1.s);
  }

  /** FIX 6: a digit-leading object name in name position. Repro: 4 files
   * (e.g. {@code measure: 1st_yr_bill {}}). Number literals as values must NOT
   * regress. */
  @Test void parsesDigitLeadingObjectNames() {
    // Arrange
    final String lookml = ""
        + "view: v {\n"
        + "  measure: 1st_yr_bill {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.amount ;;\n"
        + "  }\n"
        + "  dimension: 2nd_thing {\n"
        + "    type: number\n"
        + "    value_format: \"0\"\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode view = LookmlParser.parse(lookml).children("view").get(0);

    // Assert: digit-leading names parse.
    assertEquals("1st_yr_bill",
        view.children("measure").get(0).name().orElseThrow());
    assertEquals("2nd_thing",
        view.children("dimension").get(0).name().orElseThrow());
  }

  /** FIX 6 in list-pair position: a digit-leading ref starting a list pair,
   * e.g. {@code filters: [21days_since_release: "yes"]}. */
  @Test void parsesDigitLeadingRefInListPair() {
    // Arrange
    final String lookml = ""
        + "explore: e {\n"
        + "  always_filter: {\n"
        + "    filters: [21days_since_release: \"yes\"]\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode filter = LookmlParser.parse(lookml).children("explore")
        .get(0).child("always_filter").orElseThrow();

    // Assert
    final List<ValueImpl> items =
        ((Values.ListValue) filter.value("filters").orElseThrow()).list;
    assertEquals(1, items.size());
    final Values.PairValue p = (Values.PairValue) items.get(0);
    assertEquals("21days_since_release", p.ref);
    assertEquals("yes", p.s);
  }

  /** FIX 6 guard: numeric literal values must still parse as numbers, not get
   * swallowed by the digit-leading NAME rule. */
  @Test void numberLiteralsStillParseAsNumbers() {
    // Arrange
    final String lookml = ""
        + "view: v {\n"
        + "  dimension: tier {\n"
        + "    tiers: [0, 10, 100]\n"
        + "  }\n"
        + "  measure: m {\n"
        + "    type: number\n"
        + "    precision: 2\n"
        + "    value: -3.5\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode view = LookmlParser.parse(lookml).children("view").get(0);

    // Assert: list of numbers stays numeric.
    final List<ValueImpl> tiers = ((Values.ListValue)
        view.children("dimension").get(0).value("tiers").orElseThrow()).list;
    assertEquals(3, tiers.size());
    assertTrue(tiers.get(0) instanceof Values.NumberValue,
        "0 must be a NumberValue, not a NAME");
    assertEquals(new java.math.BigDecimal("100"),
        ((Values.NumberValue) tiers.get(2)).toBigDecimal());

    // Assert: scalar number properties stay numeric.
    final LookmlNode m = view.children("measure").get(0);
    final Value precision = m.value("precision").orElseThrow();
    assertTrue(precision instanceof Values.NumberValue, "precision is a number");
    final Value val = m.value("value").orElseThrow();
    assertTrue(val instanceof Values.NumberValue, "negative number stays number");
    assertEquals(new java.math.BigDecimal("-3.5"),
        ((Values.NumberValue) val).toBigDecimal());
  }

  /** FIX 7 / finding #4 root cause: {@code default_value} is a STRING-valued
   * key in LookML (parameters/filters), never a {@code ;;}-terminated code
   * block. Treating it as code made the lexer run past the unterminated quoted
   * string and swallow following properties up to the next {@code ;;}, deep in
   * the file — surfacing later as a spurious {@code <EOF>}. Repro pattern from
   * 16 multi-block view files (mozilla experimentation/*, duet/*). */
  @Test void defaultValueIsStringNotCodeAndDoesNotSwallowToEof() {
    // Arrange: a parameter with a quoted default_value, then a real sql code
    // block, then more properties — exactly the shape that used to fail.
    final String lookml = ""
        + "view: preview {\n"
        + "  parameter: project {\n"
        + "    type: unquoted\n"
        + "    default_value: \"mozdata\"\n"
        + "  }\n"
        + "  sql_table_name: {% parameter project %}.t ;;\n"
        + "  dimension: a {\n"
        + "    type: number\n"
        + "    sql: ${TABLE}.a ;;\n"
        + "  }\n"
        + "  measure: lower {\n"
        + "    type: sum\n"
        + "    sql: ${TABLE}.lower ;;\n"
        + "  }\n"
        + "}\n";

    // Act
    final LookmlNode view = LookmlParser.parse(lookml).children("view").get(0);

    // Assert: default_value survives as a plain string, and every following
    // property is still reachable (nothing was swallowed).
    final LookmlNode param = view.children("parameter").get(0);
    assertEquals("mozdata", param.stringValue("default_value").orElseThrow());
    assertTrue(view.value("sql_table_name").orElseThrow()
        instanceof Values.CodeValue);
    assertEquals(1, view.children("dimension").size());
    assertEquals(1, view.children("measure").size());
    assertEquals("lower", view.children("measure").get(0).name().orElseThrow());
  }

  /** FIX 8: a leading '-' field-exclusion ref in a list, e.g.
   * {@code fields: [ALL_FIELDS*, -account.id]}. Negative number literals must
   * NOT regress. */
  @Test void parsesFieldExclusionWithLeadingDash() {
    // Arrange
    final String lookml = ""
        + "explore: e {\n"
        + "  fields: [ALL_FIELDS*, -tables.id, -account.region]\n"
        + "}\n";

    // Act
    final List<ValueImpl> items = ((Values.ListValue)
        LookmlParser.parse(lookml).children("explore").get(0)
            .value("fields").orElseThrow()).list;

    // Assert
    assertEquals(3, items.size());
    assertEquals("ALL_FIELDS*", ((Values.IdentifierValue) items.get(0)).id);
    assertEquals("-tables.id", ((Values.IdentifierValue) items.get(1)).id);
    assertEquals("-account.region", ((Values.IdentifierValue) items.get(2)).id);
  }

  /** FIX 8 guard: a list of negative number literals stays numeric. */
  @Test void negativeNumberLiteralsStillParseAsNumbers() {
    // Arrange
    final String lookml = ""
        + "view: v {\n"
        + "  dimension: t { tiers: [-10, 0, 10] }\n"
        + "}\n";

    // Act
    final List<ValueImpl> items = ((Values.ListValue)
        LookmlParser.parse(lookml).children("view").get(0)
            .children("dimension").get(0).value("tiers").orElseThrow()).list;

    // Assert
    assertEquals(3, items.size());
    assertTrue(items.get(0) instanceof Values.NumberValue,
        "-10 must stay a NumberValue");
    assertEquals(new java.math.BigDecimal("-10"),
        ((Values.NumberValue) items.get(0)).toBigDecimal());
  }

  /** An empty document is valid and yields no top-level objects. */
  @Test void parsesEmptyDocument() {
    // Act
    final LookmlNode doc = LookmlParser.parse("");

    // Assert
    assertTrue(doc.children("view").isEmpty());
    assertFalse(doc.name().isPresent());
  }
}

// End LookmlParserTest.java
