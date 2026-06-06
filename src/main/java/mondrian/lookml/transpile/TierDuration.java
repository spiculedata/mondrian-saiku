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
package mondrian.lookml.transpile;

import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.Value;
import mondrian.lookml.parse.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps LookML {@code type: tier} dimensions and {@code type: duration}
 * dimension_groups to their native M4 attribute children: a {@code <Tier>}
 * (with {@code <Bin>}s recovered from {@code tiers:}) and a {@code <Duration>}
 * (#108). Pure helper; builds the {@code tier:} / {@code duration:} YAML map a
 * queryable attribute carries.
 */
final class TierDuration {
  private TierDuration() {}

  /** Whether the field is a tier dimension or duration dimension_group. */
  static boolean isTierOrDuration(LookmlNode field) {
    final String type = type(field);
    return TranspileKeywords.TYPE_TIER.equals(type)
        || TranspileKeywords.TYPE_DURATION.equals(type);
  }

  /**
   * Builds a queryable, hierarchy-bearing attribute for a tier/duration field,
   * or empty if the field is neither (or is missing the columns it needs).
   */
  static Optional<Map<String, Object>> attribute(LookmlNode field,
      String name, String table) {
    final String type = type(field);
    if (TranspileKeywords.TYPE_TIER.equals(type)) {
      return tierAttribute(field, name, table);
    }
    if (TranspileKeywords.TYPE_DURATION.equals(type)) {
      return durationAttribute(field, name, table);
    }
    return Optional.empty();
  }

  // --- tier ---------------------------------------------------------------

  private static Optional<Map<String, Object>> tierAttribute(LookmlNode field,
      String name, String table) {
    final Optional<String> column = LookmlTranspiler.columnFromSql(field);
    if (column.isEmpty()) {
      return Optional.empty();
    }
    final List<Map<String, Object>> bins = bins(field);
    if (bins.isEmpty()) {
      return Optional.empty();
    }
    final Map<String, Object> tier = new LinkedHashMap<>();
    tier.put("column", column.get());
    tier.put("bins", bins);

    final Map<String, Object> attr = baseAttribute(name, table, field);
    attr.put("key_column", column.get());
    attr.put("tier", tier);
    return Optional.of(attr);
  }

  /** Recovers ordered bins from {@code tiers: [b1, b2, ...]}: one boundary bin
   * per value (label "&lt; b") plus a trailing catch-all bin (label "&ge; bN").
   * Mirrors the engine's boundary semantics: a bin's boundary is its
   * <em>upper</em> edge, and the last (boundary-less) bin catches the rest. */
  private static List<Map<String, Object>> bins(LookmlNode field) {
    final List<String> tiers = numberList(field);
    final List<Map<String, Object>> bins = new ArrayList<>();
    if (tiers.isEmpty()) {
      return bins;
    }
    String prev = null;
    for (String boundary : tiers) {
      bins.add(bin(boundary, label(prev, boundary)));
      prev = boundary;
    }
    // Trailing catch-all: no boundary, everything at or above the last tier.
    bins.add(bin(null, "≥ " + prev));
    return bins;
  }

  private static Map<String, Object> bin(String boundary, String label) {
    final Map<String, Object> bin = new LinkedHashMap<>();
    if (boundary != null) {
      bin.put("boundary", boundary);
    }
    bin.put("label", label);
    return bin;
  }

  private static String label(String lower, String upper) {
    return lower == null ? "< " + upper : lower + "–" + upper;
  }

  private static List<String> numberList(LookmlNode field) {
    return readList(field, TranspileKeywords.TIERS);
  }

  // --- duration -----------------------------------------------------------

  private static Optional<Map<String, Object>> durationAttribute(
      LookmlNode field, String name, String table) {
    final Optional<String> start =
        LookmlTranspiler.columnFromSql(field, TranspileKeywords.SQL_START);
    final Optional<String> end =
        LookmlTranspiler.columnFromSql(field, TranspileKeywords.SQL_END);
    if (start.isEmpty() || end.isEmpty()) {
      return Optional.empty();
    }
    final Map<String, Object> duration = new LinkedHashMap<>();
    duration.put("start_column", start.get());
    duration.put("end_column", end.get());
    unit(field).ifPresent(u -> duration.put("unit", u));

    final Map<String, Object> attr = baseAttribute(name, table, field);
    // The attribute keys on the computed duration; key_column points at the
    // start column so the physical layer has a real column to bind.
    attr.put("key_column", start.get());
    attr.put("duration", duration);
    return Optional.of(attr);
  }

  /** The duration's M4 unit, from the first LookML {@code intervals:} value
   * (e.g. {@code day} → {@code DAY}); empty defaults to the engine's DAY. */
  private static Optional<String> unit(LookmlNode field) {
    final List<String> intervals = readList(field, TranspileKeywords.INTERVALS);
    if (intervals.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(intervals.get(0).toUpperCase(Locale.ROOT));
  }

  // --- shared -------------------------------------------------------------

  private static Map<String, Object> baseAttribute(String name, String table,
      LookmlNode field) {
    final Map<String, Object> attr = new LinkedHashMap<>();
    attr.put("name", name);
    attr.put("table", table);
    attr.put("has_hierarchy", true);
    field.stringValue(TranspileKeywords.LABEL)
        .ifPresent(label -> attr.put("caption", label));
    field.stringValue(TranspileKeywords.DESCRIPTION)
        .ifPresent(desc -> attr.put("description", desc));
    return attr;
  }

  private static List<String> readList(LookmlNode field, String key) {
    final List<String> out = new ArrayList<>();
    final Optional<Value> raw = field.value(key);
    if (raw.isEmpty() || !(raw.get() instanceof Values.ListValue)) {
      return out;
    }
    for (Value e : ((Values.ListValue) raw.get()).list) {
      final String s = LookmlNode.asString(e);
      if (s != null && !s.trim().isEmpty()) {
        out.add(s.trim());
      }
    }
    return out;
  }

  private static String type(LookmlNode field) {
    return field.stringValue(TranspileKeywords.TYPE)
        .map(s -> s.toLowerCase(Locale.ROOT))
        .orElse("");
  }
}

// End TierDuration.java
