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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers the LookML many-to-many <em>bridge</em> two-hops of an explore and
 * their four bridge columns, so the emitter can map each to a Mondrian
 * {@code <BridgeLink>} (#107) instead of a foreign-key conformed dimension
 * (#124). Immutable, pure — the transpile-side mirror of the classifier's
 * {@code BridgePattern} (kept package-local to keep the two packages decoupled).
 *
 * <p>The shape is {@code fact --(one_to_many|many_to_many)--> bridge
 * --(many_to_one|one_to_one)--> dim}. Both hops must reduce to a single-column
 * key on each side (the engine supports single-column bridge keys only); a
 * compound / ambiguous {@code sql_on} yields no bridge, so the explore keeps its
 * REFUSE — a missed conversion is acceptable, a wrong bridge is not.
 */
final class BridgeJoins {
  /** One {@code ${view.column}} reference inside a sql_on. */
  private static final Pattern REF =
      Pattern.compile("\\$\\{\\s*([A-Za-z_][\\w]*)\\.([A-Za-z_][\\w]*)\\s*}");

  private final LookmlNode dimJoin;
  private final String bridgeName;
  private final String dimName;
  private final String dimView;
  private final String bridgeTable;
  private final String factForeignKeyColumn;
  private final String bridgeFactKeyColumn;
  private final String bridgeDimensionKeyColumn;
  private final String dimKeyColumn;

  private BridgeJoins(LookmlNode dimJoin, String bridgeName, String dimName,
      String dimView, String bridgeTable, String factForeignKeyColumn,
      String bridgeFactKeyColumn, String bridgeDimensionKeyColumn,
      String dimKeyColumn) {
    this.dimJoin = dimJoin;
    this.bridgeName = bridgeName;
    this.dimName = dimName;
    this.dimView = dimView;
    this.bridgeTable = bridgeTable;
    this.factForeignKeyColumn = factForeignKeyColumn;
    this.bridgeFactKeyColumn = bridgeFactKeyColumn;
    this.bridgeDimensionKeyColumn = bridgeDimensionKeyColumn;
    this.dimKeyColumn = dimKeyColumn;
  }

  /** The bridge→dim join (hop 2); its underlying view is the conformed
   * dimension's column source. */
  LookmlNode dimJoin() {
    return dimJoin;
  }

  /** The fact→bridge hop's join NAME — the field namespace hop 2 references and
   * the partition key that excludes the bridge from the normal join split
   * (#125). */
  String bridgeName() {
    return bridgeName;
  }

  /** The conformed dimension's NAME (the dim hop's join name) — distinct per
   * alias, the partition exclusion key, and the emitted dimension name (#125). */
  String dimName() {
    return dimName;
  }

  /** The dim hop's underlying view — where the bridged dimension's columns and
   * {@code sql_table_name} are defined (#125). */
  String dimView() {
    return dimView;
  }

  String bridgeTable() {
    return bridgeTable;
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

  String dimKeyColumn() {
    return dimKeyColumn;
  }

  /** Recovers every bridge two-hop in {@code explore} (base {@code baseView}),
   * resolving bridge tables through {@code tableResolver} (view name → table). */
  static List<BridgeJoins> recover(LookmlNode explore, String baseView,
      java.util.function.Function<String, String> tableResolver) {
    final List<BridgeJoins> out = new ArrayList<>();
    final List<LookmlNode> joins = LookmlTranspiler.joins(explore);
    for (LookmlNode factJoin : joins) {
      if (!isBridgeFactHop(factJoin)) {
        continue;
      }
      // #125: hops reference one another by join NAME (the field namespace);
      // the bridge TABLE is resolved from the fact hop's underlying view.
      final String bridgeName = LookmlTranspiler.joinName(factJoin);
      final String bridgeView = LookmlTranspiler.joinedView(factJoin);
      final Optional<KeyPair> hop1 = keyPair(factJoin, baseView, bridgeName);
      if (hop1.isEmpty()) {
        continue;
      }
      for (LookmlNode dimJoin : joins) {
        if (dimJoin == factJoin || !isBridgeDimHop(dimJoin)) {
          continue;
        }
        final String dimName = LookmlTranspiler.joinName(dimJoin);
        final String dimView = LookmlTranspiler.joinedView(dimJoin);
        final Optional<KeyPair> hop2 = keyPair(dimJoin, bridgeName, dimName);
        if (hop2.isEmpty()) {
          continue;
        }
        out.add(new BridgeJoins(dimJoin, bridgeName, dimName, dimView,
            tableResolver.apply(bridgeView),
            hop1.get().near,    // fact column                → fc
            hop1.get().far,     // bridge column (fact side)  → bfc
            hop2.get().near,    // bridge column (dim side)   → bdc
            hop2.get().far));   // dim key column             → dimKey
        break;
      }
    }
    return out;
  }

  private static boolean isBridgeFactHop(LookmlNode join) {
    final String rel = relationship(join);
    return TranspileKeywords.REL_ONE_TO_MANY.equals(rel)
        || TranspileKeywords.REL_MANY_TO_MANY.equals(rel);
  }

  private static boolean isBridgeDimHop(LookmlNode join) {
    final String rel = relationship(join);
    return TranspileKeywords.REL_MANY_TO_ONE.equals(rel)
        || TranspileKeywords.REL_ONE_TO_ONE.equals(rel);
  }

  private static String relationship(LookmlNode join) {
    return join.stringValue(TranspileKeywords.RELATIONSHIP)
        .map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
  }

  /** The single-column key pair {@code join}'s {@code sql_on} equates between
   * {@code nearView} and {@code farView} ({@code far} = {@code joinedView}),
   * else empty when absent, compound on either side, or naming a third view. */
  private static Optional<KeyPair> keyPair(LookmlNode join, String nearView,
      String farView) {
    final Optional<String> sqlOn = join.stringValue(TranspileKeywords.SQL_ON);
    if (sqlOn.isEmpty()) {
      return Optional.empty();
    }
    String near = null;
    String far = null;
    final Matcher m = REF.matcher(sqlOn.get());
    while (m.find()) {
      final String view = m.group(1);
      final String column = m.group(2);
      if (view.equals(nearView)) {
        if (near != null && !near.equals(column)) {
          return Optional.empty();
        }
        near = column;
      } else if (view.equals(farView)) {
        if (far != null && !far.equals(column)) {
          return Optional.empty();
        }
        far = column;
      } else {
        return Optional.empty();
      }
    }
    if (near == null || far == null) {
      return Optional.empty();
    }
    return Optional.of(new KeyPair(near, far));
  }

  private static final class KeyPair {
    private final String near;
    private final String far;

    KeyPair(String near, String far) {
      this.near = near;
      this.far = far;
    }
  }
}

// End BridgeJoins.java
