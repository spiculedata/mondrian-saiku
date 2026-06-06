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
import java.util.regex.Pattern;

/**
 * Resolves a LookML {@code sql_distinct_key} (on a {@code sum_distinct} /
 * {@code average_distinct} measure) to a bare, same-view column name when it is
 * a simple field reference, so the classifier and transpiler can decide whether
 * it de-duplicates on the base view's grain (#117).
 *
 * <p>A distinct key is "resolvable to the fact grain" only when it is a simple
 * reference to a column in the measure's own view — {@code ${field}},
 * {@code ${TABLE}.col}, or a bare {@code col}. A cross-view reference
 * ({@code ${other_view.field}}) is <em>not</em> resolvable: the de-dup grain
 * would be a foreign key, which the engine cannot honour at measure level (the
 * #103 symmetric path de-duplicates on the fact primary key only). Anything
 * with SQL syntax (operators, concatenation) is likewise unresolvable.
 */
final class DistinctKey {
  private DistinctKey() {}

  /** {@code ${TABLE}.} prefix LookML uses for own-table column refs. */
  private static final String TABLE_REF = "${TABLE}.";

  /** A simple identifier: a bare column or unqualified field name. */
  private static final Pattern SIMPLE = Pattern.compile("[A-Za-z_][\\w]*");

  /**
   * Returns the bare same-view column/field name a {@code sql_distinct_key}
   * references, or empty when the key is absent, cross-view, or not a simple
   * single-column reference.
   */
  static Optional<String> resolveSameView(String rawDistinctKey) {
    if (rawDistinctKey == null) {
      return Optional.empty();
    }
    String s = rawDistinctKey.trim();
    if (s.isEmpty()) {
      return Optional.empty();
    }
    // ${TABLE}.col → col
    if (s.startsWith(TABLE_REF)) {
      s = s.substring(TABLE_REF.length()).trim();
      return SIMPLE.matcher(s).matches() ? Optional.of(s) : Optional.empty();
    }
    // ${field} or ${view.field}
    if (s.startsWith("${") && s.endsWith("}")) {
      final String inner = s.substring(2, s.length() - 1).trim();
      if (inner.contains(".")) {
        // Cross-view reference: not the measure's own grain.
        return Optional.empty();
      }
      return SIMPLE.matcher(inner).matches()
          ? Optional.of(inner) : Optional.empty();
    }
    // A bare column reference (no ${} wrapper).
    return SIMPLE.matcher(s).matches() ? Optional.of(s) : Optional.empty();
  }
}

// End DistinctKey.java
