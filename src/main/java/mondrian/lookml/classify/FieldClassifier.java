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
package mondrian.lookml.classify;

import mondrian.lookml.model.CoverageRecord;
import mondrian.lookml.model.ReasonCode;
import mondrian.lookml.model.Scope;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.Value;
import mondrian.lookml.parse.Values;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Classifies a single LookML field (measure / dimension / parameter) within a
 * view, against the explore fan-out context. Pure: node in, record out.
 */
final class FieldClassifier {
  private final String viewName;

  FieldClassifier(String viewName) {
    this.viewName = requireNonNull(viewName, "viewName");
  }

  /**
   * Classifies a {@code measure:} field. {@code fanOutEdge} is the
   * {@code one_to_many} edge an explore fans the field's view out across, or
   * empty if the field's view is never fanned out. {@code hasPrimaryKey} is
   * whether the field's view declares a {@code primary_key: yes} dimension (the
   * fact grain symmetric aggregation needs).
   */
  CoverageRecord classifyMeasure(LookmlNode measure,
      Optional<JoinEdge> fanOutEdge, PrimaryKey primaryKey) {
    final String qn = qualifiedName(measure);
    final String type = lowerType(measure);

    // Order matters: refuse the most decisive reasons first.
    final Optional<ReasonCode> guard = guardRefusals(measure, type);
    if (guard.isPresent()) {
      return refusal(qn, guard.get(), measure, type, fanOutEdge);
    }

    // Distinct-key aggregators (#117): sum_distinct / average_distinct map to a
    // plain SUM/AVG only when the sql_distinct_key resolves to the base view's
    // own primary key (one row per key → de-dup is a no-op). Otherwise the
    // engine cannot de-duplicate at measure level, so it stays REFUSE.
    if (LookmlKeywords.DISTINCT_KEY_AGGREGATE_TYPES.contains(type)) {
      return classifyDistinctAggregate(qn, measure, type, primaryKey);
    }

    // Percentile-family (#104): emit as an M4 median/percentile aggregator,
    // but DEGRADE — the runtime needs a PERCENTILE_CONT-capable backend and
    // the importer cannot know the target dialect.
    if (LookmlKeywords.PERCENTILE_FAMILY_TYPES.contains(type)) {
      return percentileDegrade(qn, measure, type);
    }

    // Fan-out additive aggregate on the "one" side of a one_to_many. Symmetric
    // (fan-out-safe) aggregation shipped (#103), but it can only fire when the
    // fact declares a grain key. Without a primary key, emitting the sum would
    // be silently wrong, so it stays REFUSE.
    if (LookmlKeywords.ADDITIVE_AGGREGATE_TYPES.contains(type)
        && fanOutEdge.isPresent() && !primaryKey.isPresent()) {
      return refusal(qn, ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE, measure,
          type, fanOutEdge);
    }

    return clean(qn, Scope.FIELD,
        "measure `" + simpleName(measure) + "` (" + type
            + ") converts with full fidelity"
            + (fanOutEdge.isPresent()
                ? " (fan-out-safe via symmetric aggregation, #103)" : ""));
  }

  /**
   * Classifies a {@code sum_distinct} / {@code average_distinct} measure
   * (#117). CLEAN when its {@code sql_distinct_key} (or the primary-key
   * fallback) resolves to the base view's own {@code primary_key: yes}
   * dimension — then the de-dup is a no-op and it maps to a plain SUM/AVG, made
   * fan-out-safe by the #103 symmetric path when queried through a bridge.
   * Otherwise the de-dup grain is not the fact grain and the engine cannot
   * honour it at measure level, so it stays REFUSE (never silently wrong).
   */
  private CoverageRecord classifyDistinctAggregate(String qn,
      LookmlNode measure, String type, PrimaryKey primaryKey) {
    if (!primaryKey.isPresent()) {
      return refusal(qn, ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR, measure,
          type, Optional.empty());
    }
    final Optional<String> rawKey =
        measure.stringValue(LookmlKeywords.SQL_DISTINCT_KEY);
    // No explicit key → fall back to the base view's primary key (de-dup on
    // the fact grain is a no-op).
    if (rawKey.isEmpty()) {
      return distinctClean(qn, measure, type);
    }
    final Optional<String> resolved = DistinctKey.resolveSameView(rawKey.get());
    if (resolved.isPresent() && primaryKey.matches(resolved.get())) {
      return distinctClean(qn, measure, type);
    }
    return refusal(qn, ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR, measure, type,
        Optional.empty());
  }

  private CoverageRecord distinctClean(String qn, LookmlNode measure,
      String type) {
    return clean(qn, Scope.FIELD,
        "measure `" + simpleName(measure) + "` (" + type
            + ") de-duplicates on the base view primary key (the fact grain), "
            + "so it maps to a plain " + plainAggregator(type)
            + " — fan-out-safe via symmetric aggregation (#117/#103)");
  }

  /** The plain M4 aggregator a distinct-key measure collapses to once the
   * de-dup is shown to be a no-op. */
  private static String plainAggregator(String type) {
    return LookmlKeywords.TYPE_AVERAGE_DISTINCT.equals(type) ? "avg" : "sum";
  }

  /** Classifies a {@code parameter:} field. A bounded parameter declaration
   * maps to an M4 {@code <QueryParameter>} (#105), so it is CLEAN. A
   * parameter's <em>use</em> in {@code {% parameter %}} Liquid SQL is caught
   * separately as REFUSE_LIQUID on the using field. */
  CoverageRecord classifyParameter(LookmlNode parameter) {
    final String qn = qualifiedName(parameter);
    return CoverageRecord.builder(Scope.FIELD, qn, ReasonCode.CLEAN,
            "parameter `" + simpleName(parameter)
                + "` is a bounded declaration; emitted as an M4 "
                + "<QueryParameter> (#105). Its use in {% parameter %} SQL "
                + "field-switching, if any, is refused separately as Liquid.")
        .producedM4("QueryParameter")
        .build();
  }

  private CoverageRecord percentileDegrade(String qn, LookmlNode measure,
      String type) {
    return CoverageRecord.builder(Scope.FIELD, qn,
            ReasonCode.DEGRADE_PERCENTILE_DIALECT,
            "measure `" + simpleName(measure) + "` (" + type
                + ") maps to an M4 " + type + " aggregator (#104); requires a "
                + "PERCENTILE_CONT-capable backend at query time")
        .producedM4("Measure(aggregator=" + type + ")")
        .lostCapability("requires a PERCENTILE_CONT-capable backend")
        .build();
  }

  /** Classifies a {@code dimension:} / {@code dimension_group:} field. */
  CoverageRecord classifyDimension(LookmlNode dimension) {
    final String qn = qualifiedName(dimension);
    final String type = lowerType(dimension);
    final Optional<ReasonCode> guard = guardRefusals(dimension, type);
    if (guard.isPresent()) {
      return refusal(qn, guard.get(), dimension, type, Optional.empty());
    }
    return clean(qn, Scope.FIELD,
        "dimension `" + simpleName(dimension) + "` converts with full fidelity");
  }

  // --- shared refusal guards (apply to any field) ------------------------

  /** Metadata-only refusals that apply regardless of fan-out. */
  private Optional<ReasonCode> guardRefusals(LookmlNode field, String type) {
    if (!field.values(LookmlKeywords.REQUIRED_ACCESS_GRANTS).isEmpty()) {
      return Optional.of(ReasonCode.REFUSE_REQUIRED_ACCESS_GRANTS);
    }
    if (hasLiquid(field)) {
      return Optional.of(ReasonCode.REFUSE_LIQUID);
    }
    if (LookmlKeywords.TYPE_LIST.equals(type)) {
      return Optional.of(ReasonCode.REFUSE_TYPE_LIST);
    }
    if (LookmlKeywords.NON_ADDITIVE_REFUSED_TYPES.contains(type)) {
      return Optional.of(ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR);
    }
    return Optional.empty();
  }

  /** Whether any scanned key on the field carries Liquid. */
  private boolean hasLiquid(LookmlNode field) {
    for (String key : LookmlKeywords.LIQUID_SCAN_KEYS) {
      for (Value v : field.values(key)) {
        if (valueHasLiquid(v)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Scans a value (including list/pair shapes used by {@code filters:}). */
  private boolean valueHasLiquid(Value v) {
    if (v instanceof Values.ListValue) {
      for (Value e : ((Values.ListValue) v).list) {
        if (valueHasLiquid(e)) {
          return true;
        }
      }
      return false;
    }
    if (v instanceof Values.PairValue) {
      return Liquid.isPresent(((Values.PairValue) v).s);
    }
    return Liquid.isPresent(LookmlNode.asString(v));
  }

  // --- record helpers ----------------------------------------------------

  private CoverageRecord refusal(String qn, ReasonCode code, LookmlNode field,
      String type, Optional<JoinEdge> fanOutEdge) {
    return CoverageRecord.builder(Scope.FIELD, qn, code,
            reasonText(code, field, type, fanOutEdge))
        .lostCapability("field not emitted")
        .build();
  }

  private CoverageRecord clean(String qn, Scope scope, String reason) {
    return CoverageRecord.builder(scope, qn, ReasonCode.CLEAN, reason)
        .producedM4("emitted")
        .build();
  }

  private String reasonText(ReasonCode code, LookmlNode field, String type,
      Optional<JoinEdge> fanOutEdge) {
    final String name = simpleName(field);
    switch (code) {
    case REFUSE_FANOUT_SYMMETRIC_AGGREGATE:
      final String edge = fanOutEdge
          .map(e -> viewName + "→" + e.joinedView())
          .orElse(viewName);
      return "measure `" + name + "` (" + type
          + ") fans out across one_to_many join `" + edge
          + "`; symmetric aggregation required (see #103)";
    case REFUSE_LIQUID:
      return "field `" + name + "` contains Liquid templating ({{ }} / {% %}) "
          + "that is not statically resolvable";
    case REFUSE_TYPE_LIST:
      return "field `" + name + "` is type: list (multi-valued, non-OLAP)";
    case REFUSE_UNSUPPORTED_AGGREGATOR:
      return "measure `" + name + "` (" + type
          + ") has no static M4 aggregator mapping; emitting it would be "
          + "silently wrong";
    case REFUSE_REQUIRED_ACCESS_GRANTS:
      return "field `" + name + "` is guarded by required_access_grants the "
          + "importer cannot evaluate";
    default:
      return "field `" + name + "` refused (" + code + ")";
    }
  }

  // --- name / type helpers ----------------------------------------------

  private String simpleName(LookmlNode field) {
    return field.name().orElse("?");
  }

  private String qualifiedName(LookmlNode field) {
    return viewName + "." + simpleName(field);
  }

  private static String lowerType(LookmlNode field) {
    return field.stringValue(LookmlKeywords.TYPE)
        .map(s -> s.toLowerCase(Locale.ROOT))
        .orElse("");
  }

  /**
   * The base view's {@code primary_key: yes} dimension, if any: its name and
   * resolved column (the fact grain). A {@code sql_distinct_key} that resolves
   * to either is de-duplicating on the fact grain (#117).
   */
  static final class PrimaryKey {
    private static final PrimaryKey NONE = new PrimaryKey(null, null);
    private final String dimensionName;
    private final String column;

    private PrimaryKey(String dimensionName, String column) {
      this.dimensionName = dimensionName;
      this.column = column;
    }

    /** The absent primary key. */
    static PrimaryKey none() {
      return NONE;
    }

    /** A primary key on dimension {@code name} resolving to {@code column}. */
    static PrimaryKey of(String name, String column) {
      return new PrimaryKey(
          name == null ? null : name.toLowerCase(Locale.ROOT),
          column == null ? null : column.toLowerCase(Locale.ROOT));
    }

    boolean isPresent() {
      return dimensionName != null;
    }

    /** Whether a resolved distinct-key name matches this primary key (by
     * dimension name or by underlying column). */
    boolean matches(String resolvedKey) {
      if (!isPresent() || resolvedKey == null) {
        return false;
      }
      final String k = resolvedKey.toLowerCase(Locale.ROOT);
      return k.equals(dimensionName) || k.equals(column);
    }
  }
}

// End FieldClassifier.java
