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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fact-side foreign-key column and dimension-side key column of a single
 * {@code join:} block, recovered from its {@code sql_on} (or {@code
 * foreign_key}). Immutable, pure: this is the only place that parses join SQL.
 *
 * <p>A v1 star join is a single equality {@code ${fact.fk} = ${dim.key}}; the
 * fact reference resolves to the explore's base view and the dim reference to
 * the joined view.
 */
final class JoinKeys {
  /** One {@code ${view.column}} reference inside a sql_on code block. */
  private static final Pattern REF =
      Pattern.compile("\\$\\{\\s*([A-Za-z_][\\w]*)\\.([A-Za-z_][\\w]*)\\s*}");

  private final String factForeignKeyColumn;
  private final String dimensionKeyColumn;

  private JoinKeys(String factForeignKeyColumn, String dimensionKeyColumn) {
    this.factForeignKeyColumn = factForeignKeyColumn;
    this.dimensionKeyColumn = dimensionKeyColumn;
  }

  /** The fact-side FK column (e.g. {@code user_id} in orders). */
  String factForeignKeyColumn() {
    return factForeignKeyColumn;
  }

  /** The dimension-side key column (e.g. {@code user_id} in users). */
  String dimensionKeyColumn() {
    return dimensionKeyColumn;
  }

  /**
   * Recovers the join keys for {@code join}, attached to {@code baseView}.
   * The dimension side of the {@code sql_on} is referenced by the join's
   * <em>name</em> ({@code dimNamespace}), never its {@code from:}/{@code
   * view_name:} target (#125) — those only swap the underlying physical view.
   * Prefers {@code sql_on}; falls back to {@code foreign_key} (a bare fact
   * column, dim key assumed the same name). Empty if neither yields a usable
   * single-column key.
   */
  static Optional<JoinKeys> from(LookmlNode join, String baseView,
      String dimNamespace) {
    final Optional<JoinKeys> fromSqlOn =
        fromSqlOn(join, baseView, dimNamespace);
    if (fromSqlOn.isPresent()) {
      return fromSqlOn;
    }
    return join.stringValue(TranspileKeywords.FOREIGN_KEY)
        .map(String::trim)
        .filter(fk -> !fk.isEmpty())
        .map(fk -> new JoinKeys(fk, fk));
  }

  private static Optional<JoinKeys> fromSqlOn(LookmlNode join, String baseView,
      String dimNamespace) {
    final Optional<String> sqlOn = join.stringValue(TranspileKeywords.SQL_ON);
    if (sqlOn.isEmpty()) {
      return Optional.empty();
    }
    String fact = null;
    String dim = null;
    final Matcher m = REF.matcher(sqlOn.get());
    while (m.find()) {
      final String view = m.group(1);
      final String column = m.group(2);
      // #125: the dimension side is referenced by the join name, not the
      // from:-target. Check it first so a join whose name equals the base view
      // is impossible here (names are unique within an explore's namespace).
      if (view.equals(dimNamespace)) {
        if (dim != null && !dim.equals(column)) {
          return Optional.empty(); // compound dim key: refuse (never reduce to
                                   // a single wrong key, #125).
        }
        dim = column;
      } else if (view.equals(baseView)) {
        if (fact != null && !fact.equals(column)) {
          return Optional.empty(); // compound fact key: refuse (#125).
        }
        fact = column;
      }
    }
    if (fact == null || dim == null) {
      return Optional.empty();
    }
    return Optional.of(new JoinKeys(fact, dim));
  }
}

// End JoinKeys.java
