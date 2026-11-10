package io.plainround.seatwave.concurrency.bench;

import java.util.Arrays;
import java.util.Objects;

/**
 * The p50 and p99 of a set of durations, read by the nearest-rank method.
 *
 * @param count how many durations were summarized; 0 means none, and both percentiles are then 0
 * @param p50Millis the duration half of them did not exceed
 * @param p99Millis the duration 99 in 100 of them did not exceed
 */
public record LatencySummary(int count, double p50Millis, double p99Millis) {

  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final int P50 = 50;
  private static final int P99 = 99;
  private static final int HUNDRED = 100;

  /**
   * Creates the summary.
   *
   * @throws IllegalArgumentException if a value is negative or p99 is below p50
   */
  public LatencySummary {
    if (count < 0 || p50Millis < 0 || p99Millis < p50Millis) {
      throw new IllegalArgumentException("impossible summary: " + count + ", " + p50Millis);
    }
  }

  /**
   * Summarizes durations.
   *
   * @param durationsNanos the durations in nanoseconds, in any order; not changed
   * @return the summary
   */
  public static LatencySummary of(long[] durationsNanos) {
    Objects.requireNonNull(durationsNanos, "durationsNanos");
    if (durationsNanos.length == 0) {
      return new LatencySummary(0, 0, 0);
    }
    long[] sorted = durationsNanos.clone();
    Arrays.sort(sorted);
    return new LatencySummary(
        sorted.length,
        nearestRank(sorted, P50) / NANOS_PER_MILLI,
        nearestRank(sorted, P99) / NANOS_PER_MILLI);
  }

  /**
   * Tells whether any duration was summarized.
   *
   * @return true when the count is above 0
   */
  public boolean hasSamples() {
    return count > 0;
  }

  // Nearest rank: the smallest value that at least percent of the values do not exceed.
  private static long nearestRank(long[] sorted, int percent) {
    int rank = (int) Math.ceil(percent / (double) HUNDRED * sorted.length);
    return sorted[Math.max(rank, 1) - 1];
  }
}
