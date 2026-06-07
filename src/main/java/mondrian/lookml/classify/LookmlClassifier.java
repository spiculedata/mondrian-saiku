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
 *   builds the join graph; any join whose type is not {@code left_outer}
 *   ({@code inner}/{@code right_outer}/{@code full_outer}/{@code cross}), an
 *   unbridged {@code many_to_many}, or a chained-many topology (a
 *   {@code one_to_many} reached through another {@code one_to_many}) refuses
 *   the whole explore.</li>
 *   <li><b>Symmetric-aggregate fan-out</b> &mdash; an additive aggregate
 *   ({@code sum}/{@code average}/{@code count}) on the "one" side of a
 *   {@code one_to_many} an explore fans out is refused per-measure. Fan-out is
 *   detected for <em>every</em> view on a "one" side (the upstream view its
 *   {@code sql_on} references), not just the explore's base view; this is the
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

    // 0b. Index declared bounded parameter names so a {% parameter X %} use can
    //     be confirmed bounded (it selects among declared parameters, #118).
    final Set<String> parameterNames = indexParameterNames(document);

    // 1. Build every explore graph; index EVERY view on the "one" side of a
    //    one_to_many (not just the base) -> fan-out edge, so an additive
    //    measure on any fanned-out view can be tested for symmetric-aggregate
    //    dependence (#98).
    // 0c. Index whether each view declares a primary_key: yes dimension — the
    //     fact grain key a bridge two-hop needs to de-duplicate (#124).
    final Set<String> viewsWithPrimaryKey = indexViewsWithPrimaryKey(document);

    final Map<String, JoinEdge> fanOutByOneSideView = new HashMap<>();
    for (LookmlNode explore : document.children(LookmlKeywords.EXPLORE)) {
      final ExploreGraph graph = ExploreGraph.from(explore);
      classifyExplore(explore, graph, dimensionKeys, viewsWithPrimaryKey, out);
      graph.fanOutByOneSideView().forEach(fanOutByOneSideView::putIfAbsent);
    }

    // 2. Classify every view's fields, consulting the fan-out index.
    for (LookmlNode view : document.children(LookmlKeywords.VIEW)) {
      classifyView(view, fanOutByOneSideView, parameterNames, out);
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

  /** Collects every declared {@code parameter:} name (lower-cased) across all
   * views — the bounded set a {@code {% parameter X %}} use can select from. */
  private Set<String> indexParameterNames(LookmlNode document) {
    final Set<String> names = new HashSet<>();
    for (LookmlNode view : document.children(LookmlKeywords.VIEW)) {
      for (LookmlNode param : view.children(LookmlKeywords.PARAMETER)) {
        param.name().ifPresent(
            n -> names.add(n.toLowerCase(java.util.Locale.ROOT)));
      }
    }
    return names;
  }

  // --- explores ----------------------------------------------------------

  private void classifyExplore(LookmlNode explore, ExploreGraph graph,
      Set<String> dimensionKeys, Set<String> viewsWithPrimaryKey,
      ClassificationResult.Builder out) {
    final String qn = "explore:" + graph.exploreName();

    // The fact (base) view's grain key gates the bridge two-hop (#124).
    final boolean factHasPrimaryKey =
        viewsWithPrimaryKey.contains(graph.baseView());

    // Topology check (the first killer): refuse the whole explore.
    final Optional<JoinEdge> nonStar =
        graph.firstNonStarEdge(factHasPrimaryKey);
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
      // A star-eligible join whose key the transpiler cannot recover: its
      // conformed dimension is omitted, so record a DEGRADE note (#115). A join
      // that participates in a recognised bridge two-hop is resolved by the
      // bridge path (its key is recovered against the bridge view, not the
      // fact), so it is excluded from this fact-keyed check (#124).
      final Set<JoinEdge> bridged = factHasPrimaryKey
          ? bridgeEdges(graph) : java.util.Collections.emptySet();
      for (JoinEdge edge : graph.edges()) {
        if (!bridged.contains(edge) && !edge.hasResolvableKey()) {
          out.add(unparseableJoinRecord(graph.exploreName(), edge));
        }
      }
    }

    // aggregate_table blocks degrade independently of the explore outcome.
    for (LookmlNode agg : explore.children(LookmlKeywords.AGGREGATE_TABLE)) {
      out.add(aggregateTableRecord(graph.exploreName(), agg));
    }
  }

  /** Returns a DEGRADE record if any access_filter is on an arbitrary fact
   * column (mapped to a {@code <PredicateGrant>}, #106), else empty (a
   * dimension-key access_filter is CLEAN; the DimensionGrant case is #115). */
  private Optional<CoverageRecord> classifyAccessFilters(LookmlNode explore,
      String qn, Set<String> dimensionKeys) {
    for (LookmlNode af : explore.children(LookmlKeywords.ACCESS_FILTER)) {
      final Optional<String> field = af.stringValue(LookmlKeywords.FIELD);
      if (!isSimpleDimensionRef(field, dimensionKeys)) {
        return Optional.of(CoverageRecord.builder(Scope.EXPLORE, qn,
                ReasonCode.DEGRADE_PREDICATE_ROW_SECURITY,
                "explore `" + explore.name().orElse("?")
                    + "` has an access_filter on `" + field.orElse("?")
                    + "` (an arbitrary fact column, not a modelled dimension "
                    + "key); emitted as a <PredicateGrant> bound to a "
                    + "query-context parameter (#106)")
            .producedM4("PredicateGrant")
            .lostCapability("user-attribute value supplied at query time")
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

  /** The join edges (fact hop + dim hop) of every recognised bridge two-hop in
   * {@code graph} (#124), so the unparseable-join check can skip them. */
  private Set<JoinEdge> bridgeEdges(ExploreGraph graph) {
    final Set<JoinEdge> set = new HashSet<>();
    for (BridgePattern b : graph.bridges()) {
      set.add(b.factHop());
      set.add(b.dimHop());
    }
    return set;
  }

  /** The names of views that declare a {@code primary_key: yes} dimension — the
   * fact grain key a bridge two-hop de-duplicates on (#124). */
  private Set<String> indexViewsWithPrimaryKey(LookmlNode document) {
    final Set<String> names = new HashSet<>();
    for (LookmlNode view : document.children(LookmlKeywords.VIEW)) {
      final String viewName = view.name().orElse("");
      if (viewName.isEmpty()) {
        continue;
      }
      for (LookmlNode dim : view.children(LookmlKeywords.DIMENSION)) {
        final boolean isPk = dim.stringValue(LookmlKeywords.PRIMARY_KEY)
            .map(v -> v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("true"))
            .orElse(false);
        if (isPk) {
          names.add(viewName);
          break;
        }
      }
    }
    return names;
  }

  private String nonStarReason(ExploreGraph graph, JoinEdge edge) {
    return "explore `" + graph.exploreName() + "` join `" + edge.joinName()
        + "` (" + graph.nonStarCause(edge)
        + ") is not a star/snowflake fact→dim left-join; "
        + "it would break structurally";
  }

  /** A DEGRADE record for a join whose {@code sql_on} cannot be reduced to a
   * single fact/dimension key pair (#115). */
  private CoverageRecord unparseableJoinRecord(String exploreName,
      JoinEdge edge) {
    final String qn = "explore:" + exploreName + ".join:" + edge.joinName();
    return CoverageRecord.builder(Scope.EXPLORE, qn,
            ReasonCode.DEGRADE_JOIN_SQL_ON_UNPARSEABLE,
            "join `" + edge.joinName() + "` on explore `" + exploreName
                + "` has a sql_on that does not reduce to a single "
                + "fact/dimension key pair (multi-column or expression join); "
                + "its conformed dimension is omitted (#115)")
        .lostCapability("joined dimension `" + edge.joinedView()
            + "` not queryable")
        .build();
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
      Map<String, JoinEdge> fanOutByOneSideView, Set<String> parameterNames,
      ClassificationResult.Builder out) {
    final String viewName = view.name().orElse("?");

    classifyDerivedTable(view, viewName).ifPresent(out::add);

    final FieldClassifier fields = new FieldClassifier(viewName, parameterNames);
    final Optional<JoinEdge> fanOut =
        Optional.ofNullable(fanOutByOneSideView.get(viewName));
    final FieldClassifier.PrimaryKey primaryKey = primaryKeyOf(view);

    for (LookmlNode measure : view.children(LookmlKeywords.MEASURE)) {
      out.add(fields.classifyMeasure(measure, fanOut, primaryKey));
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

  /** The view's {@code primary_key: yes} dimension as a {@link
   * FieldClassifier.PrimaryKey} (its name and resolved column) — the fact grain
   * symmetric (fan-out-safe) aggregation needs (#103) and a {@code
   * sql_distinct_key} can de-duplicate on (#117). Absent if none declared. */
  private FieldClassifier.PrimaryKey primaryKeyOf(LookmlNode view) {
    for (LookmlNode dim : view.children(LookmlKeywords.DIMENSION)) {
      final boolean isPk = dim.stringValue(LookmlKeywords.PRIMARY_KEY)
          .map(v -> v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("true"))
          .orElse(false);
      if (isPk) {
        final String name = dim.name().orElse("");
        return FieldClassifier.PrimaryKey.of(name, primaryKeyColumn(dim, name));
      }
    }
    return FieldClassifier.PrimaryKey.none();
  }

  /** The column a primary-key dimension reads: its {@code ${TABLE}.col} sql
   * column else its own name. Mirrors the transpiler's column resolution
   * without depending on it (separate package). */
  private String primaryKeyColumn(LookmlNode dim, String dimName) {
    return DistinctKey.resolveSameView(
            dim.stringValue(LookmlKeywords.SQL).orElse(""))
        .orElse(dimName);
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
                + "that is dropped; emitted as a SQL-backed <Query> physical "
                + "table (the persistence policy maps to the segment-cache "
                + "story #94/#95/#96)")
        .producedM4("Query")
        .lostCapability("PDT persistence policy dropped")
        .build());
  }
}

// End LookmlClassifier.java
