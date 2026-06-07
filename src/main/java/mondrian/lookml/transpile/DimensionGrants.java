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

import static java.util.regex.Pattern.compile;

/**
 * Maps a LookML {@code access_filter} on a modelled <em>dimension key</em> (the
 * case the classifier marks CLEAN via {@code isSimpleDimensionRef}) to M4
 * member-level row security (#115): a generated {@code <Role>} whose
 * {@code <CubeGrant>} carries a {@code <HierarchyGrant access="custom">} locking
 * the granted dimension down, with a {@code <MemberGrant access="all">} for each
 * member the filter statically admits.
 *
 * <p>Distinct from {@link RowSecurity}, which emits a {@code <PredicateGrant>}
 * for an access_filter on an arbitrary fact <em>column</em>. This helper fires
 * only when the access_filter targets a dimension the cube actually models.
 *
 * <p>Member visibility in Mondrian is static, so a member grant can only be
 * baked when the access_filter names a static member (a non-Liquid
 * {@code value:}). When the value is supplied at query time (a user-attribute
 * Liquid reference, the common case) the dimension is still locked down
 * ({@code access="custom"} with no member grant — denied by default), and a
 * DEGRADE note records that the visible members are supplied per user at query
 * time. The importer never emits a grant that would silently widen access.
 */
final class DimensionGrants {
  private DimensionGrants() {}

  /** Suffix for the generated role name, e.g. {@code orders_dim_security}. */
  private static final String ROLE_SUFFIX = "_dim_security";
  private static final String ACCESS_CUSTOM = "custom";
  private static final String ACCESS_ALL = "all";

  /** Any Liquid output / tag marks the value as query-time supplied. */
  private static final java.util.regex.Pattern LIQUID =
      compile("\\{\\{|\\{%");

  /** Resolves an access_filter {@code field:} reference to the emitted M4
   * dimension and its member level, or empty if the cube does not model it. */
  interface DimensionResolver {
    Optional<GrantTarget> resolve(String fieldRef);
  }

  /** The emitted dimension + member-level coordinates a member grant needs. */
  static final class GrantTarget {
    final String dimension;
    final String hierarchy;
    final String level;

    GrantTarget(String dimension, String hierarchy, String level) {
      this.dimension = dimension;
      this.hierarchy = hierarchy;
      this.level = level;
    }
  }

  /** Emits a member-grant role for each dimension-key access_filter on the
   * explore that the cube models, into the shared model. */
  static void emit(LookmlNode explore, String cubeName,
      DimensionResolver resolver, M4SchemaModel model,
      ProvenanceMap.Builder provenance) {
    final List<Map<String, Object>> hierarchyGrants = new ArrayList<>();
    for (LookmlNode af : explore.children(TranspileKeywords.ACCESS_FILTER)) {
      final Optional<String> field = af.stringValue(TranspileKeywords.FIELD);
      if (field.isEmpty()) {
        continue;
      }
      final Optional<GrantTarget> target = resolver.resolve(field.get().trim());
      if (target.isEmpty()) {
        // Not a dimension this cube models: RowSecurity (predicate) handles it.
        continue;
      }
      // A static value: bakes the granted member; absent/Liquid → deny-by-
      // default (access=custom, no member), members supplied per user at runtime.
      hierarchyGrants.add(hierarchyGrant(target.get(), staticMembers(af)));
    }
    if (hierarchyGrants.isEmpty()) {
      return;
    }
    final String roleName = cubeName + ROLE_SUFFIX;
    model.addRole(roleName, role(roleName, cubeName, hierarchyGrants));
    provenance.put("explore:" + cubeName + ".access_filter_dimension",
        "role:" + roleName);
  }

  /** The statically-known member values an access_filter admits: a non-Liquid
   * {@code value:} literal. Empty when supplied at query time (Liquid) or
   * absent — then the dimension is locked with no member grant (deny-by-default,
   * a DEGRADE). */
  private static List<String> staticMembers(LookmlNode accessFilter) {
    final List<String> out = new ArrayList<>();
    accessFilter.stringValue(TranspileKeywords.VALUE)
        .map(String::trim)
        .filter(v -> !v.isEmpty())
        .filter(v -> !LIQUID.matcher(v).find())
        .ifPresent(out::add);
    return out;
  }

  private static Map<String, Object> hierarchyGrant(GrantTarget t,
      List<String> members) {
    final Map<String, Object> hg = new LinkedHashMap<>();
    hg.put("hierarchy", mdx(t.dimension, t.hierarchy));
    hg.put("access", ACCESS_CUSTOM);
    hg.put("bottom_level", mdx(t.dimension, t.hierarchy, t.level));
    if (!members.isEmpty()) {
      final List<Object> memberGrants = new ArrayList<>();
      for (String m : members) {
        final Map<String, Object> mg = new LinkedHashMap<>();
        // A single-level attribute (all-member) hierarchy resolves a member
        // directly under the hierarchy: [dim].[hierarchy].[member].
        mg.put("member", mdx(t.dimension, t.hierarchy, m));
        mg.put("access", ACCESS_ALL);
        memberGrants.add(mg);
      }
      hg.put("members", memberGrants);
    }
    return hg;
  }

  private static Map<String, Object> role(String roleName, String cubeName,
      List<Map<String, Object>> hierarchyGrants) {
    final Map<String, Object> cubeGrant = new LinkedHashMap<>();
    cubeGrant.put("cube", cubeName);
    cubeGrant.put("access", ACCESS_ALL);
    cubeGrant.put("hierarchies", new ArrayList<Object>(hierarchyGrants));

    final List<Object> cubeGrants = new ArrayList<>();
    cubeGrants.add(cubeGrant);

    final Map<String, Object> schemaGrant = new LinkedHashMap<>();
    schemaGrant.put("access", ACCESS_ALL);
    schemaGrant.put("cubes", cubeGrants);

    final Map<String, Object> role = new LinkedHashMap<>();
    role.put("name", roleName);
    role.put("schema_grant", schemaGrant);
    return role;
  }

  /** Builds a bracketed MDX unique name from its parts, e.g.
   * {@code [users].[country].[GB]}. */
  private static String mdx(String... parts) {
    final StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (sb.length() > 0) {
        sb.append('.');
      }
      sb.append('[').append(p).append(']');
    }
    return sb.toString();
  }

  /** The leaf of a {@code view.field} / {@code field} reference, lower-cased. */
  static String leaf(String ref) {
    final int dot = ref.lastIndexOf('.');
    final String leaf = dot >= 0 ? ref.substring(dot + 1) : ref;
    return leaf.toLowerCase(Locale.ROOT);
  }
}

// End DimensionGrants.java
