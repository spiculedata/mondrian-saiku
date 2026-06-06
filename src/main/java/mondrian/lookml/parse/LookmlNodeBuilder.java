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
package mondrian.lookml.parse;

import mondrian.lookml.parse.util.PairList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Public builder for synthesising a {@link LookmlNode} without going through the
 * text parser. It is the seam used by alternative front-ends — notably the
 * Looker SDK {@code LookmlModelExplore} JSON reader (issue #116, part B) — that
 * map a pre-resolved model into the same internal AST the parser produces, so
 * the classifier / transpiler / report pipeline is reused unchanged.
 *
 * <p>Only the value shapes the importer actually consumes are exposed:
 * identifiers / enum / boolean ({@code type}, {@code relationship},
 * {@code primary_key}), strings ({@code label}, {@code value_format}), code
 * blocks ({@code sql}, {@code sql_on}) and nested named/anonymous objects
 * (views, dimensions, measures, joins, access_filter). The builder is
 * mutate-then-build: it accumulates into a {@link PairList} and produces an
 * immutable {@link LookmlNode}.
 */
public final class LookmlNodeBuilder {
  private final String name;
  private final PairList<String, Value> properties = PairList.of();

  private LookmlNodeBuilder(String name) {
    this.name = name;
  }

  /** A builder for an unnamed object (e.g. a document root or {@code
   * access_filter: { ... }}). */
  public static LookmlNodeBuilder anonymous() {
    return new LookmlNodeBuilder(null);
  }

  /** A builder for a named object (e.g. {@code view: orders { ... }}). */
  public static LookmlNodeBuilder named(String name) {
    return new LookmlNodeBuilder(requireNonNull(name, "name"));
  }

  /** Adds an identifier / enum / boolean property (e.g. {@code type: sum}). */
  public LookmlNodeBuilder identifier(String key, String value) {
    if (value != null) {
      properties.add(key, Values.identifier(value));
    }
    return this;
  }

  /** Adds a string property (e.g. {@code label: "Order Name"}). */
  public LookmlNodeBuilder string(String key, String value) {
    if (value != null) {
      properties.add(key, Values.string(value));
    }
    return this;
  }

  /** Adds a {@code ;;}-terminated code property (e.g. {@code sql}/{@code
   * sql_on}). */
  public LookmlNodeBuilder code(String key, String value) {
    if (value != null) {
      properties.add(key, Values.code(value));
    }
    return this;
  }

  /** Adds a list-of-identifiers property (e.g. {@code fields: [a, b]}). */
  public LookmlNodeBuilder identifierList(String key, List<String> values) {
    if (values != null && !values.isEmpty()) {
      final java.util.List<ValueImpl> items = new java.util.ArrayList<>();
      for (String v : values) {
        items.add(Values.identifier(v));
      }
      properties.add(key, Values.list(items));
    }
    return this;
  }

  /** Adds a nested object property built by another builder (named or not). */
  public LookmlNodeBuilder object(String key, LookmlNodeBuilder child) {
    requireNonNull(child, "child");
    properties.add(key, child.build().toValue());
    return this;
  }

  /** Builds the immutable node. */
  public LookmlNode build() {
    final PairList<String, Value> copy = PairList.of();
    properties.forEach((k, v) -> copy.add(k, v));
    return LookmlNode.of(name, copy);
  }
}

// End LookmlNodeBuilder.java
