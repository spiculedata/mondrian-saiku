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

import java.util.List;
import java.util.Optional;

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

  /** Returns the first edge that structurally breaks a star (full/cross outer
   * or unbridged many_to_many), if any. */
  Optional<JoinEdge> firstNonStarEdge() {
    for (JoinEdge e : edges) {
      if (e.isNonStarType() || e.isUnbridgedManyToMany()) {
        return Optional.of(e);
      }
    }
    return Optional.empty();
  }

  /** Returns the first {@code one_to_many} edge the explore fans out across,
   * if any. A measure on the base view fans out across such an edge. */
  Optional<JoinEdge> firstFanOutEdge() {
    for (JoinEdge e : edges) {
      if (LookmlKeywords.REL_ONE_TO_MANY.equals(e.relationship().orElse(null))) {
        return Optional.of(e);
      }
    }
    return Optional.empty();
  }
}

// End ExploreGraph.java
