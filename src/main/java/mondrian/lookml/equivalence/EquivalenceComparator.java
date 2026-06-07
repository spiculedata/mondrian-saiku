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

import mondrian.lookml.equivalence.LookerQueryToMdx.Plan;
import mondrian.lookml.equivalence.LookerQueryToMdx.ResolvedField;
import mondrian.olap.Axis;
import mondrian.olap.Cell;
import mondrian.olap.Position;
import mondrian.olap.Result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Issue #128: aligns a {@link LookerQueryResult} oracle against a Mondrian
 * {@link Result} (the converted cube's answer to the same query) and reports
 * numerical divergences — the migration analogue of the #90 Calcite parity
 * guard.
 *
 * <p>Alignment: both sides are keyed by the dimension-key tuple (the ordered
 * member names of the ROWS axis on the Saiku side; the corresponding Looker
 * dimension field values on the oracle side). For each shared key, each
 * requested measure is compared within a relative float tolerance.
 *
 * <p>Divergence categories are deliberately low-cardinality and name the
 * field/category only — NEVER the underlying values (potential PII, per #90):
 * <ul>
 *   <li>{@code ROW_COUNT} — the two sides have a different number of rows;</li>
 *   <li>{@code DIMENSION_SET} — a dimension-key tuple present on one side and
 *       absent on the other;</li>
 *   <li>{@code MEASURE_VALUE} — a shared key's measure differs beyond
 *       tolerance.</li>
 * </ul>
 */
public final class EquivalenceComparator {

  /** Default relative tolerance for measure comparison (#128). */
  public static final double DEFAULT_RELATIVE_TOLERANCE = 1e-6;

  private final double relativeTolerance;

  public EquivalenceComparator() {
    this(DEFAULT_RELATIVE_TOLERANCE);
  }

  public EquivalenceComparator(double relativeTolerance) {
    if (relativeTolerance < 0) {
      throw new IllegalArgumentException("tolerance must be non-negative");
    }
    this.relativeTolerance = relativeTolerance;
  }

  /**
   * Compares the {@code oracle} (Looker) against the {@code saiku} engine
   * result for the given {@code plan}.
   *
   * <p>The plan supplies the dimension/measure axis order so a Saiku ROWS tuple
   * (member names) can be matched to an oracle row (Looker dimension field
   * values). When the plan has no dimensions, both sides are single-row and the
   * measures are compared on COLUMNS directly.
   */
  public ComparisonResult compare(LookerQueryResult oracle, Result saiku,
      Plan plan) {
    requireNonNull(oracle, "oracle");
    requireNonNull(saiku, "saiku");
    requireNonNull(plan, "plan");

    final Map<String, double[]> oracleByKey = indexOracle(oracle, plan);
    final Map<String, double[]> saikuByKey = indexSaiku(saiku, plan);

    final List<Divergence> divergences = new ArrayList<>();

    if (oracleByKey.size() != saikuByKey.size()) {
      divergences.add(new Divergence(DivergenceCategory.ROW_COUNT, "<rows>",
          "oracle=" + oracleByKey.size() + " saiku=" + saikuByKey.size()));
    }

    // DIMENSION_SET: keys present on one side only.
    for (String key : oracleByKey.keySet()) {
      if (!saikuByKey.containsKey(key)) {
        divergences.add(new Divergence(DivergenceCategory.DIMENSION_SET,
            "<row>", "present in oracle, absent in saiku"));
      }
    }
    for (String key : saikuByKey.keySet()) {
      if (!oracleByKey.containsKey(key)) {
        divergences.add(new Divergence(DivergenceCategory.DIMENSION_SET,
            "<row>", "present in saiku, absent in oracle"));
      }
    }

    // MEASURE_VALUE: per shared key, per measure, beyond tolerance.
    final List<ResolvedField> measures = plan.measureFields();
    for (Map.Entry<String, double[]> e : oracleByKey.entrySet()) {
      final double[] saikuVals = saikuByKey.get(e.getKey());
      if (saikuVals == null) {
        continue; // already reported as DIMENSION_SET
      }
      final double[] oracleVals = e.getValue();
      for (int i = 0; i < measures.size(); i++) {
        if (!withinTolerance(oracleVals[i], saikuVals[i])) {
          divergences.add(new Divergence(DivergenceCategory.MEASURE_VALUE,
              measures.get(i).lookerField(), "beyond tolerance"));
        }
      }
    }
    return new ComparisonResult(divergences);
  }

  /** Indexes the oracle rows by their dimension-key tuple, with the measure
   * values (in plan measure order) as the value. */
  private Map<String, double[]> indexOracle(LookerQueryResult oracle,
      Plan plan) {
    final List<ResolvedField> dims = plan.dimensionFields();
    final List<ResolvedField> measures = plan.measureFields();
    final Map<String, double[]> index = new LinkedHashMap<>();
    for (Map<String, Object> row : oracle.rows()) {
      final List<String> keyParts = new ArrayList<>(dims.size());
      for (ResolvedField dim : dims) {
        keyParts.add(stringKey(row.get(dim.lookerField())));
      }
      final double[] vals = new double[measures.size()];
      for (int i = 0; i < measures.size(); i++) {
        vals[i] = toDouble(row.get(measures.get(i).lookerField()));
      }
      index.put(tupleKey(keyParts), vals);
    }
    return index;
  }

  /** Indexes the Saiku result cells by the ROWS-axis member-name tuple, with
   * the measure values (in plan measure / COLUMNS order). */
  private Map<String, double[]> indexSaiku(Result saiku, Plan plan) {
    final int measureCount = plan.measureFields().size();
    final Axis[] axes = saiku.getAxes();
    final Map<String, double[]> index = new LinkedHashMap<>();

    if (plan.dimensionFields().isEmpty()) {
      // No ROWS axis: a single implicit row keyed by the empty tuple.
      final double[] vals = new double[measureCount];
      for (int c = 0; c < measureCount; c++) {
        vals[c] = cellDouble(saiku, new int[]{c});
      }
      index.put(tupleKey(Collections.emptyList()), vals);
      return index;
    }

    final List<Position> rows = axes[1].getPositions();
    for (int r = 0; r < rows.size(); r++) {
      final Position pos = rows.get(r);
      final List<String> keyParts = new ArrayList<>(pos.size());
      for (int d = 0; d < pos.size(); d++) {
        keyParts.add(pos.get(d).getName());
      }
      final double[] vals = new double[measureCount];
      for (int c = 0; c < measureCount; c++) {
        vals[c] = cellDouble(saiku, new int[]{c, r});
      }
      index.put(tupleKey(keyParts), vals);
    }
    return index;
  }

  private static double cellDouble(Result result, int[] coords) {
    final Cell cell = result.getCell(coords);
    final Object v = cell.getValue();
    return v == null ? 0.0 : ((Number) v).doubleValue();
  }

  private boolean withinTolerance(double a, double b) {
    final double diff = Math.abs(a - b);
    if (diff == 0.0) {
      return true;
    }
    final double scale = Math.max(Math.abs(a), Math.abs(b));
    if (scale == 0.0) {
      return diff <= relativeTolerance;
    }
    return diff / scale <= relativeTolerance;
  }

  private static double toDouble(Object v) {
    if (v == null) {
      return 0.0;
    }
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    return Double.parseDouble(v.toString());
  }

  /** Normalises a dimension cell value to its string key (Looker labels and
   * Mondrian member names align on the rendered label). */
  private static String stringKey(Object v) {
    return v == null ? "\u0000null" : v.toString();
  }

  private static String tupleKey(List<String> parts) {
    // Unit-separated so member labels containing the separator can't collide.
    return String.join("", parts);
  }

  /** A divergence category — low-cardinality, value-free (#90/#128). */
  public enum DivergenceCategory {
    ROW_COUNT,
    DIMENSION_SET,
    MEASURE_VALUE
  }

  /** A single divergence: the category and the affected field name only — no
   * raw value is ever carried (#90 PII discipline). */
  public static final class Divergence {
    private final DivergenceCategory category;
    private final String field;
    private final String detail;

    Divergence(DivergenceCategory category, String field, String detail) {
      this.category = category;
      this.field = field;
      this.detail = detail;
    }

    /** The divergence category. */
    public DivergenceCategory category() {
      return category;
    }

    /** The affected field/dimension name (never the value). */
    public String field() {
      return field;
    }

    /** A value-free human detail (e.g. counts, "beyond tolerance"). */
    public String detail() {
      return detail;
    }

    @Override public String toString() {
      return category + "[" + field + "]: " + detail;
    }
  }

  /** The structured comparison outcome the test asserts on. */
  public static final class ComparisonResult {
    private final List<Divergence> divergences;

    ComparisonResult(List<Divergence> divergences) {
      this.divergences =
          Collections.unmodifiableList(new ArrayList<>(divergences));
    }

    /** True when the converted cube matched the oracle exactly (zero
     * divergences). */
    public boolean matched() {
      return divergences.isEmpty();
    }

    /** The divergences found (empty on a clean match). */
    public List<Divergence> divergences() {
      return divergences;
    }

    /** True when any divergence of the given category was found. */
    public boolean hasCategory(DivergenceCategory category) {
      return divergences.stream().anyMatch(d -> d.category() == category);
    }

    @Override public String toString() {
      return matched()
          ? "ComparisonResult{MATCH}"
          : "ComparisonResult{divergences=" + divergences + "}";
    }
  }
}

// End EquivalenceComparator.java
