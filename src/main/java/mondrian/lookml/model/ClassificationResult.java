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

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Immutable result of classifying a LookML model: the ordered list of
 * {@link CoverageRecord}s.
 *
 * <p>The classifier (#100) produces this; the transpiler (#101) and the
 * coverage report (#102) both take it. It is a pure data type.
 */
public final class ClassificationResult {
  private final ImmutableList<CoverageRecord> records;

  private ClassificationResult(List<CoverageRecord> records) {
    this.records = ImmutableList.copyOf(records);
  }

  /** All coverage records, in the order they were added. */
  public ImmutableList<CoverageRecord> records() {
    return records;
  }

  /** Records with the given classification, in order. */
  public ImmutableList<CoverageRecord> withClassification(
      Classification classification) {
    requireNonNull(classification, "classification");
    final ImmutableList.Builder<CoverageRecord> b = ImmutableList.builder();
    for (CoverageRecord r : records) {
      if (r.classification() == classification) {
        b.add(r);
      }
    }
    return b.build();
  }

  /** Number of records with the given classification. */
  public long count(Classification classification) {
    requireNonNull(classification, "classification");
    return records.stream()
        .filter(r -> r.classification() == classification)
        .count();
  }

  /** Whether any construct was refused. */
  public boolean hasRefusals() {
    return count(Classification.REFUSE) > 0;
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  @Override public String toString() {
    return "ClassificationResult{clean=" + count(Classification.CLEAN)
        + ", degrade=" + count(Classification.DEGRADE)
        + ", refuse=" + count(Classification.REFUSE) + "}";
  }

  /** Builder for {@link ClassificationResult}. Mutable while building;
   * {@link #build()} produces an immutable result. */
  public static final class Builder {
    private final ImmutableList.Builder<CoverageRecord> records =
        ImmutableList.builder();

    private Builder() {}

    /** Adds a record. */
    public Builder add(CoverageRecord record) {
      records.add(requireNonNull(record, "record"));
      return this;
    }

    /** Adds many records. */
    public Builder addAll(Iterable<CoverageRecord> records) {
      this.records.addAll(requireNonNull(records, "records"));
      return this;
    }

    /** Builds the immutable result. */
    public ClassificationResult build() {
      return new ClassificationResult(records.build());
    }
  }
}

// End ClassificationResult.java
