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

import mondrian.lookml.model.ClassificationResult;
import mondrian.lookml.model.CoverageRecord;
import mondrian.lookml.model.ReasonCode;
import mondrian.lookml.model.Scope;
import mondrian.lookml.parse.LookmlNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Static safety gate for the LookML&rarr;Mondrian-M4 importer (issue #100).
 *
 * <p>Classifies each explore and each field of a parsed LookML document as
 * {@code CLEAN} / {@code DEGRADE} / {@code REFUSE}, emitting a
 * {@link CoverageRecord} per construct into a {@link ClassificationResult}. It
 * performs <em>no</em> conversion, <em>no</em> warehouse access and emits
 * <em>no</em> schema: it consumes a {@link LookmlNode} and produces the
 * classification only.
 *
 * <p>The two "killer" detections are purely metadata-driven:
 * <ul>
 *   <li><b>Non-star topology</b> &mdash; per explore, {@link ExploreGraph}
 *   builds the join graph; a {@code full_outer}/{@code cross} join or an
 *   unbridged {@code many_to_many} refuses the whole explore.</li>
 *   <li><b>Symmetric-aggregate fan-out</b> &mdash; an additive aggregate
 *   ({@code sum}/{@code average}/{@code count}) on the "one" side of a
 *   {@code one_to_many} an explore fans out is refused per-measure; this is the
 *   silently-wrong case the gate exists to catch.</li>
 * </ul>
 *
 * <p>v1 limitation: the model is classified <em>as parsed</em>. {@code extends}
 * / refinements / {@code @{}} constants are not flattened; a follow-up flatten
 * pass can run before classification.
 */
public final class LookmlClassifier {

  /** Classifies a parsed LookML document. */
  public ClassificationResult classify(LookmlNode document) {
    requireNonNull(document, "document");
    final ClassificationResult.Builder out = ClassificationResult.builder();

    // 0. Index modelled dimension keys so an access_filter field can be told
    //    apart from an arbitrary predicate (a measure / unknown field).
    final Set<String> dimensionKeys = indexDimensionKeys(document);

    // 1. Build every explore graph; index base view -> fan-out edge so a
    //    measure can be tested for symmetric-aggregate dependence.
    final Map<String, JoinEdge> fanOutByBaseView = new HashMap<>();
    for (LookmlNode explore : document.children(LookmlKeywords.EXPLORE)) {
      final ExploreGraph graph = ExploreGraph.from(explore);
      classifyExplore(explore, graph, dimensionKeys, out);
      graph.firstFanOutEdge().ifPresent(edge ->
          fanOutByBaseView.putIfAbsent(graph.baseView(), edge));
    }

    // 2. Classify every view's fields, consulting the fan-out index.
    for (LookmlNode view : document.children(LookmlKeywords.VIEW)) {
      classifyView(view, fanOutByBaseView, out);
    }

    return out.build();
  }

  /** Collects every modelled dimension key, both as {@code view.dimension} and
   * as a bare {@code dimension}, across all views. */
  private Set<String> indexDimensionKeys(LookmlNode document) {
    final Set<String> keys = new HashSet<>();
    for (LookmlNode view : document.children(LookmlKeywords.VIEW)) {
      final String viewName = view.name().orElse("");
      addDimensionNames(view, LookmlKeywords.DIMENSION, viewName, keys);
      addDimensionNames(view, LookmlKeywords.DIMENSION_GROUP, viewName, keys);
    }
    return keys;
  }

  private void addDimensionNames(LookmlNode view, String key, String viewName,
      Set<String> keys) {
    for (LookmlNode dim : view.children(key)) {
      final String name = dim.name().orElse("");
      if (!name.isEmpty()) {
        keys.add(name);
        keys.add(viewName + "." + name);
      }
    }
  }

  // --- explores ----------------------------------------------------------

  private void classifyExplore(LookmlNode explore, ExploreGraph graph,
      Set<String> dimensionKeys, ClassificationResult.Builder out) {
    final String qn = "explore:" + graph.exploreName();

    // Topology check (the first killer): refuse the whole explore.
    final Optional<JoinEdge> nonStar = graph.firstNonStarEdge();
    if (nonStar.isPresent()) {
      out.add(CoverageRecord.builder(Scope.EXPLORE, qn,
              ReasonCode.REFUSE_NON_STAR_TOPOLOGY,
              nonStarReason(graph, nonStar.get()))
          .lostCapability("explore not emitted")
          .build());
    } else {
      final Optional<CoverageRecord> filterIssue =
          classifyAccessFilters(explore, qn, dimensionKeys);
      out.add(filterIssue.orElseGet(() ->
          CoverageRecord.builder(Scope.EXPLORE, qn, ReasonCode.CLEAN,
                  "explore `" + graph.exploreName()
                      + "` is a star/snowflake of left-joins")
              .producedM4("cube")
              .build()));
    }

    // aggregate_table blocks degrade independently of the explore outcome.
    for (LookmlNode agg : explore.children(LookmlKeywords.AGGREGATE_TABLE)) {
      out.add(aggregateTableRecord(graph.exploreName(), agg));
    }
  }

  /** Returns a refusal record if any access_filter is an arbitrary predicate,
   * else empty (a dimension-key access_filter is CLEAN). */
  private Optional<CoverageRecord> classifyAccessFilters(LookmlNode explore,
      String qn, Set<String> dimensionKeys) {
    for (LookmlNode af : explore.children(LookmlKeywords.ACCESS_FILTER)) {
      final Optional<String> field = af.stringValue(LookmlKeywords.FIELD);
      if (!isSimpleDimensionRef(field, dimensionKeys)) {
        return Optional.of(CoverageRecord.builder(Scope.EXPLORE, qn,
                ReasonCode.REFUSE_ARBITRARY_ACCESS_FILTER,
                "explore `" + explore.name().orElse("?")
                    + "` has an access_filter on `" + field.orElse("?")
                    + "` that is not a simple modelled dimension key; "
                    + "predicate-based row security lands in #106")
            .lostCapability("explore not emitted")
            .build());
      }
    }
    return Optional.empty();
  }

  /** A clean access_filter targets a modelled dimension key: a single, syntax-
   * clean {@code view.field}/{@code field} reference that resolves to a known
   * dimension. A measure, an unknown field or any operator-bearing predicate is
   * arbitrary. */
  private boolean isSimpleDimensionRef(Optional<String> field,
      Set<String> dimensionKeys) {
    if (field.isEmpty()) {
      return false;
    }
    final String f = field.get().trim();
    if (f.isEmpty() || Liquid.isPresent(f)) {
      return false;
    }
    // Reject SQL operators / whitespace that signal an arbitrary predicate.
    if (f.matches(".*[\\s<>=!()+*/-].*")) {
      return false;
    }
    if (!f.matches("[A-Za-z_][\\w]*(\\.[A-Za-z_][\\w]*)?")) {
      return false;
    }
    // Must resolve to a modelled dimension (not a measure / unknown field).
    return dimensionKeys.contains(f);
  }

  private String nonStarReason(ExploreGraph graph, JoinEdge edge) {
    final String cause = edge.isUnbridgedManyToMany()
        ? "unbridged many_to_many"
        : edge.type();
    return "explore `" + graph.exploreName() + "` join `" + edge.joinName()
        + "` (" + cause + ") is not a star/snowflake fact→dim left-join; "
        + "it would break structurally";
  }

  private CoverageRecord aggregateTableRecord(String exploreName,
      LookmlNode agg) {
    final String name = agg.name().orElse("?");
    final String qn = "explore:" + exploreName + ".aggregate_table:" + name;
    return CoverageRecord.builder(Scope.EXPLORE, qn,
            ReasonCode.DEGRADE_AGGREGATE_TABLE_NOT_CONVERTED,
            "aggregate_table `" + name + "` on explore `" + exploreName
                + "` is not converted; the engine regenerates aggregates so "
                + "queries still run against the base")
        .lostCapability("aggregate_table not converted")
        .build();
  }

  // --- views -------------------------------------------------------------

  private void classifyView(LookmlNode view,
      Map<String, JoinEdge> fanOutByBaseView, ClassificationResult.Builder out) {
    final String viewName = view.name().orElse("?");

    classifyDerivedTable(view, viewName).ifPresent(out::add);

    final FieldClassifier fields = new FieldClassifier(viewName);
    final Optional<JoinEdge> fanOut =
        Optional.ofNullable(fanOutByBaseView.get(viewName));

    for (LookmlNode measure : view.children(LookmlKeywords.MEASURE)) {
      out.add(fields.classifyMeasure(measure, fanOut));
    }
    for (LookmlNode parameter : view.children(LookmlKeywords.PARAMETER)) {
      out.add(fields.classifyParameter(parameter));
    }
    for (LookmlNode dimension : view.children(LookmlKeywords.DIMENSION)) {
      out.add(fields.classifyDimension(dimension));
    }
    for (LookmlNode dg : view.children(LookmlKeywords.DIMENSION_GROUP)) {
      out.add(fields.classifyDimension(dg));
    }
  }

  /** A derived_table with a persistence policy degrades (policy dropped). */
  private Optional<CoverageRecord> classifyDerivedTable(LookmlNode view,
      String viewName) {
    final Optional<LookmlNode> derived =
        view.child(LookmlKeywords.DERIVED_TABLE);
    if (derived.isEmpty()) {
      return Optional.empty();
    }
    final boolean persisted = LookmlKeywords.PERSISTENCE_KEYS.stream()
        .anyMatch(k -> view.child(LookmlKeywords.DERIVED_TABLE)
            .flatMap(d -> d.stringValue(k)).isPresent());
    if (!persisted) {
      return Optional.empty();
    }
    return Optional.of(CoverageRecord.builder(Scope.VIEW, "view:" + viewName,
            ReasonCode.DEGRADE_PDT_PERSISTENCE_DROPPED,
            "derived_table on view `" + viewName + "` has a persistence policy "
                + "that is dropped; emitted as a plain <View> (segment-cache "
                + "story #94/#95/#96)")
        .producedM4("View")
        .lostCapability("PDT persistence policy dropped")
        .build());
  }
}

// End LookmlClassifier.java
