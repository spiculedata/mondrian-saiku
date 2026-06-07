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
package mondrian.lookml.equivalence;

import mondrian.lookml.transpile.ProvenanceMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Issue #128: rewrites a {@link LookerQuerySpec} into an MDX statement over the
 * converted cube, using the transpiler's {@link ProvenanceMap} to resolve each
 * Looker field to the M4 element actually emitted for it.
 *
 * <p>Shape: measures on COLUMNS, the requested dimension levels crossjoined
 * {@code NON EMPTY} on ROWS — the canonical "group measures by dimensions"
 * query Looker itself issues for an explore. The cube name is taken from the
 * explore's provenance entry ({@code explore:NAME -> cube:NAME}).
 *
 * <p>Provenance is the contract: a field with no entry (REFUSE) or a degraded
 * one we can't turn into an MDX member is EXCLUDED and recorded in
 * {@link Plan#skippedFields()} — the harness never fabricates a member it can't
 * prove the importer emitted CLEAN. The {@link Plan} returns the MDX plus, for
 * each kept field, the mapping back to its Looker qname so the comparator can
 * align oracle rows to engine cells.
 */
public final class LookerQueryToMdx {

  // M4 path leaf patterns the importer emits (see CubeEmitter / Measures #128).
  private static final Pattern MEASURE_LEAF =
      Pattern.compile("/measure:([^/]+)$");
  private static final Pattern CALC_MEMBER_LEAF =
      Pattern.compile("/calculatedMember:([^/]+)$");
  // cube:CUBE/dimension:DIM/attribute:ATTR  → captures DIM and ATTR.
  private static final Pattern DIM_ATTR =
      Pattern.compile("/dimension:([^/]+)/attribute:([^/]+)$");
  private static final Pattern CUBE_LEAF =
      Pattern.compile("^cube:([^/]+)");

  private final ProvenanceMap provenance;

  public LookerQueryToMdx(ProvenanceMap provenance) {
    this.provenance = requireNonNull(provenance, "provenance");
  }

  /**
   * Builds the MDX plan for {@code spec}. Resolves the cube from the explore's
   * provenance; resolves each dimension/measure field to an MDX member via its
   * M4 path. Fields with no CLEAN provenance are skipped (recorded, not faked).
   */
  public Plan toMdx(LookerQuerySpec spec) {
    requireNonNull(spec, "spec");
    final String cubeName = resolveCube(spec.explore());

    final List<ResolvedField> measures = new ArrayList<>();
    final List<ResolvedField> dimensions = new ArrayList<>();
    final List<String> skipped = new ArrayList<>();

    for (String field : spec.measureFields()) {
      final Optional<String> member = measureMember(field);
      if (member.isPresent()) {
        measures.add(new ResolvedField(field, member.get()));
      } else {
        skipped.add(field);
      }
    }
    for (String field : spec.dimensionFields()) {
      final Optional<DimRef> ref = dimensionRef(field);
      if (ref.isPresent()) {
        dimensions.add(new ResolvedField(field, ref.get().levelExpr()));
      } else {
        skipped.add(field);
      }
    }

    final String mdx = buildMdx(cubeName, measures, dimensions);
    return new Plan(mdx, cubeName, dimensions, measures, skipped);
  }

  private String resolveCube(String exploreName) {
    final String m4 = provenance.m4Path("explore:" + exploreName)
        .orElseThrow(() -> new IllegalArgumentException(
            "no converted cube for explore '" + exploreName + "'"));
    final Matcher m = CUBE_LEAF.matcher(m4);
    if (!m.find()) {
      throw new IllegalArgumentException(
          "explore '" + exploreName + "' did not map to a cube");
    }
    return m.group(1);
  }

  /** Resolves a Looker measure field to its {@code [Measures].[NAME]} member,
   * or empty when the field has no CLEAN measure/calc-member provenance. */
  private Optional<String> measureMember(String fieldQname) {
    final Optional<String> m4 = provenance.m4Path(fieldQname);
    if (m4.isEmpty()) {
      return Optional.empty();
    }
    Matcher m = MEASURE_LEAF.matcher(m4.get());
    if (m.find()) {
      return Optional.of("[Measures].[" + m.group(1) + "]");
    }
    m = CALC_MEMBER_LEAF.matcher(m4.get());
    if (m.find()) {
      return Optional.of("[Measures].[" + m.group(1) + "]");
    }
    return Optional.empty();
  }

  /** Resolves a Looker dimension field to its {@code [DIM].[ATTR]} reference,
   * or empty when the field has no CLEAN dimension/attribute provenance. */
  private Optional<DimRef> dimensionRef(String fieldQname) {
    final Optional<String> m4 = provenance.m4Path(fieldQname);
    if (m4.isEmpty()) {
      return Optional.empty();
    }
    final Matcher m = DIM_ATTR.matcher(m4.get());
    if (!m.find()) {
      return Optional.empty();
    }
    return Optional.of(new DimRef(m.group(1), m.group(2)));
  }

  private static String buildMdx(String cube, List<ResolvedField> measures,
      List<ResolvedField> dimensions) {
    final String columns = measures.isEmpty()
        ? "{}"
        : "{" + join(measures) + "}";
    final StringBuilder sb = new StringBuilder();
    sb.append("SELECT ").append(columns).append(" ON COLUMNS");
    if (!dimensions.isEmpty()) {
      sb.append(",\n NON EMPTY ");
      if (dimensions.size() == 1) {
        sb.append(dimensions.get(0).member()).append(".Members");
      } else {
        // Crossjoin the levels left-to-right (CrossJoin nests pairwise).
        sb.append(crossjoin(dimensions));
      }
      sb.append(" ON ROWS");
    }
    sb.append("\nFROM [").append(cube).append("]");
    return sb.toString();
  }

  private static String join(List<ResolvedField> fields) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(fields.get(i).member());
    }
    return sb.toString();
  }

  private static String crossjoin(List<ResolvedField> dims) {
    // CrossJoin(a.Members, CrossJoin(b.Members, c.Members)) — right-nested.
    String expr = dims.get(dims.size() - 1).member() + ".Members";
    for (int i = dims.size() - 2; i >= 0; i--) {
      expr = "CrossJoin(" + dims.get(i).member() + ".Members, " + expr + ")";
    }
    return expr;
  }

  /** A Looker dimension field resolved to its M4 dimension + attribute. */
  private static final class DimRef {
    private final String dimension;
    private final String attribute;

    DimRef(String dimension, String attribute) {
      this.dimension = dimension;
      this.attribute = attribute;
    }

    /** The MDX <em>level</em> reference {@code [DIM].[ATTR].[ATTR]} — the leaf
     * level of the attribute hierarchy. Using the level (not the bare
     * hierarchy) keeps {@code .Members} to the actual data members and excludes
     * the synthetic {@code [All]} member, so the row set aligns 1:1 with the
     * Looker group-by result. */
    String levelExpr() {
      return "[" + dimension + "].[" + attribute + "].[" + attribute + "]";
    }
  }

  /** A Looker field kept in the query, with the MDX member it resolved to. */
  public static final class ResolvedField {
    private final String lookerField;
    private final String member;

    ResolvedField(String lookerField, String member) {
      this.lookerField = lookerField;
      this.member = member;
    }

    /** The original Looker field qname. */
    public String lookerField() {
      return lookerField;
    }

    /** The MDX member/level expression it resolved to. */
    public String member() {
      return member;
    }
  }

  /**
   * The rewrite outcome: the MDX, the resolved cube, the kept dimension and
   * measure fields (in axis order), and the fields that were skipped because
   * they had no CLEAN provenance.
   */
  public static final class Plan {
    private final String mdx;
    private final String cube;
    private final List<ResolvedField> dimensionFields;
    private final List<ResolvedField> measureFields;
    private final List<String> skippedFields;

    Plan(String mdx, String cube, List<ResolvedField> dimensionFields,
        List<ResolvedField> measureFields, List<String> skippedFields) {
      this.mdx = mdx;
      this.cube = cube;
      this.dimensionFields = Collections.unmodifiableList(
          new ArrayList<>(dimensionFields));
      this.measureFields = Collections.unmodifiableList(
          new ArrayList<>(measureFields));
      this.skippedFields = Collections.unmodifiableList(
          new ArrayList<>(skippedFields));
    }

    /** The generated MDX statement. */
    public String mdx() {
      return mdx;
    }

    /** The cube the MDX queries. */
    public String cube() {
      return cube;
    }

    /** The dimension fields kept, in ROWS-tuple order. */
    public List<ResolvedField> dimensionFields() {
      return dimensionFields;
    }

    /** The measure fields kept, in COLUMNS order. */
    public List<ResolvedField> measureFields() {
      return measureFields;
    }

    /** The Looker fields excluded for lack of CLEAN provenance (never faked). */
    public List<String> skippedFields() {
      return skippedFields;
    }

    /** Looker dimension qname &rarr; ROWS-tuple position (0-based). */
    public Map<String, Integer> dimensionAxisOrder() {
      final Map<String, Integer> order = new LinkedHashMap<>();
      for (int i = 0; i < dimensionFields.size(); i++) {
        order.put(dimensionFields.get(i).lookerField(), i);
      }
      return Collections.unmodifiableMap(order);
    }
  }
}

// End LookerQueryToMdx.java
