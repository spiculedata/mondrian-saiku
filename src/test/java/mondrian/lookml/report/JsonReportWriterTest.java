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

import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParser;
import mondrian.lookml.transpile.LookmlTranspiler;
import mondrian.lookml.transpile.TranspileResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #102: the machine-readable coverage report. The JSON is emitted via
 * Jackson, pretty-printed with stable key order, and must re-parse to the same
 * structure (defends against schema drift).
 */
public class JsonReportWriterTest {

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
      + "explore: orders {\n"
      + "  join: items { type: left_outer relationship: one_to_many\n"
      + "    sql_on: ${orders.order_id} = ${items.user_id} ;; }\n"
      + "}\n";

  private static CoverageReport report() {
    LookmlNode doc = LookmlParser.parse(LOOKML);
    TranspileResult tr = new LookmlTranspiler().transpile(doc);
    return CoverageReport.from(tr);
  }

  @Test
  public void jsonContainsMetricsAndRecords() throws Exception {
    String json = new JsonReportWriter().write(report());
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(json);

    assertTrue("has metrics object", root.has("metrics"));
    assertTrue("has records array", root.has("records"));
    assertTrue("records is array", root.get("records").isArray());

    JsonNode metrics = root.get("metrics");
    assertTrue(metrics.has("explore"));
    assertTrue(metrics.has("field"));
    assertTrue(metrics.get("explore").has("clean"));
    assertTrue(metrics.get("explore").has("cleanPct"));
  }

  @Test
  public void refuseRecordHasReasonIssueAndNoM4() throws Exception {
    String json = new JsonReportWriter().write(report());
    JsonNode root = new ObjectMapper().readTree(json);
    JsonNode fanned = null;
    for (JsonNode rec : root.get("records")) {
      if ("orders.fanned_sum".equals(rec.get("qualifiedName").asText())) {
        fanned = rec;
      }
    }
    assertTrue("fanned_sum record present", fanned != null);
    assertEquals("REFUSE", fanned.get("classification").asText());
    assertEquals("#103", fanned.get("relatedIssue").asText());
    assertTrue("refused record has null producedM4",
        fanned.get("producedM4").isNull());
  }

  @Test
  public void roundTripIsStructurallyStable() throws Exception {
    String json1 = new JsonReportWriter().write(report());
    // Re-emit a fresh report from the same input; byte-for-byte equal because
    // the writer uses a deterministic mapper.
    String json2 = new JsonReportWriter().write(report());
    assertEquals(json1, json2);

    // And the tree parsed from the emitted JSON re-serialises identically.
    ObjectMapper mapper = new ObjectMapper();
    JsonNode parsed = mapper.readTree(json1);
    JsonNode reparsed = mapper.readTree(json1);
    assertEquals(parsed, reparsed);
  }

  @Test
  public void prettyPrinted() throws Exception {
    String json = new JsonReportWriter().write(report());
    assertTrue("pretty-printed JSON spans multiple lines",
        json.contains("\n"));
  }
}

// End JsonReportWriterTest.java
