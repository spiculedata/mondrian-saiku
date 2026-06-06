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
package mondrian.lookml.model;

import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable record of how one LookML construct was classified.
 *
 * <p>This is the unit the classifier (#100) emits and the transpiler (#101) and
 * coverage report (#102) consume. It is a pure data type with no dependency on
 * the parser or the engine.
 */
public final class CoverageRecord {
  private final Scope scope;
  private final String qualifiedName;
  private final String sourceRef;
  private final Classification classification;
  private final ReasonCode reasonCode;
  private final String reason;
  private final String producedM4;
  private final String lostCapability;
  private final String relatedIssue;

  private CoverageRecord(Builder b) {
    this.scope = requireNonNull(b.scope, "scope");
    this.qualifiedName = requireNonNull(b.qualifiedName, "qualifiedName");
    this.classification = requireNonNull(b.classification, "classification");
    this.reasonCode = requireNonNull(b.reasonCode, "reasonCode");
    this.reason = requireNonNull(b.reason, "reason");
    this.sourceRef = b.sourceRef;
    this.producedM4 = b.producedM4;
    this.lostCapability = b.lostCapability;
    this.relatedIssue = b.relatedIssue;
  }

  /** The kind of construct this record is about. */
  public Scope scope() {
    return scope;
  }

  /** A stable, human-readable identifier, e.g. {@code orders.total_revenue}. */
  public String qualifiedName() {
    return qualifiedName;
  }

  /** Where the construct came from (file and/or line), if known. */
  public Optional<String> sourceRef() {
    return Optional.ofNullable(sourceRef);
  }

  /** CLEAN / DEGRADE / REFUSE. */
  public Classification classification() {
    return classification;
  }

  /** The machine-stable reason this classification was assigned. */
  public ReasonCode reasonCode() {
    return reasonCode;
  }

  /** A human-readable explanation, suitable for the coverage report. */
  public String reason() {
    return reason;
  }

  /** A description / reference of the M4 that was emitted, if any. */
  public Optional<String> producedM4() {
    return Optional.ofNullable(producedM4);
  }

  /** What capability was lost (for DEGRADE), if any. */
  public Optional<String> lostCapability() {
    return Optional.ofNullable(lostCapability);
  }

  /** The companion-epic issue that would improve this outcome, if any. */
  public Optional<String> relatedIssue() {
    return Optional.ofNullable(relatedIssue);
  }

  /** Creates a builder for the given scope, name, classification and reason. */
  public static Builder builder(Scope scope, String qualifiedName,
      ReasonCode reasonCode, String reason) {
    return new Builder(scope, qualifiedName, reasonCode, reason);
  }

  @Override public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CoverageRecord)) {
      return false;
    }
    final CoverageRecord that = (CoverageRecord) o;
    return scope == that.scope
        && qualifiedName.equals(that.qualifiedName)
        && Objects.equals(sourceRef, that.sourceRef)
        && classification == that.classification
        && reasonCode == that.reasonCode
        && reason.equals(that.reason)
        && Objects.equals(producedM4, that.producedM4)
        && Objects.equals(lostCapability, that.lostCapability)
        && Objects.equals(relatedIssue, that.relatedIssue);
  }

  @Override public int hashCode() {
    return Objects.hash(scope, qualifiedName, sourceRef, classification,
        reasonCode, reason, producedM4, lostCapability, relatedIssue);
  }

  @Override public String toString() {
    return classification + " " + scope + " " + qualifiedName
        + " [" + reasonCode + "] " + reason;
  }

  /** Mutable builder for {@link CoverageRecord}; {@link #build()} produces an
   * immutable instance. The classification is derived from the reason code so
   * the two can never disagree. */
  public static final class Builder {
    private final Scope scope;
    private final String qualifiedName;
    private final ReasonCode reasonCode;
    private final Classification classification;
    private final String reason;
    private String sourceRef;
    private String producedM4;
    private String lostCapability;
    private String relatedIssue;

    private Builder(Scope scope, String qualifiedName, ReasonCode reasonCode,
        String reason) {
      this.scope = scope;
      this.qualifiedName = qualifiedName;
      this.reasonCode = reasonCode;
      this.reason = reason;
      this.classification =
          reasonCode == null ? null : reasonCode.classification();
      // Pre-fill the related issue from the reason code's known flip target.
      this.relatedIssue =
          reasonCode == null ? null : reasonCode.flippedByIssue().orElse(null);
    }

    /** Sets the source reference (file/line). */
    public Builder sourceRef(String sourceRef) {
      this.sourceRef = sourceRef;
      return this;
    }

    /** Sets a description of the M4 produced. */
    public Builder producedM4(String producedM4) {
      this.producedM4 = producedM4;
      return this;
    }

    /** Sets the lost capability (typically for DEGRADE). */
    public Builder lostCapability(String lostCapability) {
      this.lostCapability = lostCapability;
      return this;
    }

    /** Overrides the related companion-epic issue. */
    public Builder relatedIssue(String relatedIssue) {
      this.relatedIssue = relatedIssue;
      return this;
    }

    /** Builds the immutable record. */
    public CoverageRecord build() {
      return new CoverageRecord(this);
    }
  }
}

// End CoverageRecord.java
