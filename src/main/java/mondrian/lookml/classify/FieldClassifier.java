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
   * empty if the field's view is never fanned out.
   */
  CoverageRecord classifyMeasure(LookmlNode measure,
      Optional<JoinEdge> fanOutEdge) {
    final String qn = qualifiedName(measure);
    final String type = lowerType(measure);

    // Order matters: refuse the most decisive reasons first.
    final Optional<ReasonCode> guard = guardRefusals(measure, type);
    if (guard.isPresent()) {
      return refusal(qn, guard.get(), measure, type, fanOutEdge);
    }

    // Symmetric-aggregate fan-out: additive aggregate on the "one" side of a
    // one_to_many the explore fans out. count_distinct/min/max are safe.
    if (LookmlKeywords.ADDITIVE_AGGREGATE_TYPES.contains(type)
        && fanOutEdge.isPresent()) {
      return refusal(qn, ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE, measure,
          type, fanOutEdge);
    }

    return clean(qn, Scope.FIELD,
        "measure `" + simpleName(measure) + "` (" + type
            + ") on clean grain converts with full fidelity");
  }

  /** Classifies a {@code parameter:} field (always refused). */
  CoverageRecord classifyParameter(LookmlNode parameter) {
    final String qn = qualifiedName(parameter);
    return CoverageRecord.builder(Scope.FIELD, qn,
            ReasonCode.REFUSE_PARAMETER_FIELD,
            "parameter `" + simpleName(parameter)
                + "` performs field/SQL switching that is not statically "
                + "resolvable; bounded parameters become CLEAN once "
                + "query-context parameters land (see #105)")
        .lostCapability("parameter field not emitted")
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
      return Optional.of(ReasonCode.REFUSE_MEDIAN_PERCENTILE);
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
    case REFUSE_MEDIAN_PERCENTILE:
      return "measure `" + name + "` (" + type
          + ") is a non-additive aggregator; becomes CLEAN once non-additive "
          + "aggregators land (see #99)";
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
}

// End FieldClassifier.java
