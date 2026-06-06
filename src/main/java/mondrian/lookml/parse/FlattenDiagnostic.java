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

import static java.util.Objects.requireNonNull;

/**
 * A single diagnostic emitted by {@link LookmlFlattener} when a cross-file
 * reference cannot be resolved (a missing {@code extends:} base, a refinement
 * ({@code +name}) with no base to layer onto, or an undeclared {@code @{...}}
 * constant).
 *
 * <p>Following the importer's safety contract (issue #98), an unresolvable
 * reference is RECORDED rather than silently dropped or guessed: the construct
 * is left as-parsed and a diagnostic is attached so the downstream coverage
 * report can surface it.
 */
public final class FlattenDiagnostic {

  /** The kind of unresolved reference. */
  public enum Kind {
    /** {@code extends: [base]} named a base that does not exist. */
    MISSING_EXTENDS_BASE,
    /** A refinement ({@code +name}) had no base object to layer onto. */
    DANGLING_REFINEMENT,
    /** A {@code @{name}} substitution referenced an undeclared constant. */
    UNDECLARED_CONSTANT
  }

  private final Kind kind;
  private final String objectType;
  private final String reference;
  private final String detail;

  private FlattenDiagnostic(Kind kind, String objectType, String reference,
      String detail) {
    this.kind = requireNonNull(kind, "kind");
    this.objectType = requireNonNull(objectType, "objectType");
    this.reference = requireNonNull(reference, "reference");
    this.detail = requireNonNull(detail, "detail");
  }

  /** Creates a diagnostic. */
  public static FlattenDiagnostic of(Kind kind, String objectType,
      String reference, String detail) {
    return new FlattenDiagnostic(kind, objectType, reference, detail);
  }

  /** The kind of unresolved reference. */
  public Kind kind() {
    return kind;
  }

  /** The LookML object type the reference appeared on (e.g. {@code view}). */
  public String objectType() {
    return objectType;
  }

  /** The unresolved reference text (e.g. a base name or {@code @{name}}). */
  public String reference() {
    return reference;
  }

  /** A human-readable detail message. */
  public String detail() {
    return detail;
  }

  @Override public String toString() {
    return kind + " [" + objectType + " " + reference + "]: " + detail;
  }
}

// End FlattenDiagnostic.java
