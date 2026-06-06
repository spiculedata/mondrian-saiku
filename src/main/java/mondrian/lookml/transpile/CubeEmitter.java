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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Emits one M4 cube (with its conformed/degenerate dimensions, single measure
 * group and calculated members) for one CLEAN/DEGRADE single-base star explore,
 * appending to the shared {@link M4SchemaModel} and recording each construct's
 * origin in the {@link ProvenanceMap.Builder}.
 *
 * <p>This is the v1 core path: the base view becomes the measure group's fact;
 * each {@code join:} (already classified star-safe) becomes a conformed
 * dimension with a {@code foreign_key} link; each plain base-view dimension
 * becomes a degenerate dimension with a {@code fact} link; measures map by
 * aggregator; equality {@code filters:} measures become calculated members.
 */
final class CubeEmitter {
  private final M4SchemaModel model;
  private final Map<String, LookmlNode> viewsByName;
  private final LookmlTranspiler.Eligibility eligible;
  private final ProvenanceMap.Builder provenance;

  CubeEmitter(M4SchemaModel model, Map<String, LookmlNode> viewsByName,
      LookmlTranspiler.Eligibility eligible, ProvenanceMap.Builder provenance) {
    this.model = requireNonNull(model);
    this.viewsByName = requireNonNull(viewsByName);
    this.eligible = requireNonNull(eligible);
    this.provenance = requireNonNull(provenance);
  }

  /** Emits the cube for {@code explore} (assumed eligible). */
  void emit(LookmlNode explore) {
    final String exploreName = explore.name().orElse("");
    final String baseViewName = LookmlTranspiler.baseView(explore);
    final LookmlNode baseView = viewsByName.get(baseViewName);
    if (baseView == null) {
      // No backing view: nothing measurable to emit. (The classifier still
      // recorded the explore; we simply produce no cube for it.)
      return;
    }

    final String cubeName = exploreName;
    final String factTable = LookmlTranspiler.tableOf(baseView, baseViewName);
    final String cubePath = "cube:" + cubeName;
    provenance.put("explore:" + exploreName, cubePath);

    final List<Object> dimensions = new ArrayList<>();
    final List<Object> dimensionLinks = new ArrayList<>();

    // 1. Degenerate dimensions: each eligible base-view plain dimension.
    emitDegenerateDimensions(baseView, baseViewName, factTable, cubePath,
        dimensions, dimensionLinks);

    // 2. Conformed dimensions: one per star join. Also collects a fact column
    //    a column-less count can count over (a join FK on the fact).
    final List<String> factColumns = new ArrayList<>();
    for (LookmlNode join : LookmlTranspiler.joins(explore)) {
      emitJoinedDimension(explore, baseViewName, factTable, join, cubePath,
          dimensions, dimensionLinks, factColumns);
    }
    addDegenerateColumns(baseView, baseViewName, factColumns);
    final Optional<String> factCountColumn = factColumns.isEmpty()
        ? Optional.empty() : Optional.of(factColumns.get(0));

    // 3. Measure group: measures + filtered-measure base measures.
    final String mgPath = cubePath + "/measureGroup:" + baseViewName;
    final List<Object> measures = new ArrayList<>();
    final List<Object> calculatedMembers = new ArrayList<>();
    emitMeasures(baseView, baseViewName, mgPath, cubePath, factCountColumn,
        measures, calculatedMembers);

    // Declare the fact grain key (from a primary_key: yes dimension) so the
    // engine can apply symmetric (fan-out-safe) aggregation, #103. Without it,
    // a fact key is null and a fan-out sum would double-count.
    model.registerTable(factTable, primaryKeyColumn(baseView).orElse(null));

    final Map<String, Object> measureGroup = new LinkedHashMap<>();
    measureGroup.put("name", baseViewName);
    measureGroup.put("table", factTable);
    measureGroup.put("measures", measures);
    measureGroup.put("dimension_links", dimensionLinks);

    final List<Object> measureGroups = new ArrayList<>();
    measureGroups.add(measureGroup);

    final Map<String, Object> cube = new LinkedHashMap<>();
    if (!dimensions.isEmpty()) {
      cube.put("dimensions", dimensions);
    }
    cube.put("measure_groups", measureGroups);
    if (!calculatedMembers.isEmpty()) {
      cube.put("calculated_members", calculatedMembers);
    }
    model.addCube(cubeName, cube);

    // Row security (#106): arbitrary-column access_filters become a generated
    // role with a PredicateGrant on this cube's measure group.
    RowSecurity.emit(explore, cubeName, baseViewName,
        knownDimensionNames(explore, baseView), model, provenance);
  }

  /** The dimension names this cube models (base + joined view dimensions), so
   * row security can skip a dimension-key access_filter (the DimensionGrant
   * case, #115) and only emit predicate grants for arbitrary columns. */
  private Set<String> knownDimensionNames(LookmlNode explore,
      LookmlNode baseView) {
    final Set<String> names = new HashSet<>();
    collectDimensionNames(baseView, names);
    for (LookmlNode join : LookmlTranspiler.joins(explore)) {
      final LookmlNode joinedView =
          viewsByName.get(LookmlTranspiler.joinedView(join));
      if (joinedView != null) {
        collectDimensionNames(joinedView, names);
      }
    }
    return names;
  }

  private void collectDimensionNames(LookmlNode view, Set<String> names) {
    for (LookmlNode dim : view.children(TranspileKeywords.DIMENSION)) {
      dim.name().ifPresent(n -> names.add(n.toLowerCase(Locale.ROOT)));
    }
  }

  // --- degenerate dimensions ---------------------------------------------

  private void emitDegenerateDimensions(LookmlNode baseView,
      String baseViewName, String factTable, String cubePath,
      List<Object> dimensions, List<Object> dimensionLinks) {
    for (LookmlNode dim : baseView.children(TranspileKeywords.DIMENSION)) {
      emitDegenerateDimension(dim, baseViewName, factTable, cubePath,
          dimensions, dimensionLinks);
    }
    // dimension_group children carry duration (#108); emit those too.
    for (LookmlNode dg : baseView.children(TranspileKeywords.DIMENSION_GROUP)) {
      if (TierDuration.isTierOrDuration(dg)) {
        emitDegenerateDimension(dg, baseViewName, factTable, cubePath,
            dimensions, dimensionLinks);
      }
    }
  }

  private void emitDegenerateDimension(LookmlNode dim, String baseViewName,
      String factTable, String cubePath, List<Object> dimensions,
      List<Object> dimensionLinks) {
    final String dimName = dim.name().orElse("");
    if (dimName.isEmpty() || !eligible.field(baseViewName, dimName)) {
      return;
    }
    final Map<String, Object> attribute =
        TierDuration.attribute(dim, dimName, factTable)
            .orElseGet(() -> buildAttribute(dimName, factTable,
                LookmlTranspiler.columnOf(dim, dimName), dim));
    final List<Object> attributes = new ArrayList<>();
    attributes.add(attribute);

    final Map<String, Object> dimension = new LinkedHashMap<>();
    dimension.put("name", dimName);
    dimension.put("key", dimName);
    dimension.put("attributes", attributes);
    dimensions.add(dimension);

    final Map<String, Object> link = new LinkedHashMap<>();
    link.put("type", TranspileKeywords.LINK_FACT);
    link.put("dimension", dimName);
    dimensionLinks.add(link);

    provenance.put(baseViewName + "." + dimName,
        cubePath + "/dimension:" + dimName + "/attribute:" + dimName);
  }

  /** The fact's grain key column, from a {@code primary_key: yes} dimension's
   * column, if the base view declares one (#103 symmetric aggregation). */
  private Optional<String> primaryKeyColumn(LookmlNode baseView) {
    for (LookmlNode dim : baseView.children(TranspileKeywords.DIMENSION)) {
      final boolean isPk = dim.stringValue(TranspileKeywords.PRIMARY_KEY)
          .map(v -> v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("true"))
          .orElse(false);
      if (isPk) {
        return Optional.of(
            LookmlTranspiler.columnOf(dim, dim.name().orElse("")));
      }
    }
    return Optional.empty();
  }

  // --- conformed (joined) dimensions -------------------------------------

  private void emitJoinedDimension(LookmlNode explore, String baseViewName,
      String factTable, LookmlNode join, String cubePath,
      List<Object> dimensions, List<Object> dimensionLinks,
      List<String> factColumns) {
    final String joinedViewName = LookmlTranspiler.joinedView(join);
    final LookmlNode joinedView = viewsByName.get(joinedViewName);
    if (joinedView == null) {
      return;
    }
    final Optional<JoinKeys> keys =
        JoinKeys.from(join, baseViewName, joinedViewName);
    if (keys.isEmpty()) {
      // Can't resolve the join columns: skip this dimension rather than emit a
      // schema that fails to load. (A follow-up can DEGRADE-note this.)
      return;
    }
    final String dimTable = LookmlTranspiler.tableOf(joinedView, joinedViewName);
    final String dimKeyColumn = keys.get().dimensionKeyColumn();
    final String keyAttrName = dimKeyColumn;

    final List<Object> attributes = new ArrayList<>();
    // The key attribute (the join target) — keyed, hidden from hierarchies.
    attributes.add(buildKeyAttribute(keyAttrName, dimTable, dimKeyColumn));

    for (LookmlNode dim : joinedView.children(TranspileKeywords.DIMENSION)) {
      final String dimName = dim.name().orElse("");
      if (dimName.isEmpty() || !eligible.field(joinedViewName, dimName)
          || dimName.equals(keyAttrName)) {
        continue;
      }
      final String column = LookmlTranspiler.columnOf(dim, dimName);
      attributes.add(buildAttribute(dimName, dimTable, column, dim));
      provenance.put(joinedViewName + "." + dimName,
          cubePath + "/dimension:" + joinedViewName + "/attribute:" + dimName);
    }

    final Map<String, Object> dimension = new LinkedHashMap<>();
    dimension.put("name", joinedViewName);
    dimension.put("table", dimTable);
    dimension.put("key", keyAttrName);
    dimension.put("attributes", attributes);
    dimensions.add(dimension);

    final Map<String, Object> link = new LinkedHashMap<>();
    link.put("type", TranspileKeywords.LINK_FOREIGN_KEY);
    link.put("dimension", joinedViewName);
    link.put("foreign_key_column", keys.get().factForeignKeyColumn());
    dimensionLinks.add(link);

    // The fact FK column is a guaranteed-present column for a row count.
    factColumns.add(keys.get().factForeignKeyColumn());

    // Register the dim table with its key so the physical schema declares it.
    model.registerTable(dimTable, dimKeyColumn);
  }

  /** Adds each eligible base-view dimension's column as a count fallback. */
  private void addDegenerateColumns(LookmlNode baseView, String baseViewName,
      List<String> factColumns) {
    for (LookmlNode dim : baseView.children(TranspileKeywords.DIMENSION)) {
      final String dimName = dim.name().orElse("");
      if (dimName.isEmpty() || !eligible.field(baseViewName, dimName)) {
        continue;
      }
      factColumns.add(LookmlTranspiler.columnOf(dim, dimName));
    }
  }

  // --- attributes ---------------------------------------------------------

  private Map<String, Object> buildKeyAttribute(String name, String table,
      String column) {
    final Map<String, Object> a = new LinkedHashMap<>();
    a.put("name", name);
    a.put("table", table);
    a.put("key_column", column);
    a.put("has_hierarchy", false);
    return a;
  }

  /** Builds a queryable attribute, mapping {@code label}&rarr;caption,
   * {@code description}&rarr;description and {@code value_format(_name)} is
   * carried by the owning measure, not the attribute. */
  private Map<String, Object> buildAttribute(String name, String table,
      String column, LookmlNode dim) {
    final Map<String, Object> a = new LinkedHashMap<>();
    a.put("name", name);
    a.put("table", table);
    a.put("key_column", column);
    dim.stringValue(TranspileKeywords.LABEL)
        .ifPresent(label -> a.put("caption", label));
    dim.stringValue(TranspileKeywords.DESCRIPTION)
        .ifPresent(desc -> a.put("description", desc));
    return a;
  }

  // --- measures + filtered measures --------------------------------------

  private void emitMeasures(LookmlNode baseView, String baseViewName,
      String mgPath, String cubePath, Optional<String> factCountColumn,
      List<Object> measures, List<Object> calculatedMembers) {
    for (LookmlNode measure : baseView.children(TranspileKeywords.MEASURE)) {
      final String measureName = measure.name().orElse("");
      if (measureName.isEmpty()
          || !eligible.field(baseViewName, measureName)) {
        continue;
      }
      Measures.emit(measure, measureName, baseViewName, mgPath, cubePath,
          factCountColumn, measures, calculatedMembers, provenance);
    }
  }
}

// End CubeEmitter.java
