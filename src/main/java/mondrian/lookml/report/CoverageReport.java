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
import mondrian.lookml.transpile.ProvenanceMap;
import mondrian.lookml.transpile.TranspileResult;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Issue #102: the LookML import coverage report — the importer's primary
 * deliverable. Immutable.
 *
 * <p>Built from a {@link TranspileResult} by joining each
 * {@link CoverageRecord} (the classifier's per-construct CLEAN/DEGRADE/REFUSE
 * verdict) with the {@link ProvenanceMap} entry the transpiler emitted for it
 * (matched on {@code qualifiedName}); the join produces {@link ReportRow}s and
 * the per-project {@link SummaryMetrics}. CLEAN/DEGRADE rows carry their
 * produced M4 element; REFUSE rows carry the precise structural reason and no
 * M4.
 */
public final class CoverageReport {

  private final ImmutableList<ReportRow> rows;
  private final SummaryMetrics metrics;

  private CoverageReport(ImmutableList<ReportRow> rows,
      SummaryMetrics metrics) {
    this.rows = rows;
    this.metrics = metrics;
  }

  /** Builds the report from a transpile result, joining classification records
   * with provenance on the qualified name. */
  public static CoverageReport from(TranspileResult result) {
    requireNonNull(result, "result");
    final List<CoverageRecord> records = result.classification().records();
    final ProvenanceMap provenance = result.provenance();

    final ImmutableList.Builder<ReportRow> b = ImmutableList.builder();
    for (CoverageRecord record : records) {
      b.add(new ReportRow(record, resolveM4(record, provenance)));
    }
    return new CoverageReport(b.build(), SummaryMetrics.from(records));
  }

  /**
   * Resolves the M4 element for a record: the transpiler's provenance path
   * (precise locator) takes precedence; the record's own {@code producedM4}
   * description is the fallback (DEGRADE records have no provenance path).
   * REFUSE records have neither and resolve to {@code null}.
   */
  private static String resolveM4(CoverageRecord record,
      ProvenanceMap provenance) {
    return provenance.m4Path(record.qualifiedName())
        .or(record::producedM4)
        .orElse(null);
  }

  /** All rows, in classification order. */
  public ImmutableList<ReportRow> rows() {
    return rows;
  }

  /** Rows with the given classification, in order. */
  public ImmutableList<ReportRow> rows(Classification classification) {
    requireNonNull(classification, "classification");
    final ImmutableList.Builder<ReportRow> b = ImmutableList.builder();
    for (ReportRow row : rows) {
      if (row.record().classification() == classification) {
        b.add(row);
      }
    }
    return b.build();
  }

  /** The per-project summary metrics (the headline coverage ratio). */
  public SummaryMetrics metrics() {
    return metrics;
  }

  @Override public String toString() {
    return "CoverageReport{rows=" + rows.size() + ", metrics=" + metrics + "}";
  }
}

// End CoverageReport.java
