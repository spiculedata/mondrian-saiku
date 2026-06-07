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

import com.google.common.collect.ImmutableSet;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One edge of an explore's join graph, derived from a single {@code join:}
 * block. Immutable.
 *
 * <p>The edge connects the explore (the "fact" side) to a joined view. Its
 * cardinality is the LookML {@code relationship:} read from the perspective of
 * the fact: {@code many_to_one} / {@code one_to_one} are star-safe; {@code
 * one_to_many} fans the fact out; {@code many_to_many} needs a bridge.
 */
final class JoinEdge {
  /** {@code ${view.column}} references inside a {@code sql_on} block; group 1
   * is the referenced view name. */
  private static final Pattern VIEW_REF =
      Pattern.compile("\\$\\{\\s*([A-Za-z_]\\w*)\\.");

  /** A full {@code ${view.column}} reference: group 1 view, group 2 column. */
  private static final Pattern REF =
      Pattern.compile("\\$\\{\\s*([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)\\s*}");

  private final String joinName;
  private final String underlyingView;
  private final String type;
  private final String relationship;
  private final ImmutableSet<String> referencedViews;
  private final String sqlOn;
  private final boolean hasForeignKey;

  private JoinEdge(String joinName, String underlyingView, String type,
      String relationship, Set<String> referencedViews, String sqlOn,
      boolean hasForeignKey) {
    this.joinName = joinName;
    this.underlyingView = underlyingView;
    this.type = type;
    this.relationship = relationship;
    this.referencedViews = ImmutableSet.copyOf(referencedViews);
    this.sqlOn = sqlOn;
    this.hasForeignKey = hasForeignKey;
  }

  /**
   * Builds an edge from a {@code join:} node (#125).
   *
   * <p>Two distinct concepts are kept separate: the <em>join name</em>
   * ({@code joinNode.name()}) is the field namespace — every {@code sql_on}
   * {@code ${X.column}} reference to this join uses the join name, never the
   * {@code from:}/{@code view_name:} target. The <em>underlying view</em> is the
   * {@code from:} / {@code view_name:} target (else the join name) and is where
   * the joined dimensions' column definitions and physical {@code
   * sql_table_name} live. All {@code sql_on} ref-matching keys on the join name;
   * table/column resolution keys on the underlying view.
   */
  static JoinEdge from(LookmlNode joinNode) {
    final String name = joinNode.name().orElse("");
    final String underlying = joinNode.stringValue(LookmlKeywords.FROM)
        .or(() -> joinNode.stringValue(LookmlKeywords.VIEW_NAME))
        .orElse(name);
    final String type = joinNode.stringValue(LookmlKeywords.TYPE)
        .orElse(LookmlKeywords.JOIN_TYPE_LEFT_OUTER);
    final String relationship =
        joinNode.stringValue(LookmlKeywords.RELATIONSHIP).orElse(null);
    final String sqlOn = joinNode.stringValue(LookmlKeywords.SQL_ON).orElse("");
    // #125: refs to this join use the join NAME, so exclude the join name (not
    // the from:-target) when collecting the upstream side(s).
    final Set<String> referenced = parseReferencedViews(sqlOn, name);
    final boolean hasFk =
        joinNode.stringValue(LookmlKeywords.FOREIGN_KEY)
            .map(String::trim).filter(s -> !s.isEmpty()).isPresent();
    return new JoinEdge(name, underlying, type, relationship, referenced, sqlOn,
        hasFk);
  }

  /** The views (other than the join's own {@code joinName}) referenced by a
   * {@code sql_on} block's {@code ${view.column}} expressions: the upstream
   * side(s) the join attaches to. Empty when {@code sql_on} is absent (e.g. a
   * {@code cross} join) or only self-referential. In LookML a joined field is
   * referenced by the join name, so the join's own namespace excluded here is
   * the join name, not the {@code from:} target (#125). */
  private static Set<String> parseReferencedViews(String sqlOn,
      String joinName) {
    final Set<String> views = new LinkedHashSet<>();
    final Matcher m = VIEW_REF.matcher(sqlOn);
    while (m.find()) {
      final String v = m.group(1);
      if (!v.equals(joinName)) {
        views.add(v);
      }
    }
    return views;
  }

  String joinName() {
    return joinName;
  }

  /** The underlying physical view ({@code from:}/{@code view_name:} target, else
   * the join name) — where the joined dimensions' columns and {@code
   * sql_table_name} are defined (#125). NOT the namespace for {@code sql_on}
   * refs; use {@link #joinName()} for those. */
  String underlyingView() {
    return underlyingView;
  }

  String type() {
    return type;
  }

  Optional<String> relationship() {
    return Optional.ofNullable(relationship);
  }

  /** The upstream view(s) this join's {@code sql_on} references — the "one"
   * side when the relationship is {@code one_to_many}. */
  ImmutableSet<String> referencedViews() {
    return referencedViews;
  }

  /** This join fans the upstream side out (a {@code one_to_many}): the joined
   * view has many rows per upstream row. */
  boolean isOneToMany() {
    return LookmlKeywords.REL_ONE_TO_MANY.equals(relationship);
  }

  /** A join type that is not a fact-grain-preserving {@code left_outer}.
   *
   * <p>LookML's default join type is {@code left_outer} (so an unspecified
   * type is already normalised to it in {@link #from}). Only {@code left_outer}
   * keeps every fact row and attaches matching dim rows. {@code inner} silently
   * drops fact rows whose FK does not match; {@code right_outer} inverts the
   * grain; {@code full_outer} / {@code cross} break the star structurally. Any
   * of these would emit a silently-wrong cube, so all are non-star. */
  boolean isNonStarType() {
    return !LookmlKeywords.JOIN_TYPE_LEFT_OUTER.equals(type);
  }

  /** Whether this join's type is one of the explicitly-recognised
   * grain-changing types ({@code inner} / {@code right_outer} / {@code
   * full_outer} / {@code cross}). All are non-star; this only distinguishes a
   * recognised type from an unknown one for clearer reason text. */
  boolean isRecognisedNonStarType() {
    return LookmlKeywords.NON_STAR_JOIN_TYPES.contains(type);
  }

  /** A many_to_many relationship without a declared bridge: non-star. */
  boolean isUnbridgedManyToMany() {
    return LookmlKeywords.REL_MANY_TO_MANY.equals(relationship);
  }

  /** Whether the transpiler can recover a <em>single-column</em> fact/dimension
   * key pair for this join (#115/#125): a {@code foreign_key}, or a {@code
   * sql_on} that reduces to exactly one column on the join-name (dimension) side
   * <em>and</em> exactly one column on the other (fact/upstream) side.
   *
   * <p>This mirrors the transpiler's {@link JoinKeys} single-column gate: a
   * compound (AND-chained multi-column) or expression {@code sql_on} is NOT
   * resolvable, so it must DEGRADE rather than be silently reduced to one wrong
   * key (#125). When false the conformed dimension is omitted by the emitter, so
   * the classifier records a DEGRADE note rather than letting it vanish. */
  boolean hasResolvableKey() {
    if (hasForeignKey) {
      return true;
    }
    if (sqlOn.isEmpty()) {
      // No sql_on and no foreign_key: a cross/unconditioned join the emitter
      // cannot key. (Topology checks handle the structural cases.)
      return false;
    }
    String dimColumn = null;
    String factColumn = null;
    final Matcher m = REF.matcher(sqlOn);
    while (m.find()) {
      final String view = m.group(1);
      final String column = m.group(2);
      // #125: the dimension side is referenced by the join NAME, not the
      // from:-target underlying view.
      if (view.equals(joinName)) {
        if (dimColumn != null && !dimColumn.equals(column)) {
          return false; // compound key on the dimension side: not resolvable.
        }
        dimColumn = column;
      } else {
        if (factColumn != null && !factColumn.equals(column)) {
          return false; // compound key on the fact side: not resolvable.
        }
        factColumn = column;
      }
    }
    return dimColumn != null && factColumn != null;
  }

  /** The fact fans out across this edge (one_to_many or many_to_many). */
  boolean fansOut() {
    return LookmlKeywords.REL_ONE_TO_MANY.equals(relationship)
        || LookmlKeywords.REL_MANY_TO_MANY.equals(relationship);
  }

  // --- bridge two-hop key recovery (#124) --------------------------------

  /** Whether this edge's relationship is a fact→bridge hop (the fact fans out
   * across it: {@code one_to_many} or {@code many_to_many}). */
  boolean isBridgeFactHop() {
    return LookmlKeywords.BRIDGE_FACT_HOP_RELATIONSHIPS.contains(relationship);
  }

  /** Whether this edge's relationship is a bridge→dim hop (the bridge maps to
   * one dimension member: {@code many_to_one} or {@code one_to_one}). */
  boolean isBridgeDimHop() {
    return LookmlKeywords.BRIDGE_DIM_HOP_RELATIONSHIPS.contains(relationship);
  }

  /**
   * Recovers the single-column key pair this edge's {@code sql_on} equates
   * between {@code nearView} and this edge's own join namespace, if and only if
   * exactly one column is referenced on each side (the engine supports
   * single-column keys only, #107). The {@code nearColumn} is the column on
   * {@code nearView}; the {@code joinedColumn} is the column referenced via this
   * join's name (#125). Empty when the {@code sql_on} is absent, multi-column on
   * either side, or does not name both — so an ambiguous / compound join is
   * never mis-recovered.
   */
  Optional<KeyPair> singleColumnKeyPair(String nearView) {
    if (sqlOn.isEmpty()) {
      return Optional.empty();
    }
    String near = null;
    String joined = null;
    final Matcher m = REF.matcher(sqlOn);
    while (m.find()) {
      final String view = m.group(1);
      final String column = m.group(2);
      if (view.equals(nearView)) {
        if (near != null && !near.equals(column)) {
          return Optional.empty(); // compound key on the near side: refuse.
        }
        near = column;
      } else if (view.equals(joinName)) {
        if (joined != null && !joined.equals(column)) {
          return Optional.empty(); // compound key on the joined side: refuse.
        }
        joined = column;
      } else {
        // A third view in the predicate: not a clean two-view equality.
        return Optional.empty();
      }
    }
    if (near == null || joined == null) {
      return Optional.empty();
    }
    return Optional.of(new KeyPair(near, joined));
  }

  /** A single-column key pair recovered from a {@code sql_on}: the column on the
   * near (upstream) view and the column on the edge's joined view. Immutable. */
  static final class KeyPair {
    private final String nearColumn;
    private final String joinedColumn;

    KeyPair(String nearColumn, String joinedColumn) {
      this.nearColumn = nearColumn;
      this.joinedColumn = joinedColumn;
    }

    String nearColumn() {
      return nearColumn;
    }

    String joinedColumn() {
      return joinedColumn;
    }
  }
}

// End JoinEdge.java
