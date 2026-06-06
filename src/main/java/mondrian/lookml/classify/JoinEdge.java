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

import java.util.Optional;

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
  private final String joinName;
  private final String joinedView;
  private final String type;
  private final String relationship;

  private JoinEdge(String joinName, String joinedView, String type,
      String relationship) {
    this.joinName = joinName;
    this.joinedView = joinedView;
    this.type = type;
    this.relationship = relationship;
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
    return new JoinEdge(name, view, type, relationship);
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

  /** A join type that structurally breaks a star (full/cross outer). */
  boolean isNonStarType() {
    return LookmlKeywords.JOIN_TYPE_FULL_OUTER.equals(type)
        || LookmlKeywords.JOIN_TYPE_CROSS.equals(type);
  }

  /** A many_to_many relationship without a declared bridge: non-star. */
  boolean isUnbridgedManyToMany() {
    return LookmlKeywords.REL_MANY_TO_MANY.equals(relationship);
  }

  /** The fact fans out across this edge (one_to_many or many_to_many). */
  boolean fansOut() {
    return LookmlKeywords.REL_ONE_TO_MANY.equals(relationship)
        || LookmlKeywords.REL_MANY_TO_MANY.equals(relationship);
  }
}

// End JoinEdge.java
