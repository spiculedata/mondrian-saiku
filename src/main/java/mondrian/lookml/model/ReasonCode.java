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
package mondrian.lookml.model;

/**
 * Machine-stable taxonomy of reasons a LookML construct was classified as it
 * was (issue #98 / #100).
 *
 * <p>These names are part of the importer's public contract: the classifier
 * emits them, and the transpiler and coverage report consume them. Do not
 * rename existing codes; add new ones.
 *
 * <p>Several refusals become {@link Classification#CLEAN} once the corresponding
 * companion-epic model extension lands; the relevant issue is noted per code
 * and surfaced at runtime via {@link #flippedByIssue()}.
 */
public enum ReasonCode {
  // --- REFUSE: the constructs that decide viability -----------------------

  /** Explore join graph is not a star/snowflake of fact&rarr;dim left joins
   * (e.g. {@code full_outer}/{@code cross}/chained-many/unbridged
   * {@code many_to_many}); it would break structurally. */
  REFUSE_NON_STAR_TOPOLOGY(Classification.REFUSE, null),

  /** A {@code sum}/{@code average}/... measure on the "one" side of a
   * {@code one_to_many} the explore fans out: a symmetric-aggregate-dependent
   * measure that would be silently wrong. Becomes CLEAN once symmetric
   * aggregates land (#103). */
  REFUSE_FANOUT_SYMMETRIC_AGGREGATE(Classification.REFUSE, "#103"),

  /** Liquid templating ({@code {{ }}} / {@code {% %}}) in a {@code sql},
   * {@code filter} or {@code label}: not statically resolvable. */
  REFUSE_LIQUID(Classification.REFUSE, null),

  /** A {@code parameter} field used for field/SQL switching. Bounded,
   * enumerated parameters become CLEAN once query-context parameters land
   * (#105). */
  REFUSE_PARAMETER_FIELD(Classification.REFUSE, "#105"),

  /** A {@code type: median} or {@code type: percentile} measure
   * (non-additive aggregator). Becomes CLEAN once non-additive aggregators
   * land (#99 companion). */
  REFUSE_MEDIAN_PERCENTILE(Classification.REFUSE, "#99"),

  /** A {@code type: list} field (a multi-valued, non-OLAP field). */
  REFUSE_TYPE_LIST(Classification.REFUSE, null),

  /** An {@code access_filter} with an arbitrary predicate (not a simple
   * equality on a modelled dimension key). Becomes CLEAN once predicate-based
   * row security lands (#106). */
  REFUSE_ARBITRARY_ACCESS_FILTER(Classification.REFUSE, "#106"),

  /** A construct guarded by {@code required_access_grants}: visibility depends
   * on grants the importer cannot evaluate. */
  REFUSE_REQUIRED_ACCESS_GRANTS(Classification.REFUSE, null),

  // --- DEGRADE: emit, but record a lost capability ------------------------

  /** A derived table's persistence policy (PDT / {@code datagroup_trigger} /
   * {@code persist_for}) is dropped; emitted as a plain {@code <View>}. Maps to
   * the segment-cache story (#94/#95/#96). */
  DEGRADE_PDT_PERSISTENCE_DROPPED(Classification.DEGRADE, "#94"),

  /** A LookML {@code aggregate_table} is not converted to an M4 aggregate
   * table; queries still run against the base, just slower. */
  DEGRADE_AGGREGATE_TABLE_NOT_CONVERTED(Classification.DEGRADE, null),

  /** A {@code filters:} on a measure contains Liquid; the measure is emitted
   * without the filter (the filter capability is lost). */
  DEGRADE_FILTERED_MEASURE_LIQUID(Classification.DEGRADE, null),

  // --- CLEAN --------------------------------------------------------------

  /** The construct converts to M4 with full fidelity. */
  CLEAN(Classification.CLEAN, null);

  private final Classification classification;
  private final String flippedByIssue;

  ReasonCode(Classification classification, String flippedByIssue) {
    this.classification = classification;
    this.flippedByIssue = flippedByIssue;
  }

  /** The classification this reason code implies. */
  public Classification classification() {
    return classification;
  }

  /** The companion-epic issue whose model extension would flip this
   * {@code REFUSE} (or improve this {@code DEGRADE}) to {@code CLEAN}, or empty
   * if no extension changes it. */
  public java.util.Optional<String> flippedByIssue() {
    return java.util.Optional.ofNullable(flippedByIssue);
  }
}

// End ReasonCode.java
