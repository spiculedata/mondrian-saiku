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
  private final String joinedView;
  private final String type;
  private final String relationship;
  private final ImmutableSet<String> referencedViews;
  private final String sqlOn;
  private final boolean hasForeignKey;

  private JoinEdge(String joinName, String joinedView, String type,
      String relationship, Set<String> referencedViews, String sqlOn,
      boolean hasForeignKey) {
    this.joinName = joinName;
    this.joinedView = joinedView;
    this.type = type;
    this.relationship = relationship;
    this.referencedViews = ImmutableSet.copyOf(referencedViews);
    this.sqlOn = sqlOn;
    this.hasForeignKey = hasForeignKey;
  }

  /** Builds an edge from a {@code join:} node. The joined view defaults to the
   * join name, overridable by {@code from:} / {@code view_name:}. */
  static JoinEdge from(LookmlNode joinNode) {
    final String name = joinNode.name().orElse("");
    final String view = joinNode.stringValue(LookmlKeywords.FROM)
        .or(() -> joinNode.stringValue(LookmlKeywords.VIEW_NAME))
        .orElse(name);
    final String type = joinNode.stringValue(LookmlKeywords.TYPE)
        .orElse(LookmlKeywords.JOIN_TYPE_LEFT_OUTER);
    final String relationship =
        joinNode.stringValue(LookmlKeywords.RELATIONSHIP).orElse(null);
    final String sqlOn = joinNode.stringValue(LookmlKeywords.SQL_ON).orElse("");
    final Set<String> referenced = parseReferencedViews(sqlOn, view);
    final boolean hasFk =
        joinNode.stringValue(LookmlKeywords.FOREIGN_KEY)
            .map(String::trim).filter(s -> !s.isEmpty()).isPresent();
    return new JoinEdge(name, view, type, relationship, referenced, sqlOn,
        hasFk);
  }

  /** The views (other than the join's own {@code joinedView}) referenced by a
   * {@code sql_on} block's {@code ${view.column}} expressions: the upstream
   * side(s) the join attaches to. Empty when {@code sql_on} is absent (e.g. a
   * {@code cross} join) or only self-referential. */
  private static Set<String> parseReferencedViews(String sqlOn,
      String joinedView) {
    final Set<String> views = new LinkedHashSet<>();
    final Matcher m = VIEW_REF.matcher(sqlOn);
    while (m.find()) {
      final String v = m.group(1);
      if (!v.equals(joinedView)) {
        views.add(v);
      }
    }
    return views;
  }

  String joinName() {
    return joinName;
  }

  String joinedView() {
    return joinedView;
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

  /** Whether the transpiler can recover a single fact/dimension key pair for
   * this join (#115): a {@code foreign_key}, or a {@code sql_on} with a column
   * on the joined view <em>and</em> a column on some other (fact/upstream) view.
   * When false the conformed dimension is omitted by the emitter, so the
   * classifier records a DEGRADE note rather than letting it vanish silently. */
  boolean hasResolvableKey() {
    if (hasForeignKey) {
      return true;
    }
    if (sqlOn.isEmpty()) {
      // No sql_on and no foreign_key: a cross/unconditioned join the emitter
      // cannot key. (Topology checks handle the structural cases.)
      return false;
    }
    boolean dimSide = false;
    boolean factSide = false;
    final Matcher m = REF.matcher(sqlOn);
    while (m.find()) {
      if (m.group(1).equals(joinedView)) {
        dimSide = true;
      } else {
        factSide = true;
      }
    }
    return dimSide && factSide;
  }

  /** The fact fans out across this edge (one_to_many or many_to_many). */
  boolean fansOut() {
    return LookmlKeywords.REL_ONE_TO_MANY.equals(relationship)
        || LookmlKeywords.REL_MANY_TO_MANY.equals(relationship);
  }
}

// End JoinEdge.java
