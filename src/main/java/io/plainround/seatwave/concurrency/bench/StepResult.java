package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.util.List;
import java.util.Objects;

/**
 * What one load step measured.
 *
 * @param mode the thread model
 * @param offeredRps the holds per second the load generator aimed to send
 * @param sent holds actually sent
 * @param created 201 replies
 * @param seatTaken 409 replies
 * @param refusedQueue 503 replies from the executor bound or the queue wait limit
 * @param refusedPool 503 replies from the pool's borrow limit
 * @param otherFailures 503 replies from the database or an interrupt, and failed futures
 * @param clientTimeouts replies that came later than the client timeout, or never
 * @param achievedRps good replies (201 and 409) per second of the step
 * @param latency p50 and p99 of the good replies, from the planned send time
 * @param poolWait p50 and p99 of the wait for a connection, over holds that waited for one
 * @param saturation the sampler's readings
 * @param cpuPercent the process's CPU time over the step, as a share of every core
 * @param census the thread census, if this was the census step; otherwise empty
 */
public record StepResult(
    ExecutorMode mode,
    int offeredRps,
    long sent,
    long created,
    long seatTaken,
    long refusedQueue,
    long refusedPool,
    long otherFailures,
    long clientTimeouts,
    double achievedRps,
    LatencySummary latency,
    LatencySummary poolWait,
    Saturation saturation,
    double cpuPercent,
    List<CensusLine> census) {

  private static final double HUNDRED_PERCENT = 100.0;

  /**
   * Creates the result.
   *
   * @throws IllegalArgumentException if the outcome counts do not add up to the holds sent
   */
  public StepResult {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(latency, "latency");
    Objects.requireNonNull(poolWait, "poolWait");
    Objects.requireNonNull(saturation, "saturation");
    census = List.copyOf(census);
    long accounted =
        created + seatTaken + refusedQueue + refusedPool + otherFailures + clientTimeouts;
    // Every hold sent must end in exactly one bucket: nothing is lost, nothing counted twice.
    if (accounted != sent) {
      throw new IllegalArgumentException("outcomes add up to " + accounted + ", not " + sent);
    }
  }

  /**
   * Returns the share of holds that did not get a good reply in time.
   *
   * @return a percentage from 0 to 100; 0 when nothing was sent
   */
  public double errorPercent() {
    if (sent == 0) {
      return 0;
    }
    return (sent - created - seatTaken) * HUNDRED_PERCENT / sent;
  }
}
