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

import mondrian.lookml.model.CoverageRecord;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Issue #102: serialises a {@link CoverageReport} to machine-readable,
 * pretty-printed JSON with a stable key order — the artefact CI gates and
 * dashboards consume to track the shrinking refuse list.
 *
 * <p>The JSON shape is built from explicit {@link LinkedHashMap}s rather than
 * reflecting over the domain types, so the schema is stable and decoupled from
 * the internal model. The same input always produces byte-for-byte identical
 * output, and the result re-parses to an equivalent tree.
 *
 * <pre>
 * {
 *   "metrics": {
 *     "explore": { "total", "clean", "degrade", "refuse",
 *                  "cleanPct", "degradePct", "refusePct" },
 *     "field":   { ... }
 *   },
 *   "records": [
 *     { "scope", "qualifiedName", "classification", "reasonCode",
 *       "reason", "producedM4", "lostCapability", "relatedIssue" }, ...
 *   ]
 * }
 * </pre>
 */
public final class JsonReportWriter {

  private static final ObjectWriter WRITER = stableWriter();

  /** Renders the report to a pretty-printed JSON string (no diagnostics). */
  public String write(CoverageReport report) {
    return write(report, IngestDiagnostics.none());
  }

  /** Renders the report to a pretty-printed JSON string, including any per-file
   * ingest diagnostics (unparseable / skipped files). */
  public String write(CoverageReport report, IngestDiagnostics diagnostics) {
    requireNonNull(report, "report");
    requireNonNull(diagnostics, "diagnostics");
    try {
      return WRITER.writeValueAsString(toTree(report, diagnostics));
    } catch (Exception e) {
      throw new IllegalStateException(
          "failed to serialise coverage report to JSON", e);
    }
  }

  private static Map<String, Object> toTree(CoverageReport report,
      IngestDiagnostics diagnostics) {
    final Map<String, Object> root = new LinkedHashMap<>();
    root.put("metrics", metricsTree(report.metrics()));
    root.put("records", recordsTree(report));
    root.put("unparseableFiles", entriesTree(diagnostics.unparseable()));
    root.put("skippedFiles", entriesTree(diagnostics.skipped()));
    return root;
  }

  private static List<Map<String, Object>> entriesTree(
      List<IngestDiagnostics.Entry> entries) {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (IngestDiagnostics.Entry e : entries) {
      final Map<String, Object> tree = new LinkedHashMap<>();
      tree.put("file", e.file());
      tree.put("reason", e.reason());
      out.add(tree);
    }
    return out;
  }

  private static Map<String, Object> metricsTree(SummaryMetrics m) {
    final Map<String, Object> tree = new LinkedHashMap<>();
    tree.put("explore", bucketTree(m.explore()));
    tree.put("field", bucketTree(m.field()));
    return tree;
  }

  private static Map<String, Object> bucketTree(SummaryMetrics.Bucket b) {
    final Map<String, Object> tree = new LinkedHashMap<>();
    tree.put("total", b.total());
    tree.put("clean", b.clean());
    tree.put("degrade", b.degrade());
    tree.put("refuse", b.refuse());
    tree.put("cleanPct", b.cleanPct());
    tree.put("degradePct", b.degradePct());
    tree.put("refusePct", b.refusePct());
    return tree;
  }

  private static List<Map<String, Object>> recordsTree(CoverageReport report) {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (ReportRow row : report.rows()) {
      out.add(recordTree(row));
    }
    return out;
  }

  private static Map<String, Object> recordTree(ReportRow row) {
    final CoverageRecord r = row.record();
    final Map<String, Object> tree = new LinkedHashMap<>();
    tree.put("scope", r.scope().name());
    tree.put("qualifiedName", r.qualifiedName());
    tree.put("classification", r.classification().name());
    tree.put("reasonCode", r.reasonCode().name());
    tree.put("reason", r.reason());
    tree.put("producedM4", row.producedM4().orElse(null));
    tree.put("lostCapability", r.lostCapability().orElse(null));
    tree.put("relatedIssue", r.relatedIssue().orElse(null));
    return tree;
  }

  private static ObjectWriter stableWriter() {
    final ObjectMapper mapper = new ObjectMapper();
    // LF indentation so output is identical across platforms.
    final DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
    final DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    pp.indentObjectsWith(indenter);
    pp.indentArraysWith(indenter);
    return mapper.writer(pp);
  }
}

// End JsonReportWriter.java
