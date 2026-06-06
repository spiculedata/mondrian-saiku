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

import mondrian.lookml.model.ClassificationResult;
import mondrian.schema.yaml.m4.M4YamlToXml;

import static java.util.Objects.requireNonNull;

/**
 * Immutable output of {@link LookmlTranspiler#transpile}: the emitted M4 schema
 * (as YAML, and on demand as the loadable XML) plus the {@link ProvenanceMap}
 * and the {@link ClassificationResult} the schema was derived from.
 *
 * <p>The YAML is the canonical artefact (golden-comparable); {@link #toXml()}
 * reuses the existing, tested {@link M4YamlToXml} converter to produce the
 * MondrianDef XML the engine loads — the transpiler hand-rolls no XML.
 */
public final class TranspileResult {
  private final String yaml;
  private final ProvenanceMap provenance;
  private final ClassificationResult classification;

  TranspileResult(String yaml, ProvenanceMap provenance,
      ClassificationResult classification) {
    this.yaml = requireNonNull(yaml, "yaml");
    this.provenance = requireNonNull(provenance, "provenance");
    this.classification = requireNonNull(classification, "classification");
  }

  /** The emitted M4 schema as YAML schema-as-code. */
  public String yaml() {
    return yaml;
  }

  /** The emitted M4 schema as loadable MondrianDef XML, converted from the
   * YAML by the existing {@link M4YamlToXml}. */
  public String toXml() {
    return M4YamlToXml.toXml(yaml);
  }

  /** The per-construct provenance: LookML qualified name &rarr; M4 path. */
  public ProvenanceMap provenance() {
    return provenance;
  }

  /** The classification the schema was derived from (CLEAN/DEGRADE emitted,
   * REFUSE skipped). The #102 report joins this with {@link #provenance()}. */
  public ClassificationResult classification() {
    return classification;
  }

  @Override public String toString() {
    return "TranspileResult{" + classification + ", provenance="
        + provenance.size() + " entries}";
  }
}

// End TranspileResult.java
