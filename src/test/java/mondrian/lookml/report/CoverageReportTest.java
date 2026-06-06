/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.lookml.report;

import mondrian.lookml.model.Classification;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParser;
import mondrian.lookml.transpile.LookmlTranspiler;
import mondrian.lookml.transpile.TranspileResult;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #102: {@link CoverageReport} joins the classifier's records with the
 * transpiler's provenance so every CLEAN/DEGRADE row carries the M4 element it
 * produced and every REFUSE row carries its precise reason (and no M4).
 */
public class CoverageReportTest {

  /** One CLEAN explore + measures/dimension, one DEGRADE (derived_table with a
   * persistence policy), and one REFUSE (a sum fanned out one_to_many). */
  private static final String LOOKML =
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
      + "view: daily {\n"
      + "  derived_table: {\n"
      + "    sql: SELECT 1 ;;\n"
      + "    persist_for: \"24 hours\"\n"
      + "  }\n"
      + "  dimension: d { type: string sql: ${TABLE}.d ;; }\n"
      + "}\n"
      + "explore: orders {\n"
      + "  join: items { type: left_outer relationship: one_to_many\n"
      + "    sql_on: ${orders.order_id} = ${items.user_id} ;; }\n"
      + "}\n";

  private static CoverageReport report(String lookml) {
    LookmlNode doc = LookmlParser.parse(lookml);
    TranspileResult tr = new LookmlTranspiler().transpile(doc);
    return CoverageReport.from(tr);
  }

  private static ReportRow find(CoverageReport r, String qn) {
    return r.rows().stream()
        .filter(row -> row.record().qualifiedName().equals(qn))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + qn
            + " in " + r.rows()));
  }

  @Test
  public void cleanRowCarriesProducedM4FromProvenance() {
    CoverageReport r = report(LOOKML);
    ReportRow row = find(r, "explore:orders");
    assertEquals(Classification.CLEAN, row.record().classification());
    assertTrue("clean explore must carry produced M4: " + row,
        row.producedM4().isPresent());
  }

  @Test
  public void cleanMeasureCarriesProducedM4() {
    CoverageReport r = report(LOOKML);
    ReportRow row = find(r, "orders.top_amount");
    assertEquals(Classification.CLEAN, row.record().classification());
    assertTrue("clean measure must carry produced M4: " + row,
        row.producedM4().isPresent());
    assertTrue(row.producedM4().get().contains("top_amount"));
  }

  @Test
  public void degradeRowCarriesProducedM4AndLostCapability() {
    CoverageReport r = report(LOOKML);
    ReportRow row = find(r, "view:daily");
    assertEquals(Classification.DEGRADE, row.record().classification());
    assertTrue("degrade row must name produced M4: " + row,
        row.producedM4().isPresent());
    assertTrue("degrade row must name lost capability: " + row,
        row.record().lostCapability().isPresent());
  }

  @Test
  public void refuseRowCarriesReasonAndIssueButNoM4() {
    CoverageReport r = report(LOOKML);
    ReportRow row = find(r, "orders.fanned_sum");
    assertEquals(Classification.REFUSE, row.record().classification());
    assertFalse("refused row must NOT carry an M4 element: " + row,
        row.producedM4().isPresent());
    assertFalse("refusal reason must be present",
        row.record().reason().isBlank());
    assertEquals(Optional.of("#103"), row.record().relatedIssue());
  }

  @Test
  public void metricsMatchRows() {
    CoverageReport r = report(LOOKML);
    List<ReportRow> refused = r.rows(Classification.REFUSE);
    assertEquals(r.metrics().field().refuse() + r.metrics().explore().refuse(),
        refused.size());
  }

  @Test
  public void rowsFilteredByClassification() {
    CoverageReport r = report(LOOKML);
    assertTrue(r.rows(Classification.CLEAN).stream()
        .allMatch(row -> row.record().classification() == Classification.CLEAN));
    assertTrue(r.rows(Classification.DEGRADE).stream()
        .allMatch(row ->
            row.record().classification() == Classification.DEGRADE));
  }
}

// End CoverageReportTest.java
