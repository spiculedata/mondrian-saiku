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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Issue #128: an immutable Looker query specification — the explore plus the
 * dimension and measure fields (by Looker qualified name) and optional filters.
 *
 * <p>This is exactly the inline-query payload Looker's
 * {@code /api/4.0/queries/run/json} accepts ({@code model}/{@code view}/
 * {@code fields}/{@code filters}), and the input both
 * {@link LookerQueryToMdx} (Saiku side) and {@link LookerQueryClient} (live
 * Looker side) drive from, so the two sides ask the SAME question.
 *
 * <p>Dimensions and measures are kept separate (not merged into one field list)
 * because the MDX rewrite puts measures on COLUMNS and dimension levels on ROWS
 * — the split is semantic, not cosmetic.
 */
public final class LookerQuerySpec {

  private final String model;
  private final String explore;
  private final List<String> dimensionFields;
  private final List<String> measureFields;
  private final Map<String, String> filters;

  private LookerQuerySpec(Builder b) {
    this.model = b.model;
    this.explore = requireNonNull(b.explore, "explore");
    this.dimensionFields =
        Collections.unmodifiableList(new ArrayList<>(b.dimensionFields));
    this.measureFields =
        Collections.unmodifiableList(new ArrayList<>(b.measureFields));
    this.filters =
        Collections.unmodifiableMap(new LinkedHashMap<>(b.filters));
  }

  /** The Looker model name (optional; used only by the live client). */
  public String model() {
    return model;
  }

  /** The Looker explore name (the {@code view} in the run-query payload). */
  public String explore() {
    return explore;
  }

  /** The ordered dimension field qnames (e.g. {@code users.country}). */
  public List<String> dimensionFields() {
    return dimensionFields;
  }

  /** The ordered measure field qnames (e.g. {@code orders.total_amount}). */
  public List<String> measureFields() {
    return measureFields;
  }

  /** The Looker filter expressions, by field qname (e.g.
   * {@code users.country -> "USA"}). */
  public Map<String, String> filters() {
    return filters;
  }

  /** All requested field qnames (dimensions then measures), in order. */
  public List<String> allFields() {
    final List<String> all =
        new ArrayList<>(dimensionFields.size() + measureFields.size());
    all.addAll(dimensionFields);
    all.addAll(measureFields);
    return Collections.unmodifiableList(all);
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link LookerQuerySpec}. Mutable while building. */
  public static final class Builder {
    private String model;
    private String explore;
    private final List<String> dimensionFields = new ArrayList<>();
    private final List<String> measureFields = new ArrayList<>();
    private final Map<String, String> filters = new LinkedHashMap<>();

    private Builder() {}

    /** Sets the Looker model name. */
    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** Sets the Looker explore name. */
    public Builder explore(String explore) {
      this.explore = explore;
      return this;
    }

    /** Adds a dimension field qname (e.g. {@code users.country}). */
    public Builder dimension(String fieldQname) {
      dimensionFields.add(requireNonNull(fieldQname, "fieldQname"));
      return this;
    }

    /** Adds a measure field qname (e.g. {@code orders.total_amount}). */
    public Builder measure(String fieldQname) {
      measureFields.add(requireNonNull(fieldQname, "fieldQname"));
      return this;
    }

    /** Adds a Looker filter expression on a field. */
    public Builder filter(String fieldQname, String expression) {
      filters.put(requireNonNull(fieldQname, "fieldQname"),
          requireNonNull(expression, "expression"));
      return this;
    }

    /** Builds the immutable spec. */
    public LookerQuerySpec build() {
      return new LookerQuerySpec(this);
    }
  }
}

// End LookerQuerySpec.java
