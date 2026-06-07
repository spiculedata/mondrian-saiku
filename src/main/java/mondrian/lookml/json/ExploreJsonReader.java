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
package mondrian.lookml.json;

import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlNodeBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Looker SDK Explore-JSON front-end (issue #116, part B).
 *
 * <p>An ALTERNATIVE input path: ingests the already-resolved
 * {@code LookmlModelExplore} metadata that the Looker API returns (the
 * {@code lookml_model_explore} endpoint) and maps it into the same internal
 * {@link LookmlNode} AST the LookML text parser produces, so the existing
 * classifier / transpiler / report pipeline is reused unchanged.
 *
 * <p>Because the Explore JSON is the FLATTENED, fully-resolved model
 * (refinements / {@code extends} / constants / Liquid already applied by
 * Looker), this path sidesteps every parsing and resolution limitation of the
 * raw-{@code .lkml} path (it does not need the
 * {@link mondrian.lookml.parse.LookmlFlattener}).
 *
 * <p>Parsed with Jackson via plain {@link JsonNode} tree binding (no default
 * typing, no polymorphic deserialisation) — the JSON is treated as untrusted
 * data and only the keys the importer consumes are read.
 *
 * <h3>Mapping</h3>
 * <ul>
 *   <li>the explore &rarr; {@code explore: <name> { ... }} plus a synthesised
 *       {@code view:} per distinct field view;</li>
 *   <li>{@code joins[]} &rarr; {@code join:} blocks
 *       ({@code type}/{@code relationship}/{@code sql_on});</li>
 *   <li>{@code fields.dimensions[]} &rarr; {@code dimension:} (carrying
 *       {@code type}/{@code sql}/{@code primary_key}/{@code value_format});</li>
 *   <li>{@code fields.measures[]} &rarr; {@code measure:} (carrying
 *       {@code type}/{@code sql});</li>
 *   <li>{@code access_filters[]} &rarr; {@code access_filter:} blocks on the
 *       explore.</li>
 * </ul>
 */
public final class ExploreJsonReader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Reads a {@code LookmlModelExplore} JSON string into a document node. */
  public LookmlNode read(String json) {
    requireNonNull(json, "json");
    final JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("invalid LookmlModelExplore JSON", e);
    }
    return read(root);
  }

  /** Reads a parsed {@code LookmlModelExplore} JSON tree into a document. */
  public LookmlNode read(JsonNode root) {
    requireNonNull(root, "root");
    final String exploreName = text(root, "name", "explore");

    // Collect fields per view, splitting dimensions vs measures.
    final Map<String, ViewFields> views = new LinkedHashMap<>();
    collectFields(root.path("fields").path("dimensions"), views, false);
    collectFields(root.path("fields").path("measures"), views, true);

    // The base view: explicit if present, else the explore name.
    final String baseView = text(root, "view_name",
        text(root, "view", exploreName));
    views.computeIfAbsent(baseView, k -> new ViewFields());

    final LookmlNodeBuilder doc = LookmlNodeBuilder.anonymous();

    // One view: block per distinct field view.
    for (Map.Entry<String, ViewFields> e : views.entrySet()) {
      doc.object("view", buildView(e.getKey(), e.getValue()));
    }

    // The explore: with joins + access_filters.
    doc.object("explore", buildExplore(exploreName, baseView, root));

    return doc.build();
  }

  // --- views -------------------------------------------------------------

  private static void collectFields(JsonNode arr,
      Map<String, ViewFields> views, boolean measure) {
    if (!arr.isArray()) {
      return;
    }
    for (JsonNode f : arr) {
      // Skip fields Looker marks hidden? We keep them; classification is
      // metadata-driven and hidden fields still affect coverage.
      final String qualified = text(f, "name", null);
      if (qualified == null) {
        continue;
      }
      final String view = text(f, "view", viewOf(qualified));
      final String field = fieldOf(qualified);
      views.computeIfAbsent(view, k -> new ViewFields())
          .add(measure, field, f);
    }
  }

  private static LookmlNodeBuilder buildView(String name, ViewFields fields) {
    final LookmlNodeBuilder view = LookmlNodeBuilder.named(name);
    for (Map.Entry<String, JsonNode> e : fields.dimensions.entrySet()) {
      view.object("dimension", buildDimension(e.getKey(), e.getValue()));
    }
    for (Map.Entry<String, JsonNode> e : fields.measures.entrySet()) {
      view.object("measure", buildMeasure(e.getKey(), e.getValue()));
    }
    return view;
  }

  private static LookmlNodeBuilder buildDimension(String name, JsonNode f) {
    final LookmlNodeBuilder d = LookmlNodeBuilder.named(name)
        .identifier("type", text(f, "type", null))
        .code("sql", text(f, "sql", null))
        .string("value_format", text(f, "value_format", null));
    if (f.path("primary_key").asBoolean(false)) {
      d.identifier("primary_key", "yes");
    }
    return d;
  }

  private static LookmlNodeBuilder buildMeasure(String name, JsonNode f) {
    return LookmlNodeBuilder.named(name)
        .identifier("type", text(f, "type", null))
        .code("sql", text(f, "sql", null))
        .string("value_format", text(f, "value_format", null));
  }

  // --- explore -----------------------------------------------------------

  private static LookmlNodeBuilder buildExplore(String name, String baseView,
      JsonNode root) {
    final LookmlNodeBuilder explore = LookmlNodeBuilder.named(name);
    if (!name.equals(baseView)) {
      explore.identifier("from", baseView);
    }
    for (JsonNode j : root.path("joins")) {
      explore.object("join", buildJoin(j));
    }
    for (JsonNode af : root.path("access_filters")) {
      explore.object("access_filter", buildAccessFilter(af));
    }
    return explore;
  }

  private static LookmlNodeBuilder buildJoin(JsonNode j) {
    return LookmlNodeBuilder.named(text(j, "name", "join"))
        .identifier("type", text(j, "type", null))
        .identifier("relationship", text(j, "relationship", null))
        .code("sql_on", text(j, "sql_on", null));
  }

  private static LookmlNodeBuilder buildAccessFilter(JsonNode af) {
    return LookmlNodeBuilder.anonymous()
        .identifier("field", text(af, "field", null))
        .identifier("user_attribute", text(af, "user_attribute", null))
        .string("value", text(af, "value", null));
  }

  // --- helpers -----------------------------------------------------------

  /** Per-view accumulated fields, preserving first-seen order. */
  private static final class ViewFields {
    private final Map<String, JsonNode> dimensions = new LinkedHashMap<>();
    private final Map<String, JsonNode> measures = new LinkedHashMap<>();

    private void add(boolean measure, String field, JsonNode f) {
      (measure ? measures : dimensions).putIfAbsent(field, f);
    }
  }

  private static String text(JsonNode n, String key, String dflt) {
    final JsonNode v = n.path(key);
    return v.isTextual() ? v.asText() : (v.isMissingNode() || v.isNull()
        ? dflt : v.asText(dflt));
  }

  private static String viewOf(String qualified) {
    final int dot = qualified.indexOf('.');
    return dot < 0 ? qualified : qualified.substring(0, dot);
  }

  private static String fieldOf(String qualified) {
    final int dot = qualified.indexOf('.');
    return dot < 0 ? qualified : qualified.substring(dot + 1);
  }
}

// End ExploreJsonReader.java
