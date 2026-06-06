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
package mondrian.lookml.classify;

import com.google.common.collect.ImmutableSet;

/**
 * Centralised LookML keyword constants used by the classifier, so no magic
 * strings leak into the detection logic.
 */
final class LookmlKeywords {
  private LookmlKeywords() {}

  // --- top-level objects --------------------------------------------------
  static final String EXPLORE = "explore";
  static final String VIEW = "view";
  static final String MODEL = "model";

  // --- nested objects -----------------------------------------------------
  static final String JOIN = "join";
  static final String MEASURE = "measure";
  static final String DIMENSION = "dimension";
  static final String DIMENSION_GROUP = "dimension_group";
  static final String PARAMETER = "parameter";
  static final String DERIVED_TABLE = "derived_table";
  static final String ACCESS_FILTER = "access_filter";
  static final String AGGREGATE_TABLE = "aggregate_table";

  // --- common scalar keys -------------------------------------------------
  static final String TYPE = "type";
  static final String RELATIONSHIP = "relationship";
  static final String FROM = "from";
  static final String VIEW_NAME = "view_name";
  static final String FIELD = "field";
  static final String FILTERS = "filters";
  static final String LABEL = "label";
  static final String HTML = "html";
  static final String SQL = "sql";
  static final String REQUIRED_ACCESS_GRANTS = "required_access_grants";

  // --- persistence keys on a derived_table --------------------------------
  static final ImmutableSet<String> PERSISTENCE_KEYS =
      ImmutableSet.of("datagroup_trigger", "persist_for", "sql_trigger_value");

  // --- join "type" values that break a star ------------------------------
  static final String JOIN_TYPE_FULL_OUTER = "full_outer";
  static final String JOIN_TYPE_CROSS = "cross";
  static final String JOIN_TYPE_LEFT_OUTER = "left_outer";
  static final String JOIN_TYPE_INNER = "inner";

  // --- relationship cardinalities ----------------------------------------
  static final String REL_MANY_TO_ONE = "many_to_one";
  static final String REL_ONE_TO_ONE = "one_to_one";
  static final String REL_ONE_TO_MANY = "one_to_many";
  static final String REL_MANY_TO_MANY = "many_to_many";

  // --- additive aggregate measure types (symmetric-aggregate sensitive) ---
  static final ImmutableSet<String> ADDITIVE_AGGREGATE_TYPES =
      ImmutableSet.of("sum", "average", "avg", "count");

  // --- measure types that are refused outright ----------------------------
  static final ImmutableSet<String> NON_ADDITIVE_REFUSED_TYPES =
      ImmutableSet.of("median", "percentile",
          "percentile_distinct", "average_distinct", "sum_distinct");

  static final String TYPE_LIST = "list";

  /** SQL-bearing / templated keys to scan for Liquid on a field. */
  static final ImmutableSet<String> LIQUID_SCAN_KEYS =
      ImmutableSet.of(SQL, "sql_distinct_key", FILTERS, LABEL, HTML,
          "value_format", "value_format_name");
}

// End LookmlKeywords.java
