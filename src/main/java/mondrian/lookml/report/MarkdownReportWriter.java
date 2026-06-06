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
import mondrian.lookml.model.CoverageRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Issue #102: renders a {@link CoverageReport} as human-readable Markdown — the
 * de-risking artefact a migrating customer reads before trusting the
 * conversion.
 *
 * <p>Layout: a title, a per-granularity summary table (clean / degrade / refuse
 * counts and percentages at explore and field granularity), then three
 * sections — Clean, Degrade, Refuse — each grouped explore&rarr;field. Every
 * row shows the qualified name, the precise reason, the produced M4 element
 * (CLEAN / DEGRADE) or lost capability (DEGRADE), and the companion-epic issue
 * link that would improve the outcome.
 *
 * <p>Stateless and immutable; {@link #write(CoverageReport)} is a pure
 * function.
 */
public final class MarkdownReportWriter {

  private static final String TITLE = "# LookML Import Coverage Report";
  private static final String NL = "\n";
  private static final String NONE = "—";

  /** Renders the report to a Markdown string. */
  public String write(CoverageReport report) {
    requireNonNull(report, "report");
    final StringBuilder sb = new StringBuilder();
    sb.append(TITLE).append(NL).append(NL);
    appendSummary(sb, report);
    appendSection(sb, "Clean", report.rows(Classification.CLEAN));
    appendSection(sb, "Degrade", report.rows(Classification.DEGRADE));
    appendSection(sb, "Refuse", report.rows(Classification.REFUSE));
    return sb.toString();
  }

  private void appendSummary(StringBuilder sb, CoverageReport report) {
    final SummaryMetrics m = report.metrics();
    sb.append("## Summary").append(NL).append(NL);
    sb.append("| Granularity | Total | Clean | Degrade | Refuse |").append(NL);
    sb.append("| --- | ---: | ---: | ---: | ---: |").append(NL);
    appendSummaryRow(sb, "Explore", m.explore());
    appendSummaryRow(sb, "Field", m.field());
    sb.append(NL);
  }

  private void appendSummaryRow(StringBuilder sb, String label,
      SummaryMetrics.Bucket b) {
    sb.append("| ").append(label)
        .append(" | ").append(b.total())
        .append(" | ").append(cell(b.clean(), b.cleanPct()))
        .append(" | ").append(cell(b.degrade(), b.degradePct()))
        .append(" | ").append(cell(b.refuse(), b.refusePct()))
        .append(" |").append(NL);
  }

  private static String cell(long count, double pct) {
    return count + " (" + pct + "%)";
  }

  private void appendSection(StringBuilder sb, String name,
      List<ReportRow> rows) {
    sb.append("## ").append(name).append(NL).append(NL);
    if (rows.isEmpty()) {
      sb.append("_None._").append(NL).append(NL);
      return;
    }
    for (Map.Entry<String, List<ReportRow>> group : byOwner(rows).entrySet()) {
      sb.append("### ").append(group.getKey()).append(NL).append(NL);
      for (ReportRow row : group.getValue()) {
        appendRow(sb, row);
      }
      sb.append(NL);
    }
  }

  private void appendRow(StringBuilder sb, ReportRow row) {
    final CoverageRecord r = row.record();
    sb.append("- **").append(r.qualifiedName()).append("** — ")
        .append(r.reason());
    row.producedM4().ifPresent(m4 ->
        sb.append(NL).append("  - produced M4: `").append(m4).append('`'));
    r.lostCapability().ifPresent(lost ->
        sb.append(NL).append("  - lost capability: ").append(lost));
    sb.append(NL).append("  - related issue: ")
        .append(r.relatedIssue().map(MarkdownReportWriter::issueLink)
            .orElse(NONE));
    sb.append(NL);
  }

  /** Renders an issue ref ("#103") as the arrow-prefixed link the report uses
   * ("→ #103"). Bare "#" anchors render as plain text in Markdown, which is
   * the intended, copy-pasteable behaviour. */
  private static String issueLink(String issue) {
    return "→ " + issue;
  }

  /** Groups rows by their explore/view owner, preserving first-seen order. */
  private static Map<String, List<ReportRow>> byOwner(List<ReportRow> rows) {
    final Map<String, List<ReportRow>> byOwner = new LinkedHashMap<>();
    for (ReportRow row : rows) {
      final String owner = ReportGrouping.owner(row.record().qualifiedName());
      byOwner.computeIfAbsent(owner, k -> new java.util.ArrayList<>()).add(row);
    }
    return byOwner;
  }
}

// End MarkdownReportWriter.java
