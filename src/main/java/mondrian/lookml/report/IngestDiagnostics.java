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

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Issue #98: per-file ingest diagnostics for directory-mode reports. Records
 * the files that could not be parsed (with a concise reason) and the files that
 * were deliberately skipped (e.g. {@code *.dashboard.lkml}), so a whole-project
 * report can be produced for partial success instead of aborting on the first
 * bad file.
 *
 * <p>Immutable. {@link #none()} is the empty instance used for single-file
 * reports and to keep the legacy single-argument writer methods unchanged.
 */
public final class IngestDiagnostics {

  private static final IngestDiagnostics NONE =
      new IngestDiagnostics(ImmutableList.of(), ImmutableList.of());

  private final ImmutableList<Entry> unparseable;
  private final ImmutableList<Entry> skipped;

  private IngestDiagnostics(ImmutableList<Entry> unparseable,
      ImmutableList<Entry> skipped) {
    this.unparseable = unparseable;
    this.skipped = skipped;
  }

  /** The empty diagnostics (no unparseable, no skipped files). */
  public static IngestDiagnostics none() {
    return NONE;
  }

  /** Builds diagnostics from the two file lists (defensively copied). */
  public static IngestDiagnostics of(List<Entry> unparseable,
      List<Entry> skipped) {
    requireNonNull(unparseable, "unparseable");
    requireNonNull(skipped, "skipped");
    return new IngestDiagnostics(
        ImmutableList.copyOf(unparseable), ImmutableList.copyOf(skipped));
  }

  /** Files that failed to parse, in discovery order. */
  public ImmutableList<Entry> unparseable() {
    return unparseable;
  }

  /** Files deliberately skipped (e.g. dashboards), in discovery order. */
  public ImmutableList<Entry> skipped() {
    return skipped;
  }

  /** True when there is nothing to report (no unparseable, no skipped). */
  public boolean isEmpty() {
    return unparseable.isEmpty() && skipped.isEmpty();
  }

  /** A single diagnostic line: a file path and a concise reason. */
  public static final class Entry {
    private final String file;
    private final String reason;

    /** Creates an entry; {@code reason} may be a skip rationale or an error. */
    public Entry(String file, String reason) {
      this.file = requireNonNull(file, "file");
      this.reason = requireNonNull(reason, "reason");
    }

    /** The file path (as discovered/reported). */
    public String file() {
      return file;
    }

    /** The concise reason (parse error or skip rationale). */
    public String reason() {
      return reason;
    }

    @Override public String toString() {
      return file + " :: " + reason;
    }
  }
}

// End IngestDiagnostics.java
