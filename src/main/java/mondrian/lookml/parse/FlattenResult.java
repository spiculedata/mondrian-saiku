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

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The output of {@link LookmlFlattener}: a single RESOLVED LookML document
 * (with {@code include}/{@code extends}/refinements/{@code @{}} applied) that
 * the existing classifier and transpiler consume unchanged, plus any
 * {@link FlattenDiagnostic}s recorded for references that could not be resolved.
 */
public final class FlattenResult {
  private final LookmlNode document;
  private final ImmutableList<FlattenDiagnostic> diagnostics;

  FlattenResult(LookmlNode document, List<FlattenDiagnostic> diagnostics) {
    this.document = requireNonNull(document, "document");
    this.diagnostics = ImmutableList.copyOf(diagnostics);
  }

  /** The flattened, fully-resolved document. */
  public LookmlNode document() {
    return document;
  }

  /** Diagnostics for references that could not be resolved (never silently
   * dropped). Empty when everything resolved cleanly. */
  public List<FlattenDiagnostic> diagnostics() {
    return diagnostics;
  }
}

// End FlattenResult.java
