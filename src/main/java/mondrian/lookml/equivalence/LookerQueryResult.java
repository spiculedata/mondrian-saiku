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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Issue #128: an immutable Looker query result — the known-correct oracle the
 * equivalence harness compares the converted cube against.
 *
 * <p>It mirrors the shape Looker's {@code /api/4.0/queries/run/json} returns: a
 * JSON array of objects, each object one result row keyed by the field's
 * qualified name (e.g. {@code "orders.total_amount"}, {@code "users.country"}).
 * A row therefore maps Looker field qname &rarr; cell value (dimension values
 * and measure values together). Rows are ordered.
 *
 * <p>The model is intentionally dumb: it does not know which fields are
 * dimensions vs measures (that split lives in the {@link LookerQuerySpec}). It
 * carries only the captured numbers/labels so a real Looker response can be
 * frozen as a test fixture via {@link #fromJson(String)}.
 */
public final class LookerQueryResult {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final List<Map<String, Object>> rows;

  private LookerQueryResult(List<Map<String, Object>> rows) {
    // Defensive deep-immutable copy: each row is an unmodifiable LinkedHashMap
    // (preserve field order), wrapped in an unmodifiable list.
    final List<Map<String, Object>> copy = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
    }
    this.rows = Collections.unmodifiableList(copy);
  }

  /** The ordered result rows; each is an unmodifiable field-qname &rarr; value
   * map. */
  public List<Map<String, Object>> rows() {
    return rows;
  }

  /** The number of result rows. */
  public int rowCount() {
    return rows.size();
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Parses a Looker {@code /api/4.0/queries/run/json} response (a JSON array of
   * row objects keyed by field qname) into a {@link LookerQueryResult}.
   *
   * <p>Looker's {@code json} format yields scalar values directly (numbers stay
   * numeric, dimension labels are strings, {@code null} is preserved). Values
   * are read losslessly: numbers as {@link Number}, text as {@link String}.
   */
  public static LookerQueryResult fromJson(String json) {
    requireNonNull(json, "json");
    final JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("invalid Looker run-query JSON", e);
    }
    if (!root.isArray()) {
      throw new IllegalArgumentException(
          "expected a JSON array of Looker result rows");
    }
    final Builder builder = builder();
    for (JsonNode rowNode : root) {
      if (!rowNode.isObject()) {
        throw new IllegalArgumentException(
            "expected each Looker result row to be a JSON object");
      }
      final Map<String, Object> row = new LinkedHashMap<>();
      rowNode.fields().forEachRemaining(
          e -> row.put(e.getKey(), scalarOf(e.getValue())));
      builder.row(row);
    }
    return builder.build();
  }

  private static Object scalarOf(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    return node.asText();
  }

  @Override public String toString() {
    // Low-cardinality: never dump the row VALUES (potential PII, per #90); only
    // the shape (row count) is safe to surface.
    return "LookerQueryResult{rows=" + rows.size() + "}";
  }

  /** Builder for {@link LookerQueryResult}. Mutable while building. */
  public static final class Builder {
    private final List<Map<String, Object>> rows = new ArrayList<>();

    private Builder() {}

    /** Appends a result row (field qname &rarr; value). */
    public Builder row(Map<String, Object> row) {
      rows.add(new LinkedHashMap<>(requireNonNull(row, "row")));
      return this;
    }

    /** Builds the immutable result. */
    public LookerQueryResult build() {
      return new LookerQueryResult(rows);
    }
  }
}

// End LookerQueryResult.java
