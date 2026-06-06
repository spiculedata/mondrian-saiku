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
import mondrian.lookml.model.Scope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Issue #102 headline deliverable: the per-project coverage ratio.
 *
 * <p>A pure, immutable function over a {@code List<CoverageRecord>} that buckets
 * CLEAN/DEGRADE/REFUSE counts and percentages <em>independently</em> at two
 * granularities:
 *
 * <ul>
 *   <li><b>explore granularity</b> — every {@link Scope#EXPLORE} record (an
 *       explore, or an explore-scoped {@code aggregate_table});</li>
 *   <li><b>field granularity</b> — every {@link Scope#FIELD} record (a
 *       dimension / dimension_group / measure / parameter).</li>
 * </ul>
 *
 * <p>The denominator for each granularity is the number of records at that
 * scope; records of any other scope ({@link Scope#VIEW}, {@link Scope#MODEL},
 * {@link Scope#PROJECT}) count toward neither. Percentages are {@code count /
 * total * 100} rounded to one decimal place (half-up); when the denominator is
 * zero every percentage is {@code 0.0}.
 */
public final class SummaryMetrics {

  /** Decimal places used for every coverage percentage. */
  static final int PERCENT_SCALE = 1;

  private final Bucket explore;
  private final Bucket field;

  private SummaryMetrics(Bucket explore, Bucket field) {
    this.explore = explore;
    this.field = field;
  }

  /** Computes the metrics for the given records. Pure; never mutates input. */
  public static SummaryMetrics from(List<CoverageRecord> records) {
    requireNonNull(records, "records");
    return new SummaryMetrics(
        Bucket.from(records, Scope.EXPLORE),
        Bucket.from(records, Scope.FIELD));
  }

  /** Metrics at explore granularity (EXPLORE-scope records). */
  public Bucket explore() {
    return explore;
  }

  /** Metrics at field granularity (FIELD-scope records). */
  public Bucket field() {
    return field;
  }

  @Override public String toString() {
    return "SummaryMetrics{explore=" + explore + ", field=" + field + "}";
  }

  /**
   * Immutable per-granularity counts and percentages. The percentages are
   * derived from the counts so the two can never disagree.
   */
  public static final class Bucket {
    private final long clean;
    private final long degrade;
    private final long refuse;

    private Bucket(long clean, long degrade, long refuse) {
      this.clean = clean;
      this.degrade = degrade;
      this.refuse = refuse;
    }

    private static Bucket from(List<CoverageRecord> records, Scope scope) {
      long clean = 0;
      long degrade = 0;
      long refuse = 0;
      for (CoverageRecord r : records) {
        if (r.scope() != scope) {
          continue;
        }
        switch (r.classification()) {
        case CLEAN:
          clean++;
          break;
        case DEGRADE:
          degrade++;
          break;
        case REFUSE:
          refuse++;
          break;
        default:
          break;
        }
      }
      return new Bucket(clean, degrade, refuse);
    }

    /** Total records at this granularity (the percentage denominator). */
    public long total() {
      return clean + degrade + refuse;
    }

    /** CLEAN count. */
    public long clean() {
      return clean;
    }

    /** DEGRADE count. */
    public long degrade() {
      return degrade;
    }

    /** REFUSE count. */
    public long refuse() {
      return refuse;
    }

    /** Count for an arbitrary classification. */
    public long count(Classification classification) {
      requireNonNull(classification, "classification");
      switch (classification) {
      case CLEAN:
        return clean;
      case DEGRADE:
        return degrade;
      case REFUSE:
        return refuse;
      default:
        return 0;
      }
    }

    /** CLEAN percentage (1 dp). */
    public double cleanPct() {
      return percent(clean);
    }

    /** DEGRADE percentage (1 dp). */
    public double degradePct() {
      return percent(degrade);
    }

    /** REFUSE percentage (1 dp). */
    public double refusePct() {
      return percent(refuse);
    }

    /** Percentage for an arbitrary classification (1 dp). */
    public double pct(Classification classification) {
      return percent(count(classification));
    }

    private double percent(long count) {
      final long total = total();
      if (total == 0) {
        return 0.0;
      }
      return BigDecimal.valueOf(count)
          .multiply(BigDecimal.valueOf(100))
          .divide(BigDecimal.valueOf(total), PERCENT_SCALE,
              RoundingMode.HALF_UP)
          .doubleValue();
    }

    @Override public String toString() {
      return "clean=" + clean + " (" + cleanPct() + "%), degrade=" + degrade
          + " (" + degradePct() + "%), refuse=" + refuse
          + " (" + refusePct() + "%)";
    }
  }
}

// End SummaryMetrics.java
