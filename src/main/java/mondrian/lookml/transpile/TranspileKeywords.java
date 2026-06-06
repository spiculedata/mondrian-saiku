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
  static final String DERIVED_TABLE = "derived_table";

  // --- scalar keys --------------------------------------------------------
  static final String TYPE = "type";
  static final String SQL = "sql";
  static final String SQL_ON = "sql_on";
  static final String SQL_TABLE_NAME = "sql_table_name";
  static final String FOREIGN_KEY = "foreign_key";
  static final String FROM = "from";
  static final String VIEW_NAME = "view_name";
  static final String RELATIONSHIP = "relationship";
  static final String FILTERS = "filters";
  static final String LABEL = "label";
  static final String DESCRIPTION = "description";
  static final String VALUE_FORMAT = "value_format";
  static final String VALUE_FORMAT_NAME = "value_format_name";

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
          .build();
}

// End TranspileKeywords.java
