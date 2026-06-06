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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The growing M4 schema, as an ordered {@code Map} matching the M4 YAML
 * shape ({@code schema} / {@code physical_schema} / {@code cubes}). The
 * transpiler appends to it as it walks each explore; {@link #root()} returns
 * the finished map for serialisation.
 *
 * <p>Holds the physical-schema registry: the set of tables referenced by the
 * emitted cubes, each carrying the key columns the dimension links resolve
 * against, deduplicated by table name.
 */
final class M4SchemaModel {
  private final Map<String, Object> root = new LinkedHashMap<>();
  private final Map<String, Object> cubes = new LinkedHashMap<>();

  /** Top-level {@code <QueryParameter>} blocks (#105), deduped by name. */
  private final Map<String, Object> parameters = new LinkedHashMap<>();

  /** Top-level {@code <Role>} blocks (#106), deduped by name. */
  private final Map<String, Object> roles = new LinkedHashMap<>();

  /** table name → its declared key columns (insertion-ordered, deduped). */
  private final Map<String, Set<String>> tableKeys = new LinkedHashMap<>();

  M4SchemaModel(String schemaName, String metamodelVersion) {
    final Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("name", schemaName);
    schema.put("metamodel_version", metamodelVersion);
    root.put("schema", schema);
  }

  /** Registers a referenced physical table. A null/blank key is ignored; the
   * keys of a table accumulate across calls so a table used as both a fact and
   * a dimension declares every key it needs. */
  void registerTable(String table, String keyColumn) {
    if (table == null || table.isEmpty()) {
      return;
    }
    final Set<String> keys =
        tableKeys.computeIfAbsent(table, t -> new LinkedHashSet<>());
    if (keyColumn != null && !keyColumn.isEmpty()) {
      keys.add(keyColumn);
    }
  }

  /** Adds a built cube body under the given cube name. */
  void addCube(String cubeName, Map<String, Object> cubeBody) {
    cubes.put(cubeName, cubeBody);
  }

  /** Adds a top-level query-parameter block, deduped by name (#105). */
  void addParameter(String name, Map<String, Object> parameter) {
    if (name != null && !name.isEmpty()) {
      parameters.putIfAbsent(name, parameter);
    }
  }

  /** Adds (or returns) a top-level role block, deduped by name (#106). */
  void addRole(String name, Map<String, Object> role) {
    if (name != null && !name.isEmpty()) {
      roles.putIfAbsent(name, role);
    }
  }

  /** Whether a parameter with this name has already been declared. */
  boolean hasParameter(String name) {
    return parameters.containsKey(name);
  }

  /** Finishes and returns the ordered root map. Physical schema is emitted
   * from the table registry; cubes from the accumulated cube bodies. */
  Map<String, Object> root() {
    root.put("physical_schema", buildPhysicalSchema());
    if (!parameters.isEmpty()) {
      root.put("parameters", new ArrayList<>(parameters.values()));
    }
    if (!cubes.isEmpty()) {
      root.put("cubes", cubes);
    }
    if (!roles.isEmpty()) {
      root.put("roles", new ArrayList<>(roles.values()));
    }
    return root;
  }

  private Map<String, Object> buildPhysicalSchema() {
    final List<Object> tables = new ArrayList<>();
    for (Map.Entry<String, Set<String>> e : tableKeys.entrySet()) {
      final Map<String, Object> t = new LinkedHashMap<>();
      t.put("name", e.getKey());
      if (!e.getValue().isEmpty()) {
        t.put("key", new ArrayList<>(e.getValue()));
      }
      tables.add(t);
    }
    final Map<String, Object> phys = new LinkedHashMap<>();
    phys.put("tables", tables);
    return phys;
  }
}

// End M4SchemaModel.java
