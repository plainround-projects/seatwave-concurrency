package io.plainround.seatwave.concurrency.bench;

/**
 * The saturation of each pool during one step, from the sampler's readings.
 *
 * @param maxThreadsAlive the most threads that existed for SeatWave work at one reading
 * @param maxQueued the longest the executor queue was at one reading
 * @param maxPoolWaiters the most threads waiting for a connection at one reading
 * @param maxPlatformThreads the most platform threads in the whole JVM at one reading
 * @param meanPoolBusyPercent the average share of connections lent out: the pool's utilization
 */
public record Saturation(
    int maxThreadsAlive,
    int maxQueued,
    int maxPoolWaiters,
    int maxPlatformThreads,
    double meanPoolBusyPercent) {

  private static final double HUNDRED_PERCENT = 100.0;

  /**
   * Creates the record.
   *
   * @throws IllegalArgumentException if a count is negative or the utilization is outside 0 to 100
   */
  public Saturation {
    if (maxThreadsAlive < 0 || maxQueued < 0 || maxPoolWaiters < 0 || maxPlatformThreads < 0) {
      throw new IllegalArgumentException("a count is never negative");
    }
    if (meanPoolBusyPercent < 0 || meanPoolBusyPercent > HUNDRED_PERCENT) {
      throw new IllegalArgumentException("utilization is 0 to 100 percent: " + meanPoolBusyPercent);
    }
  }
}
