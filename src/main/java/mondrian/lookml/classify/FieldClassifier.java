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
  private final java.util.Set<String> declaredParameters;

  FieldClassifier(String viewName) {
    this(viewName, java.util.Collections.emptySet());
  }

  /** {@code declaredParameters} are the lower-cased names of bounded
   * {@code parameter:} declarations visible to this view's fields, so a
   * {@code {% parameter X %}} use can be confirmed bounded (#118). */
  FieldClassifier(String viewName, java.util.Set<String> declaredParameters) {
    this.viewName = requireNonNull(viewName, "viewName");
    this.declaredParameters =
        requireNonNull(declaredParameters, "declaredParameters");
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

    // Liquid takes precedence: a bounded pattern DEGRADEs (routed to a mapping),
    // arbitrary computed Liquid REFUSEs (#118). Decided before other guards.
    final Optional<CoverageRecord> liquid = classifyLiquid(qn, measure);
    if (liquid.isPresent()) {
      return liquid.get();
    }

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

    final Optional<CoverageRecord> unknownFormat =
        unknownValueFormatName(qn, measure);
    if (unknownFormat.isPresent()) {
      return unknownFormat.get();
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
    final Optional<String> rawKey =
        measure.stringValue(LookmlKeywords.SQL_DISTINCT_KEY);
    // No explicit key → fall back to the base view's primary key (de-dup on
    // the fact grain is a no-op). Requires a declared primary key.
    if (rawKey.isEmpty()) {
      if (!primaryKey.isPresent()) {
        return refusal(qn, ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR, measure,
            type, Optional.empty());
      }
      return distinctClean(qn, measure, type);
    }
    final Optional<String> resolved = DistinctKey.resolveSameView(rawKey.get());
    // #119: an unresolvable / cross-view key cannot be honoured at measure
    // level (the de-dup grain would be a foreign key) — stay REFUSE so we
    // never emit a silently-wrong de-dup.
    if (resolved.isEmpty()) {
      return refusal(qn, ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR, measure,
          type, Optional.empty());
    }
    // De-dup on the base view primary key is a no-op → plain SUM/AVG (#117).
    if (primaryKey.isPresent() && primaryKey.matches(resolved.get())) {
      return distinctClean(qn, measure, type);
    }
    // #119: a resolvable same-view key that is NOT the primary key maps to an
    // M4 measure-level distinct grain — the engine de-duplicates on the
    // declared key column before aggregating, without a <BridgeLink>. CLEAN.
    return distinctGrainClean(qn, measure, type, resolved.get());
  }

  /** #119: a {@code sum_distinct} / {@code average_distinct} whose
   * {@code sql_distinct_key} resolves to a real same-view column that is NOT
   * the primary key. Maps to an M4 measure declaring a distinct grain
   * ({@code distinctKeyColumn}); the engine de-duplicates the operand on that
   * key before aggregating, reusing the #103 symmetric-aggregate machinery
   * without a bridge. */
  private CoverageRecord distinctGrainClean(String qn, LookmlNode measure,
      String type, String key) {
    return clean(qn, Scope.FIELD,
        "measure `" + simpleName(measure) + "` (" + type
            + ") de-duplicates on `" + key + "` (a non-PK same-view key); "
            + "maps to an M4 " + plainAggregator(type)
            + " with a measure-level distinct grain (#119) — fan-out-safe "
            + "without a bridge");
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
    final Optional<CoverageRecord> liquid = classifyLiquid(qn, dimension);
    if (liquid.isPresent()) {
      return liquid.get();
    }
    final Optional<ReasonCode> guard = guardRefusals(dimension, type);
    if (guard.isPresent()) {
      return refusal(qn, guard.get(), dimension, type, Optional.empty());
    }
    final Optional<CoverageRecord> unknownFormat =
        unknownValueFormatName(qn, dimension);
    if (unknownFormat.isPresent()) {
      return unknownFormat.get();
    }
    return clean(qn, Scope.FIELD,
        "dimension `" + simpleName(dimension) + "` converts with full fidelity");
  }

  /** A DEGRADE record if {@code field} carries a {@code value_format_name} that
   * is not a known Looker preset (#115): the unknown name is emitted verbatim as
   * the {@code format_string}, so it may not render as Looker intended. Empty
   * when the format is absent or a known preset (then the field is CLEAN). */
  private Optional<CoverageRecord> unknownValueFormatName(String qn,
      LookmlNode field) {
    final Optional<String> name =
        field.stringValue(LookmlKeywords.VALUE_FORMAT_NAME)
            .map(String::trim)
            .filter(s -> !s.isEmpty());
    if (name.isEmpty()) {
      return Optional.empty();
    }
    final String lower = name.get().toLowerCase(Locale.ROOT);
    if (LookmlKeywords.KNOWN_VALUE_FORMAT_NAMES.contains(lower)) {
      return Optional.empty();
    }
    return Optional.of(CoverageRecord.builder(Scope.FIELD, qn,
            ReasonCode.DEGRADE_VALUE_FORMAT_NAME_UNKNOWN,
            "field `" + simpleName(field) + "` uses value_format_name `"
                + name.get() + "`, which is not a known Looker named preset; "
                + "it is emitted verbatim as the format_string and may not "
                + "render as Looker intended (#115)")
        .producedM4("emitted (format_string verbatim)")
        .lostCapability("unknown named format may not render correctly")
        .build());
  }

  // --- shared refusal guards (apply to any field) ------------------------

  /** Metadata-only refusals that apply regardless of fan-out. Liquid is handled
   * separately (see {@link #classifyLiquid}). */
  private Optional<ReasonCode> guardRefusals(LookmlNode field, String type) {
    if (!field.values(LookmlKeywords.REQUIRED_ACCESS_GRANTS).isEmpty()) {
      return Optional.of(ReasonCode.REFUSE_REQUIRED_ACCESS_GRANTS);
    }
    if (LookmlKeywords.TYPE_LIST.equals(type)) {
      return Optional.of(ReasonCode.REFUSE_TYPE_LIST);
    }
    if (LookmlKeywords.NON_ADDITIVE_REFUSED_TYPES.contains(type)) {
      return Optional.of(ReasonCode.REFUSE_UNSUPPORTED_AGGREGATOR);
    }
    return Optional.empty();
  }

  // --- Liquid (#118): bounded patterns DEGRADE, arbitrary REFUSEs ---------

  /**
   * Classifies any Liquid the field carries: empty if there is none; a DEGRADE
   * record routed to the matched bounded mapping; or a {@code REFUSE_LIQUID}
   * record naming why it is arbitrary.
   *
   * <p>Arbitrary (computed) Liquid only refuses when it sits in a SQL /
   * predicate / filter key, where it would shape engine SQL. The same Liquid
   * in a <em>presentation-only</em> key ({@code label} / {@code html} /
   * {@code value_format}) never reaches engine SQL, so it is dropped and the
   * field DEGRADEs instead of refusing the whole field
   * ({@link LookmlKeywords#LIQUID_PRESENTATION_KEYS}). A bounded fragment in
   * any key routes to its DEGRADE mapping as before.
   */
  private Optional<CoverageRecord> classifyLiquid(String qn, LookmlNode field) {
    LiquidPattern.Kind routed = null;
    String routedFragment = null;
    boolean presentationOnlyLiquid = false;
    for (String key : LookmlKeywords.LIQUID_SCAN_KEYS) {
      final boolean presentationKey =
          LookmlKeywords.LIQUID_PRESENTATION_KEYS.contains(key);
      for (Value v : field.values(key)) {
        for (String fragment : liquidFragments(v)) {
          final LiquidPattern.Kind kind = liquidKind(fragment);
          if (kind == LiquidPattern.Kind.ARBITRARY) {
            if (!presentationKey) {
              return Optional.of(refusalLiquid(qn, field, fragment));
            }
            // Arbitrary Liquid in a presentation-only key: dropped, not routed.
            presentationOnlyLiquid = true;
            continue;
          }
          routed = kind;
          routedFragment = fragment;
        }
      }
    }
    if (routed != null) {
      return Optional.of(boundedLiquid(qn, field, routed, routedFragment));
    }
    if (presentationOnlyLiquid) {
      return Optional.of(presentationLiquidDegrade(qn, field));
    }
    return Optional.empty();
  }

  /** A DEGRADE record for arbitrary Liquid confined to a presentation-only key
   * (label / html / value_format): the templated presentation fragment is
   * dropped, but the field still emits — it never shaped engine SQL (#118). */
  private CoverageRecord presentationLiquidDegrade(String qn,
      LookmlNode field) {
    return CoverageRecord.builder(Scope.FIELD, qn,
            ReasonCode.DEGRADE_LIQUID_BOUNDED,
            "field `" + simpleName(field) + "` carries computed Liquid only in "
                + "a presentation-only key (label/html/value_format); it never "
                + "shapes engine SQL, so the templated fragment is dropped and "
                + "the field is emitted without it (#118)")
        .producedM4("emitted (presentation Liquid dropped)")
        .lostCapability("templated presentation fragment dropped")
        .build();
  }

  /** Recognises the Liquid {@link LiquidPattern.Kind} of one fragment, but only
   * treats a {@code {% parameter X %}} use as bounded when X is a declared
   * bounded parameter (else it is field-switching we cannot resolve → arbitrary). */
  private LiquidPattern.Kind liquidKind(String fragment) {
    final LiquidPattern.Kind kind = LiquidPattern.classify(fragment);
    if (kind == LiquidPattern.Kind.PARAMETER) {
      final boolean declared = LiquidPattern.boundFieldName(fragment)
          .map(n -> declaredParameters.contains(n.toLowerCase(Locale.ROOT)))
          .orElse(false);
      return declared ? kind : LiquidPattern.Kind.ARBITRARY;
    }
    return kind;
  }

  /** A bounded-Liquid DEGRADE record describing the mapping the fragment routes
   * to (#118). */
  private CoverageRecord boundedLiquid(String qn, LookmlNode field,
      LiquidPattern.Kind kind, String fragment) {
    return CoverageRecord.builder(Scope.FIELD, qn,
            ReasonCode.DEGRADE_LIQUID_BOUNDED,
            boundedReason(field, kind, fragment))
        .producedM4(boundedM4(kind))
        .lostCapability("templated Liquid fragment dropped; only the typed, "
            + "enumerated bind-only construct is emitted")
        .build();
  }

  private String boundedReason(LookmlNode field, LiquidPattern.Kind kind,
      String fragment) {
    final String name = simpleName(field);
    switch (kind) {
    case USER_ATTRIBUTE:
      final String attr = LiquidPattern.userAttributeName(fragment).orElse("?");
      return "field `" + name + "` references the user attribute `" + attr
          + "` via Liquid; mapped to a `session." + attr
          + "` <QueryParameter> (and a <PredicateGrant> when it restricts a "
          + "fact column, #105/#106)";
    case PARAMETER:
      final String p = LiquidPattern.boundFieldName(fragment).orElse("?");
      return "field `" + name + "` uses the bounded parameter `" + p
          + "` via {% parameter %}; the declared parameter maps to an M4 "
          + "<QueryParameter> (#105); the selection is a Saiku-layer "
          + "field-switch (WITH MEMBER), not engine SQL";
    case CONDITION:
      final String y = LiquidPattern.boundFieldName(fragment).orElse("?");
      return "field `" + name + "` ties a {% condition %} filter to `" + y
          + "`; mapped to a parameter-bound filter (#105)";
    default:
      return "field `" + name + "` contains bounded Liquid";
    }
  }

  private static String boundedM4(LiquidPattern.Kind kind) {
    return kind == LiquidPattern.Kind.USER_ATTRIBUTE
        ? "QueryParameter(+PredicateGrant)"
        : "QueryParameter";
  }

  private CoverageRecord refusalLiquid(String qn, LookmlNode field,
      String fragment) {
    return CoverageRecord.builder(Scope.FIELD, qn, ReasonCode.REFUSE_LIQUID,
            "field `" + simpleName(field) + "` contains computed Liquid SQL "
                + "(control flow / arithmetic / string-building) that is not a "
                + "bounded, bind-only pattern; emitting it would be silently "
                + "wrong")
        .lostCapability("field not emitted")
        .build();
  }

  /** The Liquid-bearing string fragments of a value (flattening the list/pair
   * shapes {@code filters:} uses); only fragments that actually carry Liquid. */
  private List<String> liquidFragments(Value v) {
    final List<String> out = new java.util.ArrayList<>();
    collectLiquidFragments(v, out);
    return out;
  }

  private void collectLiquidFragments(Value v, List<String> out) {
    if (v instanceof Values.ListValue) {
      for (Value e : ((Values.ListValue) v).list) {
        collectLiquidFragments(e, out);
      }
      return;
    }
    final String s = v instanceof Values.PairValue
        ? ((Values.PairValue) v).s
        : LookmlNode.asString(v);
    if (Liquid.isPresent(s)) {
      out.add(s);
    }
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
