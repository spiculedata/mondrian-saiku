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

import mondrian.lookml.equivalence.LookerQueryToMdx.Plan;
import mondrian.lookml.transpile.ProvenanceMap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #128: unit tests for {@link LookerQueryToMdx} — provenance-driven field
 * resolution, the MDX shape (measures on COLUMNS, dimension levels NON EMPTY on
 * ROWS), and the no-fabrication rule (a field with no CLEAN provenance is
 * skipped, never invented). Also covers {@link LookerQueryResult#fromJson}.
 */
public class LookerQueryToMdxTest {

  private static ProvenanceMap provenance() {
    return ProvenanceMap.builder()
        .put("explore:orders", "cube:orders")
        .put("users.country",
            "cube:orders/dimension:users/attribute:country")
        .put("orders.total_amount",
            "cube:orders/measureGroup:orders/measure:total_amount")
        .put("orders.filtered_amount",
            "cube:orders/calculatedMember:filtered_amount")
        .build();
  }

  @Test
  public void buildsMeasureOnColumnsDimensionOnRows() {
    final LookerQuerySpec spec = LookerQuerySpec.builder()
        .explore("orders")
        .dimension("users.country")
        .measure("orders.total_amount")
        .build();
    final Plan plan = new LookerQueryToMdx(provenance()).toMdx(spec);

    assertEquals("orders", plan.cube());
    final String mdx = plan.mdx();
    assertTrue(mdx.contains("{[Measures].[total_amount]} ON COLUMNS"), mdx);
    assertTrue(mdx.contains("NON EMPTY [users].[country].[country].Members ON ROWS"),
        mdx);
    assertTrue(mdx.contains("FROM [orders]"), mdx);
    assertTrue(plan.skippedFields().isEmpty(), "all fields CLEAN");
  }

  @Test
  public void calculatedMemberMeasureResolves() {
    final LookerQuerySpec spec = LookerQuerySpec.builder()
        .explore("orders")
        .measure("orders.filtered_amount")
        .build();
    final Plan plan = new LookerQueryToMdx(provenance()).toMdx(spec);
    assertTrue(plan.mdx().contains("[Measures].[filtered_amount]"),
        plan.mdx());
  }

  @Test
  public void fieldWithoutCleanProvenanceIsSkippedNotFabricated() {
    final LookerQuerySpec spec = LookerQuerySpec.builder()
        .explore("orders")
        .dimension("users.country")
        .measure("orders.total_amount")
        .measure("orders.refused_measure")   // no provenance entry → REFUSE
        .build();
    final Plan plan = new LookerQueryToMdx(provenance()).toMdx(spec);

    assertEquals(1, plan.measureFields().size(),
        "only the CLEAN measure is kept");
    assertTrue(plan.skippedFields().contains("orders.refused_measure"),
        "the refused field is recorded, not invented");
    assertTrue(!plan.mdx().contains("refused_measure"), plan.mdx());
  }

  @Test
  public void multipleDimensionsCrossjoin() {
    final ProvenanceMap prov = ProvenanceMap.builder()
        .put("explore:orders", "cube:orders")
        .put("users.country",
            "cube:orders/dimension:users/attribute:country")
        .put("orders.status",
            "cube:orders/dimension:status/attribute:status")
        .put("orders.total_amount",
            "cube:orders/measureGroup:orders/measure:total_amount")
        .build();
    final LookerQuerySpec spec = LookerQuerySpec.builder()
        .explore("orders")
        .dimension("users.country")
        .dimension("orders.status")
        .measure("orders.total_amount")
        .build();
    final Plan plan = new LookerQueryToMdx(prov).toMdx(spec);
    assertTrue(plan.mdx().contains(
        "CrossJoin([users].[country].[country].Members, [status].[status].[status].Members)"),
        plan.mdx());
  }

  @Test
  public void fromJsonParsesLookerRunQueryShape() {
    // The shape Looker /api/4.0/queries/run/json returns: array of row objects.
    final String json =
        "[{\"users.country\":\"USA\",\"orders.total_amount\":1234.5},"
        + "{\"users.country\":\"UK\",\"orders.total_amount\":67.0}]";
    final LookerQueryResult result = LookerQueryResult.fromJson(json);
    assertEquals(2, result.rowCount());
    final Map<String, Object> first = result.rows().get(0);
    assertEquals("USA", first.get("users.country"));
    assertEquals(1234.5,
        ((Number) first.get("orders.total_amount")).doubleValue(), 1e-9);
  }
}

// End LookerQueryToMdxTest.java
