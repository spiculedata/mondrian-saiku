/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.lookml.report;

import mondrian.lookml.model.Classification;
import mondrian.lookml.model.CoverageRecord;
import mondrian.lookml.model.ReasonCode;
import mondrian.lookml.model.Scope;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Issue #102: the headline coverage ratio. {@link SummaryMetrics} is a pure
 * function over a {@code List<CoverageRecord>} that buckets CLEAN/DEGRADE/REFUSE
 * counts + percentages independently at explore granularity (EXPLORE-scope
 * records) and field granularity (FIELD-scope records).
 *
 * <p>Fixture: 3 explores (2 clean, 1 refuse) and 8 fields (5 clean, 2 degrade,
 * 1 refuse). The denominator is the number of records at that granularity;
 * percentages are rounded to one decimal place.
 */
public class SummaryMetricsTest {

  private static CoverageRecord rec(Scope scope, String qn, ReasonCode code) {
    return CoverageRecord.builder(scope, qn, code, "reason for " + qn).build();
  }

  private static List<CoverageRecord> fixture() {
    List<CoverageRecord> out = new ArrayList<>();
    // 3 explores: 2 CLEAN, 1 REFUSE.
    out.add(rec(Scope.EXPLORE, "explore:orders", ReasonCode.CLEAN));
    out.add(rec(Scope.EXPLORE, "explore:users", ReasonCode.CLEAN));
    out.add(rec(Scope.EXPLORE, "explore:events",
        ReasonCode.REFUSE_NON_STAR_TOPOLOGY));
    // 8 fields: 5 CLEAN, 2 DEGRADE, 1 REFUSE.
    out.add(rec(Scope.FIELD, "orders.status", ReasonCode.CLEAN));
    out.add(rec(Scope.FIELD, "orders.amount", ReasonCode.CLEAN));
    out.add(rec(Scope.FIELD, "orders.count", ReasonCode.CLEAN));
    out.add(rec(Scope.FIELD, "users.country", ReasonCode.CLEAN));
    out.add(rec(Scope.FIELD, "users.name", ReasonCode.CLEAN));
    out.add(rec(Scope.FIELD, "orders.f1",
        ReasonCode.DEGRADE_FILTERED_MEASURE_LIQUID));
    out.add(rec(Scope.FIELD, "orders.f2",
        ReasonCode.DEGRADE_FILTERED_MEASURE_LIQUID));
    out.add(rec(Scope.FIELD, "orders.fanned_sum",
        ReasonCode.REFUSE_FANOUT_SYMMETRIC_AGGREGATE));
    return out;
  }

  @Test
  public void exploreGranularityCounts() {
    SummaryMetrics m = SummaryMetrics.from(fixture());
    assertEquals(3, m.explore().total());
    assertEquals(2, m.explore().clean());
    assertEquals(0, m.explore().degrade());
    assertEquals(1, m.explore().refuse());
  }

  @Test
  public void exploreGranularityPercentages() {
    SummaryMetrics m = SummaryMetrics.from(fixture());
    // 2/3 = 66.7, 0/3 = 0.0, 1/3 = 33.3 (1 dp).
    assertEquals(66.7, m.explore().cleanPct(), 0.0001);
    assertEquals(0.0, m.explore().degradePct(), 0.0001);
    assertEquals(33.3, m.explore().refusePct(), 0.0001);
  }

  @Test
  public void fieldGranularityCounts() {
    SummaryMetrics m = SummaryMetrics.from(fixture());
    assertEquals(8, m.field().total());
    assertEquals(5, m.field().clean());
    assertEquals(2, m.field().degrade());
    assertEquals(1, m.field().refuse());
  }

  @Test
  public void fieldGranularityPercentages() {
    SummaryMetrics m = SummaryMetrics.from(fixture());
    // 5/8 = 62.5, 2/8 = 25.0, 1/8 = 12.5.
    assertEquals(62.5, m.field().cleanPct(), 0.0001);
    assertEquals(25.0, m.field().degradePct(), 0.0001);
    assertEquals(12.5, m.field().refusePct(), 0.0001);
  }

  @Test
  public void emptyDenominatorYieldsZeroPercent() {
    SummaryMetrics m = SummaryMetrics.from(new ArrayList<>());
    assertEquals(0, m.explore().total());
    assertEquals(0.0, m.explore().cleanPct(), 0.0001);
    assertEquals(0.0, m.field().refusePct(), 0.0001);
  }

  @Test
  public void ignoresOtherScopesAtBothGranularities() {
    List<CoverageRecord> recs = new ArrayList<>(fixture());
    // A VIEW-scope DEGRADE (derived_table) must not count toward explore or
    // field denominators.
    recs.add(rec(Scope.VIEW, "view:pdt",
        ReasonCode.DEGRADE_PDT_PERSISTENCE_DROPPED));
    SummaryMetrics m = SummaryMetrics.from(recs);
    assertEquals(3, m.explore().total());
    assertEquals(8, m.field().total());
  }

  @Test
  public void classificationFromGroupMatchesAggregate() {
    SummaryMetrics m = SummaryMetrics.from(fixture());
    assertEquals(m.explore().clean(),
        m.explore().count(Classification.CLEAN));
    assertEquals(m.field().refuse(),
        m.field().count(Classification.REFUSE));
  }
}

// End SummaryMetricsTest.java
