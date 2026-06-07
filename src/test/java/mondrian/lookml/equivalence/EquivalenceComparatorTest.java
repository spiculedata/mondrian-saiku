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
package mondrian.lookml.equivalence;

import mondrian.lookml.equivalence.EquivalenceComparator.ComparisonResult;
import mondrian.lookml.equivalence.EquivalenceComparator.DivergenceCategory;
import mondrian.lookml.equivalence.LookerQueryToMdx.Plan;
import mondrian.lookml.transpile.ProvenanceMap;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #128: unit tests for {@link EquivalenceComparator} using a stub
 * Mondrian {@link mondrian.olap.Result} (no engine), exercising every
 * divergence category and the within-tolerance match. The Saiku side is built
 * with {@link StubResult} so the comparison logic is tested in isolation.
 */
public class EquivalenceComparatorTest {

  /** A one-dimension, one-measure plan over the shared cube. */
  private static Plan plan() {
    final ProvenanceMap prov = ProvenanceMap.builder()
        .put("explore:sales", "cube:sales")
        .put("users.country", "cube:sales/dimension:users/attribute:country")
        .put("orders.amount", "cube:sales/measureGroup:sales/measure:amount")
        .build();
    final LookerQuerySpec spec = LookerQuerySpec.builder()
        .explore("sales")
        .dimension("users.country")
        .measure("orders.amount")
        .build();
    return new LookerQueryToMdx(prov).toMdx(spec);
  }

  private static Map<String, Object> row(String country, double amount) {
    final Map<String, Object> r = new LinkedHashMap<>();
    r.put("users.country", country);
    r.put("orders.amount", amount);
    return r;
  }

  @Test
  public void withinToleranceMatches() {
    final Plan plan = plan();
    final LookerQueryResult oracle = LookerQueryResult.builder()
        .row(row("USA", 100.0))
        .row(row("UK", 50.0))
        .build();
    // Saiku returns the same numbers within 1e-7 — inside the 1e-6 tolerance.
    final mondrian.olap.Result saiku = StubResult.builder()
        .row("USA", 100.0000001)
        .row("UK", 50.0)
        .build();

    final ComparisonResult result =
        new EquivalenceComparator().compare(oracle, saiku, plan);
    assertTrue(result.matched(), () -> result.toString());
    assertEquals(0, result.divergences().size());
  }

  @Test
  public void measureValueDivergenceIsReported() {
    final Plan plan = plan();
    final LookerQueryResult oracle = LookerQueryResult.builder()
        .row(row("USA", 100.0))
        .row(row("UK", 50.0))
        .build();
    final mondrian.olap.Result saiku = StubResult.builder()
        .row("USA", 999.0)   // deliberately wrong
        .row("UK", 50.0)
        .build();

    final ComparisonResult result =
        new EquivalenceComparator().compare(oracle, saiku, plan);
    assertFalse(result.matched());
    assertTrue(result.hasCategory(DivergenceCategory.MEASURE_VALUE));
    assertEquals("orders.amount",
        result.divergences().get(0).field(),
        "names the field, never the value");
  }

  @Test
  public void rowCountMismatchIsReported() {
    final Plan plan = plan();
    final LookerQueryResult oracle = LookerQueryResult.builder()
        .row(row("USA", 100.0))
        .row(row("UK", 50.0))
        .build();
    final mondrian.olap.Result saiku = StubResult.builder()
        .row("USA", 100.0)   // only one row
        .build();

    final ComparisonResult result =
        new EquivalenceComparator().compare(oracle, saiku, plan);
    assertFalse(result.matched());
    assertTrue(result.hasCategory(DivergenceCategory.ROW_COUNT));
  }

  @Test
  public void dimensionSetMismatchIsReported() {
    final Plan plan = plan();
    final LookerQueryResult oracle = LookerQueryResult.builder()
        .row(row("USA", 100.0))
        .row(row("UK", 50.0))
        .build();
    // Same count, but a different dimension member (France vs UK).
    final mondrian.olap.Result saiku = StubResult.builder()
        .row("USA", 100.0)
        .row("France", 50.0)
        .build();

    final ComparisonResult result =
        new EquivalenceComparator().compare(oracle, saiku, plan);
    assertFalse(result.matched());
    assertTrue(result.hasCategory(DivergenceCategory.DIMENSION_SET));
    // Crucially NOT a row-count divergence (counts match).
    assertFalse(result.hasCategory(DivergenceCategory.ROW_COUNT));
  }
}

// End EquivalenceComparatorTest.java
