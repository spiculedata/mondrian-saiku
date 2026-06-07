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

import java.util.Optional;

/**
 * Recognises the canonical LookML many-to-many <em>bridge</em> two-hop within an
 * explore's join graph and recovers its four bridge columns, so the importer can
 * map it to a Mondrian {@code <BridgeLink>} (#107) instead of refusing the
 * explore as non-star (#124). Immutable, pure.
 *
 * <p>The shape is {@code fact --(one_to_many|many_to_many)--> bridge
 * --(many_to_one|one_to_one)--> dim}: the fact fans out across the bridge view
 * (hop 1), and the bridge maps each fact row to a dimension member (hop 2). The
 * recovered columns are
 * <ul>
 *   <li>{@code bridgeView} — the bridge view (its table is the bridge table);</li>
 *   <li>{@code dimView} — the dimension view (a normal conformed dimension keyed
 *       on {@code dimKeyColumn});</li>
 *   <li>{@code factForeignKeyColumn} / {@code bridgeFactKeyColumn} — from hop 1's
 *       {@code sql_on} ({@code ${fact.X} = ${bridge.Y}} → fc=X, bfc=Y);</li>
 *   <li>{@code bridgeDimensionKeyColumn} — the bridge column in hop 2's
 *       {@code sql_on} ({@code ${bridge.Z} = ${dim.K}} → bdc=Z, dimKey=K).</li>
 * </ul>
 *
 * <p>Hard gate (correctness, #124): every hop must reduce to a single-column
 * key on each side ({@link JoinEdge#singleColumnKeyPair}). A compound / ambiguous
 * / unparseable {@code sql_on} yields no pattern, so the explore stays REFUSED
 * with its precise diagnostic — a missed conversion is acceptable, a wrong
 * bridge is not.
 */
final class BridgePattern {
  private final JoinEdge factHop;
  private final JoinEdge dimHop;
  private final String bridgeView;
  private final String dimView;
  private final String factForeignKeyColumn;
  private final String bridgeFactKeyColumn;
  private final String bridgeDimensionKeyColumn;
  private final String dimKeyColumn;

  private BridgePattern(JoinEdge factHop, JoinEdge dimHop, String bridgeView,
      String dimView, String factForeignKeyColumn, String bridgeFactKeyColumn,
      String bridgeDimensionKeyColumn, String dimKeyColumn) {
    this.factHop = factHop;
    this.dimHop = dimHop;
    this.bridgeView = bridgeView;
    this.dimView = dimView;
    this.factForeignKeyColumn = factForeignKeyColumn;
    this.bridgeFactKeyColumn = bridgeFactKeyColumn;
    this.bridgeDimensionKeyColumn = bridgeDimensionKeyColumn;
    this.dimKeyColumn = dimKeyColumn;
  }

  /** The fact→bridge edge (hop 1). */
  JoinEdge factHop() {
    return factHop;
  }

  /** The bridge→dim edge (hop 2). */
  JoinEdge dimHop() {
    return dimHop;
  }

  /** The bridge view (its physical table is the {@code bridge_table}). */
  String bridgeView() {
    return bridgeView;
  }

  /** The dimension view reached through the bridge. */
  String dimView() {
    return dimView;
  }

  String factForeignKeyColumn() {
    return factForeignKeyColumn;
  }

  String bridgeFactKeyColumn() {
    return bridgeFactKeyColumn;
  }

  String bridgeDimensionKeyColumn() {
    return bridgeDimensionKeyColumn;
  }

  /** The dimension-side key column hop 2 joins to (the dimension's key). */
  String dimKeyColumn() {
    return dimKeyColumn;
  }

  /**
   * Recognises {@code factHop} as the fact→bridge hop of a bridge two-hop within
   * {@code graph}, recovering the full pattern, or empty if it is not one. The
   * fact hop's joined view becomes the bridge; some other edge whose
   * {@code sql_on} references that bridge view, on the bridge→dim side, becomes
   * hop 2. Both hops must yield single-column keys.
   */
  static Optional<BridgePattern> recognise(ExploreGraph graph, JoinEdge factHop) {
    if (!factHop.isBridgeFactHop()) {
      return Optional.empty();
    }
    final String baseView = graph.baseView();
    // #125: hop 2's sql_on references the fact hop by its join NAME (the field
    // namespace), while the physical bridge TABLE comes from the underlying
    // view (from:-target). Keep the two separate.
    final String bridgeName = factHop.joinName();
    final String bridgeView = factHop.underlyingView();
    // Hop 1 must equate the fact (base) to the bridge with single columns.
    final Optional<JoinEdge.KeyPair> hop1 =
        factHop.singleColumnKeyPair(baseView);
    if (hop1.isEmpty()) {
      return Optional.empty();
    }
    // Hop 2: the dim hop is an edge that references the bridge by its join name
    // on its upstream side and maps to one dimension member.
    for (JoinEdge dimHop : graph.edges()) {
      if (dimHop == factHop || !dimHop.isBridgeDimHop()
          || !dimHop.referencedViews().contains(bridgeName)) {
        continue;
      }
      final Optional<JoinEdge.KeyPair> hop2 =
          dimHop.singleColumnKeyPair(bridgeName);
      if (hop2.isEmpty()) {
        continue;
      }
      return Optional.of(new BridgePattern(factHop, dimHop, bridgeView,
          dimHop.underlyingView(),
          hop1.get().nearColumn(),     // fact column            → fc
          hop1.get().joinedColumn(),   // bridge column (fact side)  → bfc
          hop2.get().nearColumn(),     // bridge column (dim side)   → bdc
          hop2.get().joinedColumn())); // dim key column         → dimKey
    }
    return Optional.empty();
  }
}

// End BridgePattern.java
