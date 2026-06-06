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

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Issue #102: the Markdown coverage report renders a summary table per
 * granularity, then Clean / Degrade / Refuse sections. The refuse section is
 * the de-risking centerpiece: its reasons must be prominent and precise and
 * carry the companion-epic issue link.
 */
public class MarkdownReportWriterTest {

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

  private static String render() {
    LookmlNode doc = LookmlParser.parse(LOOKML);
    TranspileResult tr = new LookmlTranspiler().transpile(doc);
    return new MarkdownReportWriter().write(CoverageReport.from(tr));
  }

  @Test
  public void hasTitleAndSummaryTable() {
    String md = render();
    assertTrue(md, md.contains("# LookML Import Coverage Report"));
    assertTrue(md, md.contains("## Summary"));
    // A markdown table header with the three buckets.
    assertTrue(md, md.contains("| Clean |") || md.contains("Clean"));
    assertTrue(md, md.contains("Explore"));
    assertTrue(md, md.contains("Field"));
    assertTrue("summary must show percentages: " + md, md.contains("%"));
  }

  @Test
  public void hasThreeSectionHeaders() {
    String md = render();
    assertTrue(md, md.contains("## Clean"));
    assertTrue(md, md.contains("## Degrade"));
    assertTrue(md, md.contains("## Refuse"));
  }

  @Test
  public void refuseSectionShowsPreciseReasonAndIssueLink() {
    String md = render();
    int refuseAt = md.indexOf("## Refuse");
    assertTrue("refuse section present", refuseAt >= 0);
    String refuseSection = md.substring(refuseAt);
    assertTrue("refused measure named: " + refuseSection,
        refuseSection.contains("fanned_sum"));
    assertTrue("refusal reason mentions fan-out / symmetric: " + refuseSection,
        refuseSection.toLowerCase().contains("fan")
            || refuseSection.toLowerCase().contains("symmetric"));
    assertTrue("refusal carries issue link → #103: " + refuseSection,
        refuseSection.contains("#103"));
  }

  @Test
  public void cleanSectionShowsProducedM4() {
    String md = render();
    int cleanAt = md.indexOf("## Clean");
    int degradeAt = md.indexOf("## Degrade");
    String cleanSection = md.substring(cleanAt, degradeAt);
    assertTrue("clean row names the produced M4: " + cleanSection,
        cleanSection.contains("top_amount"));
  }

  @Test
  public void degradeSectionShowsLostCapability() {
    String md = render();
    int degradeAt = md.indexOf("## Degrade");
    int refuseAt = md.indexOf("## Refuse");
    String degradeSection = md.substring(degradeAt, refuseAt);
    assertTrue("degrade row names lost capability: " + degradeSection,
        degradeSection.toLowerCase().contains("persist")
            || degradeSection.toLowerCase().contains("pdt"));
  }
}

// End MarkdownReportWriterTest.java
