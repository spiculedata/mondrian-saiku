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
 * Maps a single LookML {@code measure:} to its M4 form: a plain aggregated
 * {@code <Measure>}, or — when the measure carries an equality {@code filters:}
 * — a hidden base measure plus a {@code <CalculatedMember>} that restricts it
 * to the filter members. Pure helper; appends to the caller's lists and records
 * provenance.
 */
final class Measures {
  private Measures() {}

  /** Suffix for the hidden base measure backing a filtered (calculated)
   * measure, e.g. {@code complete_amount__base}. */
  private static final String BASE_SUFFIX = "__base";

  static void emit(LookmlNode measure, String measureName, String baseViewName,
      String mgPath, String cubePath, Optional<String> factCountColumn,
      List<Object> measures, List<Object> calculatedMembers,
      ProvenanceMap.Builder provenance) {
    final String type = type(measure);
    final String aggregator = TranspileKeywords.AGGREGATOR_BY_TYPE.get(type);
    if (aggregator == null) {
      // Defensive: an aggregator the classifier should have refused. Skip it.
      return;
    }

    final Optional<String> formatString = formatString(measure);
    final Optional<String> caption =
        measure.stringValue(TranspileKeywords.LABEL);
    final Optional<String> description =
        measure.stringValue(TranspileKeywords.DESCRIPTION);
    final Optional<String> column =
        columnFor(measure, measureName, type, factCountColumn);
    if (column.isEmpty()) {
      // A count with no usable fact column to count over: cannot emit a valid
      // measure, so skip it rather than emit a schema that fails to load.
      return;
    }
    final List<FilterEq> filters = filters(measure);
    final String col = column.get();
    final String qn = baseViewName + "." + measureName;
    final Optional<String> percentile = percentile(measure, type);

    if (filters.isEmpty()) {
      measures.add(buildMeasure(measureName, col, aggregator, percentile,
          formatString, caption, description, /* visible */ null));
      provenance.put(qn, mgPath + "/measure:" + measureName);
      return;
    }

    // Filtered measure: hidden base measure + calculated member.
    final String baseName = measureName + BASE_SUFFIX;
    measures.add(buildMeasure(baseName, col, aggregator, percentile,
        formatString, caption, description, /* visible */ Boolean.FALSE));
    calculatedMembers.add(buildFilteredCalcMember(measureName, baseName,
        filters, formatString, caption, description));
    provenance.put(qn, cubePath + "/calculatedMember:" + measureName);
  }

  // --- builders -----------------------------------------------------------

  private static Map<String, Object> buildMeasure(String name, String column,
      String aggregator, Optional<String> percentile,
      Optional<String> formatString, Optional<String> caption,
      Optional<String> description, Boolean visible) {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("column", column);
    m.put("aggregator", aggregator);
    // #104: the percentile attribute (0..100) for aggregator: percentile.
    percentile.ifPresent(p -> m.put("percentile", p));
    formatString.ifPresent(f -> m.put("format_string", f));
    caption.ifPresent(c -> m.put("caption", c));
    description.ifPresent(d -> m.put("description", d));
    if (visible != null) {
      m.put("visible", visible);
    }
    return m;
  }

  /** A calculated member that restricts the hidden base measure to the filter
   * members: {@code ([Measures].[base], [dim].[v1], [dim2].[v2])}. */
  private static Map<String, Object> buildFilteredCalcMember(String name,
      String baseName, List<FilterEq> filters, Optional<String> formatString,
      Optional<String> caption, Optional<String> description) {
    final StringBuilder tuple = new StringBuilder("(")
        .append("[Measures].[").append(baseName).append(']');
    for (FilterEq f : filters) {
      tuple.append(", [").append(f.dimension).append("].[")
          .append(f.value).append(']');
    }
    tuple.append(')');

    final Map<String, Object> cm = new LinkedHashMap<>();
    cm.put("name", name);
    cm.put("dimension", TranspileKeywords.MEASURES_DIMENSION);
    cm.put("formula", tuple.toString());
    formatString.ifPresent(fmt -> cm.put("format_string", fmt));
    caption.ifPresent(c -> cm.put("caption", c));
    description.ifPresent(d -> cm.put("description", d));
    return cm;
  }

  // --- reads --------------------------------------------------------------

  /** The column the measure aggregates. An explicit {@code sql} column wins;
   * otherwise a {@code count} (row count, no column in LookML) falls back to a
   * guaranteed-present fact column to count over, and any other aggregator
   * falls back to a column named after the measure. */
  private static Optional<String> columnFor(LookmlNode measure,
      String measureName, String type, Optional<String> factCountColumn) {
    final Optional<String> sqlColumn = LookmlTranspiler.columnFromSql(measure);
    if (sqlColumn.isPresent()) {
      return sqlColumn;
    }
    if ("count".equals(type)) {
      return factCountColumn;
    }
    return Optional.of(measureName);
  }

  /** The percentile attribute for a {@code type: percentile} measure: the
   * LookML {@code percentile:} value, defaulting to 50. Median maps to
   * percentile 50 implicitly, so it needs no explicit attribute. Empty for any
   * other aggregator. */
  private static Optional<String> percentile(LookmlNode measure, String type) {
    if (!TranspileKeywords.AGG_PERCENTILE.equals(type)) {
      return Optional.empty();
    }
    return Optional.of(measure.value(TranspileKeywords.PERCENTILE)
        .map(LookmlNode::asString)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .orElse(String.valueOf(TranspileKeywords.DEFAULT_PERCENTILE)));
  }

  private static String type(LookmlNode measure) {
    return measure.stringValue(TranspileKeywords.TYPE)
        .map(s -> s.toLowerCase(Locale.ROOT))
        .orElse("");
  }

  /** {@code value_format} (a literal) wins over {@code value_format_name} (a
   * named format) for the M4 {@code format_string}. */
  private static Optional<String> formatString(LookmlNode measure) {
    return measure.stringValue(TranspileKeywords.VALUE_FORMAT)
        .or(() -> measure.stringValue(TranspileKeywords.VALUE_FORMAT_NAME));
  }

  /** Reads {@code filters: [dim: "value"]} as equality pairs. A filter whose
   * value carries an operator (e.g. {@code "> 10"}) is not a plain equality
   * and is skipped (the classifier only lets equality, no-Liquid filters
   * through). */
  private static List<FilterEq> filters(LookmlNode measure) {
    final List<FilterEq> out = new ArrayList<>();
    final Optional<Value> raw = measure.value(TranspileKeywords.FILTERS);
    if (raw.isEmpty() || !(raw.get() instanceof Values.ListValue)) {
      return out;
    }
    for (Value e : ((Values.ListValue) raw.get()).list) {
      if (e instanceof Values.PairValue) {
        final Values.PairValue p = (Values.PairValue) e;
        final String value = p.s == null ? "" : p.s.trim();
        if (!value.isEmpty() && isEquality(value)) {
          out.add(new FilterEq(dimensionName(p.ref), value));
        }
      }
    }
    return out;
  }

  /** A plain equality filter value: no comparison/range operators. */
  private static boolean isEquality(String value) {
    return !value.matches(".*[<>=%].*") && !value.contains("..")
        && !value.toLowerCase(Locale.ROOT).startsWith("not ");
  }

  /** A {@code view.field} filter reference maps to the M4 dimension; for the
   * v1 degenerate/conformed naming the dimension is the field's view-or-field
   * leaf. A bare {@code field} maps to a same-named dimension. */
  private static String dimensionName(String ref) {
    if (ref == null) {
      return "";
    }
    final int dot = ref.lastIndexOf('.');
    return dot >= 0 ? ref.substring(dot + 1) : ref;
  }

  /** One equality filter: dimension name and the member value. */
  private static final class FilterEq {
    final String dimension;
    final String value;

    FilterEq(String dimension, String value) {
      this.dimension = dimension;
      this.value = value;
    }
  }
}

// End Measures.java
