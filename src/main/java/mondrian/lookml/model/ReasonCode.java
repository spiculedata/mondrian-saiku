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
   * measure that would be silently wrong without fan-out-safe aggregation.
   *
   * <p>Symmetric (fan-out-safe) aggregation shipped in 4.8.1.x (#103), so the
   * common single-grain fan-out now classifies CLEAN (the measure-group grain
   * lets the engine aggregate safely). This code is retained for the cases the
   * importer still cannot emit safely: a genuine, unbridged many-to-many fan-out
   * that needs a {@code <BridgeLink>} the importer cannot synthesise yet (see
   * {@link #DEGRADE_FANOUT_BRIDGE_PARTIAL}, #107). Refuse, never silently wrong.
   *
   * <p>Flip target stays {@code #103}: symmetric aggregation is the headline
   * mechanism that turns fan-out refusals CLEAN; the residual unbridged m2m
   * subset is finished by the bridge, #107. */
  REFUSE_FANOUT_SYMMETRIC_AGGREGATE(Classification.REFUSE, "#103"),

  /** Liquid templating ({@code {{ }}} / {@code {% %}}) in a {@code sql},
   * {@code filter} or {@code label}: not statically resolvable. This still
   * covers a {@code parameter}'s <em>use</em> in {@code {% parameter %}}
   * field-switching SQL — only a parameter's bounded <em>declaration</em> maps
   * to {@link #CLEAN} (a {@code <QueryParameter>}, #105). */
  REFUSE_LIQUID(Classification.REFUSE, null),

  /** A {@code type: list} field (a multi-valued, non-OLAP field). */
  REFUSE_TYPE_LIST(Classification.REFUSE, null),

  /** A measure with an aggregator that has no static M4 mapping
   * (e.g. {@code sum_distinct}/{@code average_distinct}/{@code
   * percentile_distinct}); emitting it would be silently wrong. */
  REFUSE_UNSUPPORTED_AGGREGATOR(Classification.REFUSE, null),

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

  // --- DEGRADE: formerly REFUSE, now emitted with a caveat (shipped #99) ---

  /** A {@code type: median}/{@code percentile} measure. Non-additive
   * aggregators shipped in 4.8.1.x (#104), so the measure is now emitted as an
   * M4 {@code aggregator="median"}/{@code "percentile"} measure. It DEGRADEs
   * (not CLEAN) only because the runtime requires a {@code PERCENTILE_CONT}-
   * capable backend; the importer cannot know the target dialect at import
   * time. */
  DEGRADE_PERCENTILE_DIALECT(Classification.DEGRADE, "#104"),

  /** An {@code access_filter} on an arbitrary fact <em>column</em> (not a
   * modelled dimension key). Predicate-based row security shipped in 4.8.1.x
   * (#106), so it is now emitted as a {@code <PredicateGrant>} on a generated
   * {@code <Role>}, bound to a query-context parameter (the user attribute). It
   * DEGRADEs because the user-attribute value binding is supplied at query time,
   * not by the imported model. */
  DEGRADE_PREDICATE_ROW_SECURITY(Classification.DEGRADE, "#106"),

  /** A field carries Liquid that matches one of the <em>bounded</em>, enumerable
   * patterns the importer can route to a shipped feature instead of refusing
   * wholesale (#118): a {@code {{ _user_attributes['x'] }}} reference (&rarr; a
   * {@code session.x} {@code <QueryParameter>}, and a {@code <PredicateGrant>}
   * when it restricts a real fact column, #105/#106); a {@code {% parameter X %}}
   * use of a declared bounded parameter (field-switching becomes a Saiku-layer
   * {@code WITH MEMBER}, not engine SQL, #105); or a {@code {% condition Y %}}
   * parameter-bound filter (#105). It DEGRADEs (not CLEAN) because the templated
   * fragment itself is dropped — only the typed, enumerated, bind-only construct
   * is emitted; the value is supplied at query time through the #105 sandbox. */
  DEGRADE_LIQUID_BOUNDED(Classification.DEGRADE, "#118"),

  /** A fan-out additive aggregate the importer cannot fully bridge: the
   * fan-out-safe (#103) measure-group grain is emitted, but a genuine
   * many-to-many that needs a {@code <BridgeLink>} (#107) is only partially
   * handled (the bridge link is not synthesised). The numbers may be wrong for
   * the m2m case, so callers should treat this as a known gap. */
  DEGRADE_FANOUT_BRIDGE_PARTIAL(Classification.DEGRADE, "#107"),

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
