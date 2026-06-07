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

import com.google.common.collect.ImmutableMap;

/**
 * LookML keyword constants and LookML&rarr;M4 mappings used by the transpiler,
 * so no magic strings leak into the emit logic. The classifier's own
 * {@code LookmlKeywords} is package-private to that package; these are the
 * transpile-specific constants (some intentionally overlap, kept local to keep
 * the two packages decoupled).
 */
final class TranspileKeywords {
  private TranspileKeywords() {}

  // --- top-level / nested objects ----------------------------------------
  static final String EXPLORE = "explore";
  static final String VIEW = "view";
  static final String JOIN = "join";
  static final String MEASURE = "measure";
  static final String DIMENSION = "dimension";
  static final String DIMENSION_GROUP = "dimension_group";
  static final String PARAMETER = "parameter";
  static final String ACCESS_FILTER = "access_filter";
  static final String DERIVED_TABLE = "derived_table";
  static final String DRILL_FIELDS = "drill_fields";

  // --- scalar keys --------------------------------------------------------
  static final String TYPE = "type";
  static final String SQL = "sql";
  static final String SQL_ON = "sql_on";
  static final String SQL_TABLE_NAME = "sql_table_name";
  static final String SQL_START = "sql_start";
  static final String SQL_END = "sql_end";
  static final String FOREIGN_KEY = "foreign_key";
  static final String FROM = "from";
  static final String VIEW_NAME = "view_name";
  static final String RELATIONSHIP = "relationship";
  static final String PRIMARY_KEY = "primary_key";
  static final String FILTERS = "filters";
  static final String TIERS = "tiers";
  static final String INTERVALS = "intervals";
  static final String PERCENTILE = "percentile";
  // #119: the de-dup key on a sum_distinct / average_distinct measure.
  static final String SQL_DISTINCT_KEY = "sql_distinct_key";
  static final String TYPE_SUM_DISTINCT = "sum_distinct";
  static final String TYPE_AVERAGE_DISTINCT = "average_distinct";
  static final String FIELD = "field";
  static final String USER_ATTRIBUTE = "user_attribute";
  static final String LABEL = "label";
  static final String DESCRIPTION = "description";
  static final String DEFAULT_VALUE = "default_value";
  static final String ALLOWED_VALUE = "allowed_value";
  static final String VALUE = "value";
  static final String VALUE_FORMAT = "value_format";
  static final String VALUE_FORMAT_NAME = "value_format_name";

  // --- relationship cardinality that fans the fact out -------------------
  static final String REL_ONE_TO_MANY = "one_to_many";

  // --- dimension types mapped to native M4 binning / duration (#108) -----
  static final String TYPE_TIER = "tier";
  static final String TYPE_DURATION = "duration";

  // --- percentile-family aggregators (#104) ------------------------------
  static final String AGG_MEDIAN = "median";
  static final String AGG_PERCENTILE = "percentile";
  static final int DEFAULT_PERCENTILE = 50;

  // --- the ${TABLE}. prefix a dim/measure sql strips to a bare column -----
  static final String TABLE_REF_PREFIX = "${TABLE}.";

  // --- relationship cardinalities (fact-side) ----------------------------
  static final String REL_MANY_TO_ONE = "many_to_one";
  static final String REL_ONE_TO_ONE = "one_to_one";

  // --- M4 dimension_link types -------------------------------------------
  static final String LINK_FOREIGN_KEY = "foreign_key";
  static final String LINK_FACT = "fact";

  /** The Measures dimension every cube has, host of calculated members. */
  static final String MEASURES_DIMENSION = "Measures";

  /**
   * LookML measure {@code type} &rarr; M4 {@code aggregator}. Only the
   * additive / safe aggregators the classifier lets through reach here;
   * anything else is filtered upstream (REFUSED) or skipped defensively.
   */
  static final ImmutableMap<String, String> AGGREGATOR_BY_TYPE =
      ImmutableMap.<String, String>builder()
          .put("sum", "sum")
          .put("count", "count")
          .put("min", "min")
          .put("max", "max")
          .put("average", "avg")
          .put("avg", "avg")
          .put("count_distinct", "distinct-count")
          .put("median", "median")
          .put("percentile", "percentile")
          // #117: a sum_distinct / average_distinct only reaches the emitter
          // when the classifier proved its sql_distinct_key de-duplicates on
          // the base view primary key (one row per key → de-dup is a no-op),
          // so it collapses to a plain SUM / AVG. Fan-out safety is supplied by
          // the #103 symmetric path (the fact grain key is registered already).
          .put("sum_distinct", "sum")
          .put("average_distinct", "avg")
          .build();
}

// End TranspileKeywords.java
