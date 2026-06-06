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

import mondrian.lookml.parse.LookmlNode;

import com.google.common.collect.ImmutableList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The join graph of a single explore: its base view plus one {@link JoinEdge}
 * per {@code join:} block. Immutable.
 *
 * <p>Built once per explore and consulted by the topology check (is the graph a
 * star/snowflake?) and the fan-out check (does an additive measure sit on the
 * "one" side of a {@code one_to_many} the explore fans out?).
 */
final class ExploreGraph {
  private final String exploreName;
  private final String baseView;
  private final ImmutableList<JoinEdge> edges;

  private ExploreGraph(String exploreName, String baseView,
      List<JoinEdge> edges) {
    this.exploreName = exploreName;
    this.baseView = baseView;
    this.edges = ImmutableList.copyOf(edges);
  }

  /** Builds the graph from an {@code explore:} node. The base view defaults to
   * the explore name, overridable by {@code from:} / {@code view_name:}. */
  static ExploreGraph from(LookmlNode exploreNode) {
    final String name = exploreNode.name().orElse("");
    final String base = exploreNode.stringValue(LookmlKeywords.FROM)
        .or(() -> exploreNode.stringValue(LookmlKeywords.VIEW_NAME))
        .orElse(name);
    final ImmutableList.Builder<JoinEdge> b = ImmutableList.builder();
    for (LookmlNode join : exploreNode.children(LookmlKeywords.JOIN)) {
      b.add(JoinEdge.from(join));
    }
    return new ExploreGraph(name, base, b.build());
  }

  String exploreName() {
    return exploreName;
  }

  String baseView() {
    return baseView;
  }

  ImmutableList<JoinEdge> edges() {
    return edges;
  }

  /** Returns the first edge that structurally breaks a star, if any: a
   * non-{@code left_outer} type, an unbridged {@code many_to_many}, or a
   * chained-many topology (a {@code one_to_many} reached through another
   * {@code one_to_many}, which multiplies the fact grain twice). */
  Optional<JoinEdge> firstNonStarEdge() {
    for (JoinEdge e : edges) {
      if (e.isNonStarType() || e.isUnbridgedManyToMany() || isChainedMany(e)) {
        return Optional.of(e);
      }
    }
    return Optional.empty();
  }

  /** A human-readable cause for why {@code edge} broke the star, for the
   * REFUSE reason text. */
  String nonStarCause(JoinEdge edge) {
    if (edge.isNonStarType()) {
      return edge.isRecognisedNonStarType()
          ? edge.type() + " join"
          : "non-left_outer join `" + edge.type() + "`";
    }
    if (edge.isUnbridgedManyToMany()) {
      return "unbridged many_to_many";
    }
    if (isChainedMany(edge)) {
      return "chained one_to_many";
    }
    return edge.type();
  }

  /** Whether {@code edge} is a {@code one_to_many} whose upstream ("one"-side)
   * view is itself the joined ("many"-side) view of another {@code
   * one_to_many}. Such an intermediate view fans out on both sides, so the
   * explore multiplies the fact grain more than once — non-star. */
  private boolean isChainedMany(JoinEdge edge) {
    if (!edge.isOneToMany()) {
      return false;
    }
    final Set<String> manySideViews = new HashSet<>();
    for (JoinEdge other : edges) {
      if (other != edge && other.isOneToMany()) {
        manySideViews.add(other.joinedView());
      }
    }
    for (String upstream : edge.referencedViews()) {
      if (manySideViews.contains(upstream)) {
        return true;
      }
    }
    return false;
  }

  /** Maps every view on the "one" side of a {@code one_to_many} join to that
   * fan-out edge: an additive measure on such a view fans out across the edge.
   *
   * <p>The "one" side of a {@code one_to_many} is the upstream view(s) its
   * {@code sql_on} references; absent a parseable {@code sql_on}, the explore's
   * base view is assumed (the common base→leaf fan-out). This generalises the
   * old base-only check to snowflaked / joined views that also sit on a "one"
   * side. */
  Map<String, JoinEdge> fanOutByOneSideView() {
    final Map<String, JoinEdge> byView = new HashMap<>();
    for (JoinEdge e : edges) {
      if (!e.isOneToMany()) {
        continue;
      }
      final Set<String> oneSide = e.referencedViews().isEmpty()
          ? Set.of(baseView)
          : e.referencedViews();
      for (String view : oneSide) {
        byView.putIfAbsent(view, e);
      }
    }
    return byView;
  }
}

// End ExploreGraph.java
