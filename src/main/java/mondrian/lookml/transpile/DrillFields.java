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
import mondrian.lookml.parse.Value;
import mondrian.lookml.parse.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps a LookML {@code drill_fields: [a, b, c]} list to the M4 drillthrough
 * RETURN column set (#115).
 *
 * <p>The Mondrian-4 schema has no {@code <DrillThrough>} / {@code <Action>}
 * element — a default RETURN set is a Mondrian-3 schema feature dropped in M4,
 * where drillthrough is issued as a runtime {@code DRILLTHROUGH ... RETURN}
 * MDX statement. To preserve the LookML author's intent without fabricating an
 * engine element that does not exist (the safety gate), the field list is
 * carried losslessly as a cube-level {@code <Annotation name="drill_fields">},
 * which the M4 schema supports and round-trips, and which Saiku reads to build
 * the RETURN clause. Pure helper.
 */
final class DrillFields {
  private DrillFields() {}

  /** The annotation name the drill-field list is carried under. */
  static final String ANNOTATION_NAME = "drill_fields";

  /** The comma-joined drill-field set for an explore: its own
   * {@code drill_fields:} if present, else the base view's view-level
   * {@code drill_fields:}. Empty when neither declares one. */
  static Optional<String> forCube(LookmlNode explore, LookmlNode baseView) {
    final List<String> fields = read(explore);
    if (!fields.isEmpty()) {
      return Optional.of(String.join(",", fields));
    }
    if (baseView != null) {
      final List<String> viewFields = read(baseView);
      if (!viewFields.isEmpty()) {
        return Optional.of(String.join(",", viewFields));
      }
    }
    return Optional.empty();
  }

  /** Reads the {@code drill_fields:} list of a node as field-name strings,
   * trimming blanks. Empty when absent or not a list. */
  private static List<String> read(LookmlNode node) {
    final List<String> out = new ArrayList<>();
    final Optional<Value> raw = node.value(TranspileKeywords.DRILL_FIELDS);
    if (raw.isEmpty() || !(raw.get() instanceof Values.ListValue)) {
      return out;
    }
    for (Value e : ((Values.ListValue) raw.get()).list) {
      final String s = LookmlNode.asString(e).trim();
      if (!s.isEmpty()) {
        out.add(s);
      }
    }
    return out;
  }
}

// End DrillFields.java
