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
    // field-leaf (lower-cased) → the emitted dimension/level a dimension-key
    // access_filter grant resolves against (#115).
    final Map<String, DimensionGrants.GrantTarget> grantTargets =
        new LinkedHashMap<>();

    // #124: recover the many-to-many bridge two-hops first. Each maps to a
    // <BridgeLink> on the fact measure group; its bridge view and dim view are
    // then excluded from the normal foreign-key / fact-base join partition (the
    // dim view is emitted as a bridged conformed dimension below). Gated on the
    // fact declaring a grain key — the engine de-dups the fullCount bridge on
    // the fact <Key>; without it the bridge would be silently wrong (matches the
    // classifier gate, so a no-PK explore is never reached here anyway).
    final List<BridgeJoins> bridges =
        primaryKeyColumn(baseView).isPresent()
            ? BridgeJoins.recover(explore, baseViewName, this::tableFor)
            : java.util.Collections.emptyList();
    // #125: exclude bridge participants by their join NAME, not underlying view,
    // so a from:-aliased bridge/dim and a same-base-view conformed dimension are
    // not conflated.
    final Set<String> bridgeJoinNames = new HashSet<>();
    for (BridgeJoins b : bridges) {
      bridgeJoinNames.add(b.bridgeName());
      bridgeJoinNames.add(b.dimName());
    }

    // Partition the joins: a joined view that itself has eligible measures is a
    // conformed FACT base (its own measure group, #115); one without is a
    // conformed DIMENSION. Bridge hops and bridged dim hops are handled by the
    // bridge path, so they are excluded from both partitions here (#124/#125).
    final List<LookmlNode> factBaseJoins = new ArrayList<>();
    final List<LookmlNode> dimensionJoins = new ArrayList<>();
    for (LookmlNode join : LookmlTranspiler.joins(explore)) {
      if (bridgeJoinNames.contains(LookmlTranspiler.joinName(join))) {
        continue;
      }
      if (isFactBaseJoin(join)) {
        factBaseJoins.add(join);
      } else {
        dimensionJoins.add(join);
      }
    }

    // 1. Degenerate dimensions: each eligible base-view plain dimension.
    emitDegenerateDimensions(baseView, baseViewName, factTable, cubePath,
        dimensions, dimensionLinks, grantTargets);

    // 2. Conformed dimensions: one per dimension join. Also collects a fact
    //    column a column-less count can count over (a join FK on the fact).
    final List<String> factColumns = new ArrayList<>();
    for (LookmlNode join : dimensionJoins) {
      emitJoinedDimension(explore, baseViewName, factTable, join, cubePath,
          dimensions, dimensionLinks, factColumns, grantTargets);
    }
    // 2b. Bridge dimensions (#124): each recovered two-hop emits the dim view as
    //     a conformed dimension keyed on its bridge-side key, plus a
    //     <BridgeLink> on the fact measure group.
    for (BridgeJoins bridge : bridges) {
      emitBridgeDimension(bridge, cubePath, dimensions, dimensionLinks,
          factColumns, grantTargets);
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
    // A derived_table base view becomes a SQL-backed <Query> (#115).
    maybeRegisterQuery(baseView, factTable, primaryKeyColumn(baseView));

    final Map<String, Object> measureGroup = new LinkedHashMap<>();
    measureGroup.put("name", baseViewName);
    measureGroup.put("table", factTable);
    measureGroup.put("measures", measures);
    measureGroup.put("dimension_links", dimensionLinks);

    final List<Object> measureGroups = new ArrayList<>();
    measureGroups.add(measureGroup);

    // 3b. Conformed multi-base (#115): each fact-base join becomes its own
    //     measure group over the shared conformed dimensions.
    for (LookmlNode factJoin : factBaseJoins) {
      emitConformedMeasureGroup(explore, baseViewName, factJoin, cubePath,
          dimensionJoins, measureGroups, calculatedMembers);
    }

    final Map<String, Object> cube = new LinkedHashMap<>();
    // drill_fields → the M4 drillthrough RETURN set, carried as a cube
    // annotation (M4 has no <DrillThrough> element; #115).
    DrillFields.forCube(explore, baseView).ifPresent(fields -> {
      final Map<String, Object> annotations = new LinkedHashMap<>();
      annotations.put(DrillFields.ANNOTATION_NAME, fields);
      cube.put("annotations", annotations);
      provenance.put("explore:" + exploreName + ".drill_fields",
          cubePath + "/annotation:" + DrillFields.ANNOTATION_NAME);
    });
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

    // Dimension-key access_filters (#115): a generated role with a member-level
    // HierarchyGrant on the granted dimension (the CLEAN isSimpleDimensionRef
    // case). Resolves the access_filter field to the dimension emitted above.
    DimensionGrants.emit(explore, cubeName,
        ref -> Optional.ofNullable(
            grantTargets.get(DimensionGrants.leaf(ref))),
        model, provenance);
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

  // --- conformed multi-base measure groups (#115) ------------------------

  /** Whether a join's joined view is a conformed FACT base — it declares at
   * least one eligible {@code measure:}. Such a view becomes its own measure
   * group (not a dimension). A join without measures is a conformed dimension. */
  private boolean isFactBaseJoin(LookmlNode join) {
    final String joinedViewName = LookmlTranspiler.joinedView(join);
    final LookmlNode joinedView = viewsByName.get(joinedViewName);
    if (joinedView == null) {
      return false;
    }
    for (LookmlNode measure : joinedView.children(TranspileKeywords.MEASURE)) {
      final String name = measure.name().orElse("");
      if (!name.isEmpty() && eligible.field(joinedViewName, name)) {
        return true;
      }
    }
    return false;
  }

  /** Emits a measure group for a conformed fact-base join: its measures over
   * the shared conformed dimensions. Each conformed dimension this fact can key
   * (its own {@code sql_on} references that dimension's view) gets a foreign_key
   * link; a conformed dimension it cannot key is omitted from this group (#115).
   *
   * <p>Scope (v1, #115): the fact base must declare its join keys to the
   * conformed dimensions directly (a single equality {@code sql_on} naming the
   * dimension view). Base-view degenerate dimensions and cross-fact copy links
   * are not wired into the secondary group — documented remainder. */
  private void emitConformedMeasureGroup(LookmlNode explore,
      String baseViewName, LookmlNode factJoin, String cubePath,
      List<LookmlNode> dimensionJoins, List<Object> measureGroups,
      List<Object> calculatedMembers) {
    // #125: this fact base is itself a join; its own sql_on references its
    // dimensions and the explore's other joins by their JOIN NAMES (the field
    // namespace), while its physical table comes from the underlying view.
    final String factName = LookmlTranspiler.joinName(factJoin);
    final String factViewName = LookmlTranspiler.joinedView(factJoin);
    final LookmlNode factView = viewsByName.get(factViewName);
    if (factView == null) {
      return;
    }
    final String factTable = LookmlTranspiler.tableOf(factView, factViewName);

    final List<Object> dimensionLinks = new ArrayList<>();
    final List<String> factColumns = new ArrayList<>();
    for (LookmlNode dimJoin : dimensionJoins) {
      final String dimName = LookmlTranspiler.joinName(dimJoin);
      // Recover this fact's FK to the conformed dimension from its own sql_on,
      // matching the dimension side by the dim join's NAME (#125).
      final Optional<JoinKeys> keys =
          JoinKeys.from(factJoin, factName, dimName);
      if (keys.isEmpty()) {
        continue;
      }
      final Map<String, Object> link = new LinkedHashMap<>();
      link.put("type", TranspileKeywords.LINK_FOREIGN_KEY);
      link.put("dimension", dimName);
      link.put("foreign_key_column", keys.get().factForeignKeyColumn());
      dimensionLinks.add(link);
      factColumns.add(keys.get().factForeignKeyColumn());
    }

    final Optional<String> factCountColumn = factColumns.isEmpty()
        ? Optional.empty() : Optional.of(factColumns.get(0));

    // #125: the measure group is named by the join name so two fact-base joins
    // of the same underlying view stay distinct; measures/eligibility still
    // resolve against the underlying view name.
    final String mgPath = cubePath + "/measureGroup:" + factName;
    final List<Object> measures = new ArrayList<>();
    emitMeasures(factView, factViewName, mgPath, cubePath, factCountColumn,
        measures, calculatedMembers);
    if (measures.isEmpty()) {
      return;
    }

    model.registerTable(factTable, primaryKeyColumn(factView).orElse(null));
    maybeRegisterQuery(factView, factTable, primaryKeyColumn(factView));

    final Map<String, Object> measureGroup = new LinkedHashMap<>();
    measureGroup.put("name", factName);
    measureGroup.put("table", factTable);
    measureGroup.put("measures", measures);
    measureGroup.put("dimension_links", dimensionLinks);
    measureGroups.add(measureGroup);
    provenance.put("explore:" + explore.name().orElse("") + ".measureGroup:"
        + factName, mgPath);
  }

  // --- degenerate dimensions ---------------------------------------------

  private void emitDegenerateDimensions(LookmlNode baseView,
      String baseViewName, String factTable, String cubePath,
      List<Object> dimensions, List<Object> dimensionLinks,
      Map<String, DimensionGrants.GrantTarget> grantTargets) {
    for (LookmlNode dim : baseView.children(TranspileKeywords.DIMENSION)) {
      emitDegenerateDimension(dim, baseViewName, factTable, cubePath,
          dimensions, dimensionLinks, grantTargets);
    }
    // dimension_group children carry duration (#108); emit those too.
    for (LookmlNode dg : baseView.children(TranspileKeywords.DIMENSION_GROUP)) {
      if (TierDuration.isTierOrDuration(dg)) {
        emitDegenerateDimension(dg, baseViewName, factTable, cubePath,
            dimensions, dimensionLinks, grantTargets);
      }
    }
  }

  private void emitDegenerateDimension(LookmlNode dim, String baseViewName,
      String factTable, String cubePath, List<Object> dimensions,
      List<Object> dimensionLinks,
      Map<String, DimensionGrants.GrantTarget> grantTargets) {
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

    // A degenerate dimension is its own hierarchy/level: an access_filter on
    // this dimension key grants members of [dimName].[dimName] (#115).
    grantTargets.put(dimName.toLowerCase(Locale.ROOT),
        new DimensionGrants.GrantTarget(dimName, dimName, dimName));
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
      List<String> factColumns,
      Map<String, DimensionGrants.GrantTarget> grantTargets) {
    // #125: the join NAME is the field namespace + the conformed dimension's
    // name (so two from: aliases of one base view stay distinct, each with its
    // own physical-table alias); the UNDERLYING view supplies the dimension's
    // column definitions and physical sql_table_name.
    final String dimName = LookmlTranspiler.joinName(join);
    final String underlyingViewName = LookmlTranspiler.joinedView(join);
    final LookmlNode underlyingView = viewsByName.get(underlyingViewName);
    if (underlyingView == null) {
      return;
    }
    // sql_on dim-side refs use the join name, never the from:-target (#125).
    final Optional<JoinKeys> keys =
        JoinKeys.from(join, baseViewName, dimName);
    if (keys.isEmpty()) {
      // Can't resolve the join columns: skip this dimension rather than emit a
      // schema that fails to load. The classifier records the DEGRADE note
      // (DEGRADE_JOIN_SQL_ON_UNPARSEABLE) so the report surfaces it (#115).
      return;
    }
    // #125: the dimension's physical table is the UNDERLYING view's table
    // (its sql_table_name, else the view name) — NOT the join name. Two from:
    // aliases of one base view therefore share one physical table; they stay
    // distinct because each conformed dimension has a distinct NAME (the join
    // name) and the engine aliases the shared physical table per dimension
    // (MultiSharedDimAttributeScopedAliasTest).
    final String dimTable =
        LookmlTranspiler.tableOf(underlyingView, underlyingViewName);
    final String dimKeyColumn = keys.get().dimensionKeyColumn();
    final String keyAttrName = dimKeyColumn;

    final List<Object> attributes = new ArrayList<>();
    // The key attribute (the join target) — keyed, hidden from hierarchies.
    attributes.add(buildKeyAttribute(keyAttrName, dimTable, dimKeyColumn));

    for (LookmlNode dim : underlyingView.children(TranspileKeywords.DIMENSION)) {
      final String attrName = dim.name().orElse("");
      if (attrName.isEmpty() || !eligible.field(underlyingViewName, attrName)
          || attrName.equals(keyAttrName)) {
        continue;
      }
      final String column = LookmlTranspiler.columnOf(dim, attrName);
      attributes.add(buildAttribute(attrName, dimTable, column, dim));
      provenance.put(dimName + "." + attrName,
          cubePath + "/dimension:" + dimName + "/attribute:" + attrName);

      // An access_filter on this conformed-dimension key grants members of
      // [dimName].[attrName] (the attribute hierarchy, #115/#125).
      grantTargets.put(attrName.toLowerCase(Locale.ROOT),
          new DimensionGrants.GrantTarget(dimName, attrName, attrName));
    }

    final Map<String, Object> dimension = new LinkedHashMap<>();
    dimension.put("name", dimName);
    dimension.put("table", dimTable);
    dimension.put("key", keyAttrName);
    dimension.put("attributes", attributes);
    dimensions.add(dimension);

    final Map<String, Object> link = new LinkedHashMap<>();
    link.put("type", TranspileKeywords.LINK_FOREIGN_KEY);
    link.put("dimension", dimName);
    link.put("foreign_key_column", keys.get().factForeignKeyColumn());
    dimensionLinks.add(link);

    // The fact FK column is a guaranteed-present column for a row count.
    factColumns.add(keys.get().factForeignKeyColumn());

    // Register the dim table with its key so the physical schema declares it.
    model.registerTable(dimTable, dimKeyColumn);
    // A derived_table underlying view becomes a SQL-backed <Query> (#115).
    maybeRegisterQuery(underlyingView, dimTable, Optional.of(dimKeyColumn));
  }

  // --- bridge (many-to-many) dimensions (#124) ---------------------------

  /** The physical table backing {@code viewName} (its {@code sql_table_name}
   * else the view name), or the view name when the view is unknown. Used to
   * resolve a bridge view's table for the {@code bridge_table} attribute. */
  private String tableFor(String viewName) {
    final LookmlNode view = viewsByName.get(viewName);
    return view == null
        ? viewName : LookmlTranspiler.tableOf(view, viewName);
  }

  /**
   * Emits the bridged conformed dimension for {@code bridge} (the dim view
   * reached through the bridge, keyed on its bridge-side key column) plus a
   * {@code <BridgeLink>} on the fact measure group (#124/#107).
   *
   * <p>The dimension is a normal conformed dimension on the dim view's table;
   * the link carries the bridge table and the three bridge columns. No
   * allocation weight is modelled (LookML has none), so the engine defaults to
   * fullCount — de-duplicating on the fact grain key (already registered on the
   * fact table) so the measure returns the de-duplicated total, not the
   * fanned-out one.
   */
  private void emitBridgeDimension(BridgeJoins bridge, String cubePath,
      List<Object> dimensions, List<Object> dimensionLinks,
      List<String> factColumns,
      Map<String, DimensionGrants.GrantTarget> grantTargets) {
    // #125: the bridged dimension's NAME is the dim hop's join name (distinct
    // per alias); its columns/table come from the UNDERLYING dim view.
    final String dimName = bridge.dimName();
    final String dimViewName = bridge.dimView();
    final LookmlNode dimView = viewsByName.get(dimViewName);
    if (dimView == null) {
      return;
    }
    final String dimTable = LookmlTranspiler.tableOf(dimView, dimViewName);
    final String dimKeyColumn = bridge.dimKeyColumn();
    final String keyAttrName = dimKeyColumn;

    final List<Object> attributes = new ArrayList<>();
    attributes.add(buildKeyAttribute(keyAttrName, dimTable, dimKeyColumn));
    for (LookmlNode dim : dimView.children(TranspileKeywords.DIMENSION)) {
      final String attrName = dim.name().orElse("");
      if (attrName.isEmpty() || !eligible.field(dimViewName, attrName)
          || attrName.equals(keyAttrName)) {
        continue;
      }
      final String column = LookmlTranspiler.columnOf(dim, attrName);
      attributes.add(buildAttribute(attrName, dimTable, column, dim));
      provenance.put(dimName + "." + attrName,
          cubePath + "/dimension:" + dimName + "/attribute:" + attrName);
      // An access_filter on a bridged-dimension key grants members of
      // [dimName].[attrName] — the bridge member-grant case (#124/#107/#125).
      grantTargets.put(attrName.toLowerCase(Locale.ROOT),
          new DimensionGrants.GrantTarget(dimName, attrName, attrName));
    }

    final Map<String, Object> dimension = new LinkedHashMap<>();
    dimension.put("name", dimName);
    dimension.put("table", dimTable);
    dimension.put("key", keyAttrName);
    dimension.put("attributes", attributes);
    dimensions.add(dimension);

    final Map<String, Object> link = new LinkedHashMap<>();
    link.put("type", TranspileKeywords.LINK_BRIDGE);
    link.put("dimension", dimName);
    link.put(TranspileKeywords.BRIDGE_TABLE, bridge.bridgeTable());
    link.put(TranspileKeywords.FACT_FOREIGN_KEY_COLUMN,
        bridge.factForeignKeyColumn());
    link.put(TranspileKeywords.BRIDGE_FACT_KEY_COLUMN,
        bridge.bridgeFactKeyColumn());
    link.put(TranspileKeywords.BRIDGE_DIMENSION_KEY_COLUMN,
        bridge.bridgeDimensionKeyColumn());
    // No weight: LookML has no allocation weight, so the engine defaults to
    // fullCount (omit aggregation/weight_column entirely, #124).
    dimensionLinks.add(link);

    // The fact FK column is a guaranteed-present fact column for a row count.
    factColumns.add(bridge.factForeignKeyColumn());

    // Register the dim table with its key and the bridge table (no key needed).
    model.registerTable(dimTable, dimKeyColumn);
    model.registerTable(bridge.bridgeTable(), null);

    provenance.put("explore:" + cubePath + ".bridge:" + dimName,
        cubePath + "/measureGroup/bridgeLink:" + dimName);
  }

  /** Registers {@code view}'s derived_table SQL as a SQL-backed {@code <Query>}
   * physical table aliased {@code tableAlias}, if the view has a
   * {@code derived_table { sql: ... ;; }} (#115). The persistence policy is
   * dropped (the classifier records the DEGRADE note). */
  private void maybeRegisterQuery(LookmlNode view, String tableAlias,
      Optional<String> keyColumn) {
    view.child(TranspileKeywords.DERIVED_TABLE)
        .flatMap(dt -> dt.stringValue(TranspileKeywords.SQL))
        .map(String::trim)
        .filter(sql -> !sql.isEmpty())
        .ifPresent(sql ->
            model.registerQuery(tableAlias, sql, keyColumn.orElse(null)));
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
    // #119: the base view's primary-key column, so a distinct-key measure can
    // tell a no-op de-dup (key == PK → plain SUM/AVG) from a real
    // measure-level distinct grain (key != PK → distinctKeyColumn).
    final Optional<String> primaryKey = primaryKeyColumn(baseView);
    for (LookmlNode measure : baseView.children(TranspileKeywords.MEASURE)) {
      final String measureName = measure.name().orElse("");
      if (measureName.isEmpty()
          || !eligible.field(baseViewName, measureName)) {
        continue;
      }
      Measures.emit(measure, measureName, baseViewName, mgPath, cubePath,
          factCountColumn, primaryKey, baseView, measures, calculatedMembers,
          provenance);
    }
  }
}

// End CubeEmitter.java
