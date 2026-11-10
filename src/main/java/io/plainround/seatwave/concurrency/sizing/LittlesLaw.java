package io.plainround.seatwave.concurrency.sizing;

import java.time.Duration;
import java.util.Objects;

/**
 * Little's Law and the sizing rules built on it. Stateless and thread-safe.
 *
 * <p>Little's Law: the average number of requests in flight equals the arrival rate times the
 * average time each one spends inside. It holds for any queue in a steady state, whatever the order
 * of service or the shape of the arrivals.
 */
public final class LittlesLaw {

  private static final double NANOS_PER_SECOND = 1e9;

  private LittlesLaw() {}

  /**
   * Computes the requests in flight: concurrency = throughput x latency.
   *
   * @param ratePerSecond the arrival rate, above 0
   * @param timeInside the average time each request spends inside, not negative
   * @return the average requests in flight
   */
  public static double inFlight(double ratePerSecond, Duration timeInside) {
    Objects.requireNonNull(timeInside, "timeInside");
    if (!(ratePerSecond > 0) || timeInside.isNegative()) {
      throw new IllegalArgumentException("the rate must be positive and the time not negative");
    }
    return ratePerSecond * timeInside.toNanos() / NANOS_PER_SECOND;
  }

  /**
   * Sizes a pool so its average use stays at or under a ceiling: the in-flight count divided by the
   * ceiling, rounded up.
   *
   * @param ratePerSecond the arrival rate, above 0
   * @param timeHeld how long each request holds one member of the pool
   * @param utilizationCeiling the highest average share of the pool in use, above 0 and at most 1
   * @return the pool size, at least 1
   */
  public static int poolSize(double ratePerSecond, Duration timeHeld, double utilizationCeiling) {
    if (!(utilizationCeiling > 0 && utilizationCeiling <= 1)) {
      throw new IllegalArgumentException(
          "the ceiling is above 0 and at most 1: " + utilizationCeiling);
    }
    return Math.max(1, (int) Math.ceil(inFlight(ratePerSecond, timeHeld) / utilizationCeiling));
  }

  /**
   * Computes the most a pool can serve: its size divided by the time each request holds a member.
   *
   * @param poolSize the members of the pool, at least 1
   * @param timeHeld how long each request holds one member, above 0
   * @return the capacity in requests per second
   */
  public static double capacityPerSecond(int poolSize, Duration timeHeld) {
    Objects.requireNonNull(timeHeld, "timeHeld");
    if (poolSize < 1 || timeHeld.isNegative() || timeHeld.isZero()) {
      throw new IllegalArgumentException("the pool needs 1 or more members and a positive time");
    }
    return poolSize * NANOS_PER_SECOND / timeHeld.toNanos();
  }

  /**
   * Computes the queueing rule of thumb from Module 1: waiting grows like u / (1 - u).
   *
   * @param utilization the share of capacity in use, from 0 up to but not including 1
   * @return the waiting factor: the average wait as a multiple of the service time, for one server
   */
  public static double waitingFactor(double utilization) {
    if (!(utilization >= 0 && utilization < 1)) {
      throw new IllegalArgumentException("utilization is from 0 to below 1: " + utilization);
    }
    return utilization / (1 - utilization);
  }
}
