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

/**
 * Issue #102: shared helper that derives the explore/view "owner" of a
 * coverage record from its qualified name, so the report can group rows
 * explore&rarr;field. Pure, no state.
 *
 * <p>Qualified-name conventions (set by the classifier / transpiler):
 * <ul>
 *   <li>{@code explore:<name>} &rarr; owner {@code <name>}</li>
 *   <li>{@code explore:<name>.aggregate_table:<agg>} &rarr; owner
 *       {@code <name>}</li>
 *   <li>{@code view:<name>} &rarr; owner {@code <name>}</li>
 *   <li>{@code <view>.<field>} &rarr; owner {@code <view>}</li>
 * </ul>
 */
final class ReportGrouping {

  private static final String EXPLORE_PREFIX = "explore:";
  private static final String VIEW_PREFIX = "view:";

  private ReportGrouping() {}

  /** The explore/view this qualified name belongs to, for section grouping. */
  static String owner(String qualifiedName) {
    if (qualifiedName.startsWith(EXPLORE_PREFIX)) {
      return upTo(qualifiedName.substring(EXPLORE_PREFIX.length()), '.');
    }
    if (qualifiedName.startsWith(VIEW_PREFIX)) {
      return qualifiedName.substring(VIEW_PREFIX.length());
    }
    return upTo(qualifiedName, '.');
  }

  private static String upTo(String s, char sep) {
    final int i = s.indexOf(sep);
    return i < 0 ? s : s.substring(0, i);
  }
}

// End ReportGrouping.java
