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

import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable per-construct provenance: maps a LookML qualified name (the same
 * {@code qualifiedName()} the classifier's {@code CoverageRecord} uses, e.g.
 * {@code explore:orders}, {@code orders.total_amount}, {@code users.country})
 * to the path of the M4 element the transpiler emitted for it.
 *
 * <p>The M4 element path is a stable, human-readable locator into the emitted
 * schema, e.g. {@code cube:orders/measureGroup:orders/measure:total_amount} or
 * {@code cube:orders/dimension:users/attribute:country}. The {@code #102}
 * coverage report joins this map with the {@link
 * mondrian.lookml.model.ClassificationResult} on the qualified name to bucket
 * every construct as CLEAN/DEGRADE/REFUSE alongside the M4 it produced.
 */
public final class ProvenanceMap {
  private final ImmutableMap<String, String> byQualifiedName;

  private ProvenanceMap(Map<String, String> byQualifiedName) {
    this.byQualifiedName = ImmutableMap.copyOf(byQualifiedName);
  }

  /** The M4 element path emitted for the given LookML qualified name, or
   * empty if nothing was emitted (e.g. the construct was REFUSED). */
  public Optional<String> m4Path(String qualifiedName) {
    requireNonNull(qualifiedName, "qualifiedName");
    return Optional.ofNullable(byQualifiedName.get(qualifiedName));
  }

  /** The full LookML-qualified-name &rarr; M4-element-path mapping. */
  public ImmutableMap<String, String> entries() {
    return byQualifiedName;
  }

  /** Number of emitted constructs. */
  public int size() {
    return byQualifiedName.size();
  }

  @Override public String toString() {
    return "ProvenanceMap" + byQualifiedName;
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ProvenanceMap}. Mutable while building. */
  public static final class Builder {
    // LinkedHashMap-backed via ImmutableMap.Builder to preserve emit order.
    private final ImmutableMap.Builder<String, String> map =
        ImmutableMap.builder();

    private Builder() {}

    /** Records that {@code qualifiedName} produced the M4 element at
     * {@code m4Path}. A qualified name must be recorded at most once. */
    public Builder put(String qualifiedName, String m4Path) {
      map.put(requireNonNull(qualifiedName, "qualifiedName"),
          requireNonNull(m4Path, "m4Path"));
      return this;
    }

    /** Builds the immutable provenance map. */
    public ProvenanceMap build() {
      return new ProvenanceMap(map.buildKeepingLast());
    }
  }
}

// End ProvenanceMap.java
