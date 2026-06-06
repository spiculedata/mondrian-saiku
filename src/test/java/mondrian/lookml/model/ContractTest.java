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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared CLEAN/DEGRADE/REFUSE contract types.
 */
class ContractTest {

  /** A CoverageRecord builds, derives its classification from the reason code,
   * and pre-fills the related issue from the code's known flip target. */
  @Test void coverageRecordBuilds() {
    // Act
    final CoverageRecord record =
        CoverageRecord.builder(Scope.FIELD, "orders.total_revenue",
                ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE,
                "sum measure fans out across a one_to_many join")
            .sourceRef("orders.view.lkml:42")
            .lostCapability("measure not emitted")
            .build();

    // Assert
    assertEquals(Scope.FIELD, record.scope());
    assertEquals("orders.total_revenue", record.qualifiedName());
    assertEquals(Classification.REFUSE, record.classification());
    assertEquals(ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE,
        record.reasonCode());
    assertEquals("orders.view.lkml:42", record.sourceRef().orElseThrow());
    assertEquals("measure not emitted", record.lostCapability().orElseThrow());
    // related issue auto-filled from the reason code (#103 flips this REFUSE).
    assertEquals("#103", record.relatedIssue().orElseThrow());
  }

  /** The classification on a record always matches its reason code. */
  @Test void classificationDerivesFromReasonCode() {
    assertEquals(Classification.CLEAN, ReasonCode.CLEAN.classification());
    assertEquals(Classification.DEGRADE,
        ReasonCode.DEGRADE_PDT_PERSISTENCE_DROPPED.classification());
    assertEquals(Classification.REFUSE,
        ReasonCode.REFUSE_LIQUID.classification());
  }

  /** A required missing field fails fast (boundary validation). */
  @Test void coverageRecordRejectsNullReason() {
    assertThrows(NullPointerException.class,
        () -> CoverageRecord.builder(Scope.VIEW, "orders",
            ReasonCode.CLEAN, null).build());
  }

  /** ClassificationResult is immutable and counts by classification. */
  @Test void classificationResultIsImmutableAndCounts() {
    // Arrange
    final CoverageRecord clean =
        CoverageRecord.builder(Scope.VIEW, "orders", ReasonCode.CLEAN, "ok")
            .build();
    final CoverageRecord refuse =
        CoverageRecord.builder(Scope.EXPLORE, "bad", ReasonCode.REFUSE_LIQUID,
            "liquid in sql").build();
    final ClassificationResult result = ClassificationResult.builder()
        .add(clean)
        .add(refuse)
        .build();

    // Assert: counts and views.
    assertEquals(1L, result.count(Classification.CLEAN));
    assertEquals(1L, result.count(Classification.REFUSE));
    assertTrue(result.hasRefusals());
    assertEquals(1, result.withClassification(Classification.REFUSE).size());

    // Assert: the records list is immutable.
    assertThrows(UnsupportedOperationException.class,
        () -> result.records().add(clean));
  }

  /** A CLEAN result has no refusals. */
  @Test void cleanResultHasNoRefusals() {
    final ClassificationResult result = ClassificationResult.builder()
        .add(CoverageRecord.builder(Scope.VIEW, "orders", ReasonCode.CLEAN,
            "ok").build())
        .build();
    assertFalse(result.hasRefusals());
  }
}

// End ContractTest.java
