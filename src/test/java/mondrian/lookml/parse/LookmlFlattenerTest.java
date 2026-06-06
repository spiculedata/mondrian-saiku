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

import mondrian.lookml.classify.LookmlClassifier;
import mondrian.lookml.model.Classification;
import mondrian.lookml.model.ClassificationResult;
import mondrian.lookml.model.CoverageRecord;
import mondrian.lookml.model.ReasonCode;
import mondrian.lookml.transpile.LookmlTranspiler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD spec for the multi-file LookML flatten pass (issue #116, part A).
 *
 * <p>Each test concatenates two "files" (mirroring what the CLI does after
 * recursively discovering {@code .lkml} files) and asserts the flattened
 * document resolves {@code include}/{@code extends}/refinements/{@code @{}} to
 * the expected single model — and, crucially, that a construct mis-classified
 * on its literal text now classifies correctly after flattening.
 */
class LookmlFlattenerTest {

  private static LookmlNode flatten(String lookml) {
    return new LookmlFlattener().flatten(LookmlParser.parse(lookml)).document();
  }

  private static FlattenResult flattenResult(String lookml) {
    return new LookmlFlattener().flatten(LookmlParser.parse(lookml));
  }

  private static CoverageRecord record(LookmlNode doc, String name) {
    final ClassificationResult r = new LookmlClassifier().classify(doc);
    final Optional<CoverageRecord> found = r.records().stream()
        .filter(rec -> rec.qualifiedName().equals(name))
        .findFirst();
    assertTrue(found.isPresent(),
        () -> "no record for " + name + " in " + r.records());
    return found.get();
  }

  // --- the headline 2-file fixture: include + +view + @{} ----------------

  /**
   * The DoD fixture: file 1 declares a constant and a base view+explore; file 2
   * {@code include}s file 1 and refines the view (+orders) to set the measure's
   * {@code type: sum}. As-parsed the base measure has no type → CLEAN on a
   * fan-out view (the pinned v1 limitation). After flattening the refinement's
   * {@code type: sum} lands on the measure, so it is now correctly REFUSED as a
   * symmetric-aggregate fan-out.
   */
  @Test void includeRefinementAndConstantResolveToSingleModel() {
    final String file1 = ""
        + "constant: amount_col { value: \"order_amount\" }\n"
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders {\n"
        + "  measure: revenue { sql: ${TABLE}.@{amount_col} ;; }\n"
        + "}\n"
        + "view: items { dimension: order_id { type: number } }\n";
    final String file2 = ""
        + "include: \"orders.view.lkml\"\n"
        + "view: +orders {\n"
        + "  measure: revenue { type: sum }\n"
        + "}\n";

    final LookmlNode doc = flatten(file1 + file2);

    // include: is dropped; exactly one resolved orders view (refinement merged).
    final List<LookmlNode> orders = doc.children("view").stream()
        .filter(v -> "orders".equals(v.name().orElse("")))
        .toList();
    assertEquals(1, orders.size(), "refinement merged into one base view");
    assertTrue(doc.children("include").isEmpty(), "include: dropped");

    // The refinement's type:sum landed on the existing revenue measure.
    final LookmlNode revenue = orders.get(0).children("measure").stream()
        .filter(m -> "revenue".equals(m.name().orElse(""))).findFirst()
        .orElseThrow();
    assertEquals("sum", revenue.stringValue("type").orElseThrow());
    // @{amount_col} substituted into the measure's sql.
    assertEquals("${TABLE}.order_amount", revenue.stringValue("sql")
        .orElseThrow().trim());

    // Classification now flips: additive sum on a fanned-out view → REFUSE.
    final CoverageRecord rec = record(doc, "orders.revenue");
    assertEquals(Classification.REFUSE, rec.classification());
    assertEquals(ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE,
        rec.reasonCode());
  }

  /** Without the flatten pass the same model classifies the measure CLEAN
   * (the documented as-parsed limitation), proving the flatten pass is what
   * fixes the mis-classification. */
  @Test void asParsedTheSameMeasureIsMisclassifiedClean() {
    final String lookml = ""
        + "explore: orders {\n"
        + "  join: items {\n"
        + "    type: left_outer\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;;\n"
        + "    relationship: one_to_many\n"
        + "  }\n"
        + "}\n"
        + "view: orders { measure: revenue { sql: ${TABLE}.amount ;; } }\n"
        + "view: +orders { measure: revenue { type: sum } }\n"
        + "view: items { dimension: order_id { type: number } }\n";

    // As-parsed: two separate view:orders objects; the typeless base revenue
    // (no type → not an additive aggregate) classifies CLEAN — the documented
    // mis-classification this pass fixes.
    final ClassificationResult asParsed =
        new LookmlClassifier().classify(LookmlParser.parse(lookml));
    assertTrue(asParsed.records().stream()
            .anyMatch(r -> r.qualifiedName().equals("orders.revenue")
                && r.classification() == Classification.CLEAN),
        "as-parsed the typeless base revenue is mis-classified CLEAN");

    // Flattened: the refinement's type:sum lands → REFUSE (fan-out symmetric).
    assertEquals(Classification.REFUSE,
        record(flatten(lookml), "orders.revenue").classification());
  }

  // --- extends -----------------------------------------------------------

  /** {@code extends: [base]} copies the base then the extender overrides. */
  @Test void extendsCopiesBaseThenOverrides() {
    final String lookml = ""
        + "view: base_dims {\n"
        + "  dimension: id { type: number }\n"
        + "  dimension: name { type: string }\n"
        + "}\n"
        + "view: orders {\n"
        + "  extends: [base_dims]\n"
        + "  dimension: name { type: string label: \"Order Name\" }\n"
        + "  measure: c { type: count }\n"
        + "}\n";

    final LookmlNode orders = flatten(lookml).children("view").stream()
        .filter(v -> "orders".equals(v.name().orElse(""))).findFirst()
        .orElseThrow();
    // extends: key is dropped from the resolved object.
    assertTrue(orders.value("extends").isEmpty(), "extends: dropped");
    // id comes from the base; name overridden with the label.
    final LookmlNode id = orders.children("dimension").stream()
        .filter(d -> "id".equals(d.name().orElse(""))).findFirst()
        .orElseThrow();
    assertEquals("number", id.stringValue("type").orElseThrow());
    final LookmlNode name = orders.children("dimension").stream()
        .filter(d -> "name".equals(d.name().orElse(""))).findFirst()
        .orElseThrow();
    assertEquals("Order Name", name.stringValue("label").orElseThrow());
    // own measure preserved.
    assertFalse(orders.children("measure").isEmpty());
  }

  // --- diagnostics -------------------------------------------------------

  /** A refinement whose base is missing is recorded (DANGLING_REFINEMENT) and
   * preserved, never silently dropped. */
  @Test void danglingRefinementIsRecordedNotDropped() {
    final String lookml = ""
        + "view: +ghost { measure: m { type: sum } }\n";
    final FlattenResult res = flattenResult(lookml);
    assertEquals(1, res.diagnostics().size());
    assertEquals(FlattenDiagnostic.Kind.DANGLING_REFINEMENT,
        res.diagnostics().get(0).kind());
    // preserved as-parsed (its +name name).
    assertTrue(res.document().children("view").stream()
        .anyMatch(v -> "+ghost".equals(v.name().orElse(""))));
  }

  /** An undeclared {@code @{}} constant is recorded and left as-parsed. */
  @Test void undeclaredConstantIsRecordedAndLeftAsParsed() {
    final String lookml = ""
        + "view: orders { measure: m { sql: ${TABLE}.@{missing} ;; } }\n";
    final FlattenResult res = flattenResult(lookml);
    assertEquals(1, res.diagnostics().size());
    assertEquals(FlattenDiagnostic.Kind.UNDECLARED_CONSTANT,
        res.diagnostics().get(0).kind());
    final LookmlNode m = res.document().children("view").get(0)
        .children("measure").get(0);
    assertEquals("${TABLE}.@{missing}",
        m.stringValue("sql").orElseThrow().trim());
  }

  // --- RLS: cross-file access_filter must survive flattening (mandatory) --

  /**
   * Security-relevant construct across files: a refinement ({@code +explore})
   * adds an {@code access_filter}. After flattening, the generated Role /
   * PredicateGrant must be byte-for-byte the same M4 schema as the single-file
   * equivalent — a refinement must never silently drop or widen an
   * access_filter (issue #98 RLS gate). This guards that the new resolution
   * logic does not reopen an RLS gap.
   */
  @Test void crossFileAccessFilterResolvesToSameGrantAsSingleFile() {
    // File 2 refines the explore to add the access_filter cross-file.
    final String crossFile = ""
        + "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: orders { }\n"
        + "explore: +orders {\n"
        + "  access_filter: { field: orders.tenant_id"
        + "    user_attribute: tenant_id }\n"
        + "}\n";
    // The single-file equivalent: the access_filter declared inline.
    final String singleFile = ""
        + "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: amount { type: sum sql: ${TABLE}.amount ;; }\n"
        + "}\n"
        + "explore: orders {\n"
        + "  access_filter: { field: orders.tenant_id"
        + "    user_attribute: tenant_id }\n"
        + "}\n";

    final String flattenedYaml = new LookmlTranspiler()
        .transpile(flatten(crossFile)).yaml();
    final String singleYaml = new LookmlTranspiler()
        .transpile(LookmlParser.parse(singleFile)).yaml();

    // The grant survived the cross-file resolution and is identical.
    assertTrue(flattenedYaml.contains("predicate_grants:"), flattenedYaml);
    assertTrue(flattenedYaml.contains("column: \"tenant_id\""), flattenedYaml);
    assertEquals(singleYaml, flattenedYaml,
        "cross-file access_filter must transpile identically to single-file");
  }

  /** A model with no cross-file constructs round-trips unchanged. */
  @Test void plainModelIsUnchanged() {
    final String lookml = ""
        + "view: orders { measure: c { type: count } }\n"
        + "explore: orders { }\n";
    final FlattenResult res = flattenResult(lookml);
    assertTrue(res.diagnostics().isEmpty());
    assertEquals(1, res.document().children("view").size());
    assertEquals(1, res.document().children("explore").size());
  }
}

// End LookmlFlattenerTest.java
