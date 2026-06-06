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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a LookML {@code parameter:} declaration to a top-level M4
 * {@code <QueryParameter>} (#105): its type, default value and the closed
 * {@code allowed_value} enumeration. Pure helper.
 *
 * <p>Only the bounded <em>declaration</em> maps here; a parameter's <em>use</em>
 * inside {@code {% parameter %}} Liquid field-switching SQL is refused upstream
 * as Liquid and never reaches the transpiler.
 */
final class QueryParameters {
  private QueryParameters() {}

  /** LookML parameter {@code type} &rarr; M4 QueryParameter type. */
  private static final Map<String, String> TYPE_BY_LOOKML = Map.of(
      "number", "Numeric",
      "string", "String",
      "unquoted", "String",
      "yesno", "String",
      "date", "String");

  /** Builds the M4 {@code <QueryParameter>} map for a LookML parameter, or
   * empty if it has no usable name. */
  static Optional<Map<String, Object>> build(LookmlNode parameter) {
    final String name = parameter.name().orElse("");
    if (name.isEmpty()) {
      return Optional.empty();
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", name);
    out.put("type", type(parameter));
    parameter.stringValue(TranspileKeywords.DEFAULT_VALUE)
        .map(String::trim)
        .filter(d -> !d.isEmpty())
        .ifPresent(d -> out.put("default_value", d));
    final List<Object> allowed = allowedValues(parameter);
    if (!allowed.isEmpty()) {
      out.put("allowed_values", allowed);
    }
    parameter.stringValue(TranspileKeywords.DESCRIPTION)
        .ifPresent(d -> out.put("description", d));
    return Optional.of(out);
  }

  private static String type(LookmlNode parameter) {
    final String lookmlType = parameter.stringValue(TranspileKeywords.TYPE)
        .map(s -> s.toLowerCase(Locale.ROOT))
        .orElse("string");
    return TYPE_BY_LOOKML.getOrDefault(lookmlType, "String");
  }

  /** The closed enumeration from {@code allowed_value: { value: "X" }} blocks;
   * each contributes its {@code value} (the machine value the SQL binds). */
  private static List<Object> allowedValues(LookmlNode parameter) {
    final List<Object> out = new ArrayList<>();
    for (LookmlNode av
        : parameter.children(TranspileKeywords.ALLOWED_VALUE)) {
      av.stringValue(TranspileKeywords.VALUE)
          .filter(v -> !v.trim().isEmpty())
          .ifPresent(out::add);
    }
    return out;
  }
}

// End QueryParameters.java
