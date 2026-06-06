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
import java.util.Set;

/**
 * Maps a LookML {@code access_filter} on an arbitrary fact <em>column</em> to
 * M4 predicate-based row security (#106): a generated {@code <QueryParameter>}
 * (the user attribute, supplied at query time) and a generated {@code <Role>}
 * whose {@code <CubeGrant>} carries a {@code <PredicateGrant>} binding the
 * column to that parameter with an {@code in} operator. Pure helper.
 *
 * <p>An access_filter on a modelled dimension key (the DimensionGrant case,
 * #115) is handled elsewhere; this helper only fires for the arbitrary-column
 * predicate the importer previously refused.
 */
final class RowSecurity {
  private RowSecurity() {}

  /** Suffix for the generated role name, e.g. {@code orders_row_security}. */
  private static final String ROLE_SUFFIX = "_row_security";
  /** The membership operator the predicate uses (IN a set of allowed values). */
  private static final String OPERATOR_IN = "in";

  /** Emits a query parameter + role for each arbitrary-column access_filter on
   * the explore, into the shared model. {@code dimensionNames} (lower-cased)
   * are the modelled dimensions to skip — a dimension-key access_filter is the
   * DimensionGrant case (#115), not predicate row security. */
  static void emit(LookmlNode explore, String cubeName, String measureGroup,
      Set<String> dimensionNames, M4SchemaModel model,
      ProvenanceMap.Builder provenance) {
    final List<Map<String, Object>> predicateGrants = new ArrayList<>();
    for (LookmlNode af : explore.children(TranspileKeywords.ACCESS_FILTER)) {
      grant(af, measureGroup, dimensionNames, model)
          .ifPresent(predicateGrants::add);
    }
    if (predicateGrants.isEmpty()) {
      return;
    }
    final String roleName = cubeName + ROLE_SUFFIX;
    model.addRole(roleName, role(roleName, cubeName, predicateGrants));
    provenance.put("explore:" + cubeName + ".access_filter",
        "role:" + roleName);
  }

  /** Builds one predicate grant (and its backing parameter), or empty if the
   * filter lacks a usable column. */
  private static Optional<Map<String, Object>> grant(LookmlNode accessFilter,
      String measureGroup, Set<String> dimensionNames, M4SchemaModel model) {
    final Optional<String> column = column(accessFilter);
    if (column.isEmpty()) {
      return Optional.empty();
    }
    final String col = column.get();
    // A dimension-key access_filter is the DimensionGrant case (#115); skip it.
    if (dimensionNames.contains(col)) {
      return Optional.empty();
    }
    final String parameter = parameterName(accessFilter, col);

    if (!model.hasParameter(parameter)) {
      model.addParameter(parameter, parameter(parameter));
    }

    final Map<String, Object> pg = new LinkedHashMap<>();
    pg.put("measure_group", measureGroup);
    pg.put("column", col);
    pg.put("operator", OPERATOR_IN);
    pg.put("parameter", parameter);
    return Optional.of(pg);
  }

  /** The fact column the predicate filters on: the leaf of the access_filter
   * {@code field:} reference ({@code view.column} or {@code column}). */
  private static Optional<String> column(LookmlNode accessFilter) {
    return accessFilter.stringValue(TranspileKeywords.FIELD)
        .map(String::trim)
        .filter(f -> !f.isEmpty())
        .map(RowSecurity::leaf);
  }

  /** The parameter bound to the predicate: the LookML {@code user_attribute}
   * if present, else the column name. */
  private static String parameterName(LookmlNode accessFilter, String column) {
    return accessFilter.stringValue(TranspileKeywords.USER_ATTRIBUTE)
        .map(String::trim)
        .filter(a -> !a.isEmpty())
        .orElse(column);
  }

  private static Map<String, Object> parameter(String name) {
    final Map<String, Object> p = new LinkedHashMap<>();
    p.put("name", name);
    p.put("type", "String");
    return p;
  }

  private static Map<String, Object> role(String roleName, String cubeName,
      List<Map<String, Object>> predicateGrants) {
    final Map<String, Object> cubeGrant = new LinkedHashMap<>();
    cubeGrant.put("cube", cubeName);
    cubeGrant.put("access", "all");
    cubeGrant.put("predicate_grants", new ArrayList<Object>(predicateGrants));

    final List<Object> cubeGrants = new ArrayList<>();
    cubeGrants.add(cubeGrant);

    final Map<String, Object> schemaGrant = new LinkedHashMap<>();
    schemaGrant.put("access", "all");
    schemaGrant.put("cubes", cubeGrants);

    final Map<String, Object> role = new LinkedHashMap<>();
    role.put("name", roleName);
    role.put("schema_grant", schemaGrant);
    return role;
  }

  private static String leaf(String ref) {
    final int dot = ref.lastIndexOf('.');
    final String leaf = dot >= 0 ? ref.substring(dot + 1) : ref;
    return leaf.toLowerCase(Locale.ROOT);
  }
}

// End RowSecurity.java
