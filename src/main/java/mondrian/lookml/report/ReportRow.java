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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Issue #102: one coverage record joined with the M4 element it produced.
 *
 * <p>Immutable. The {@code producedM4} is resolved once at construction time by
 * preferring the transpiler's {@link mondrian.lookml.transpile.ProvenanceMap}
 * entry (a precise M4 element path) and falling back to the record's own
 * {@link CoverageRecord#producedM4()} description (used by DEGRADE records such
 * as a derived_table emitted as a {@code <View>} that have no provenance path).
 * REFUSE rows resolve to empty: nothing was emitted for them.
 */
public final class ReportRow {
  private final CoverageRecord record;
  private final String producedM4;

  ReportRow(CoverageRecord record, String producedM4) {
    this.record = requireNonNull(record, "record");
    this.producedM4 = producedM4;
  }

  /** The underlying classification record. */
  public CoverageRecord record() {
    return record;
  }

  /** The produced M4 element (provenance path, else the record's own
   * description), or empty when nothing was emitted (REFUSE). */
  public Optional<String> producedM4() {
    return Optional.ofNullable(producedM4);
  }

  @Override public String toString() {
    return record + (producedM4 == null ? "" : " -> " + producedM4);
  }
}

// End ReportRow.java
