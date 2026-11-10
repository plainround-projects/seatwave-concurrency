package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.util.List;

/** Hand-made step results for the report tests. */
final class StepResults {

  private static final long SENT = 1000;

  private StepResults() {}

  /**
   * Returns a step where every hold got a 201, with the given latency.
   *
   * @param mode the thread model
   * @param offeredRps the offered rate
   * @param p99Millis the p99
   * @param refusedQueue how many of the 1,000 holds were refused at the door instead
   * @return the step
   */
  static StepResult step(ExecutorMode mode, int offeredRps, double p99Millis, long refusedQueue) {
    return new StepResult(
        mode,
        offeredRps,
        SENT,
        SENT - refusedQueue,
        0,
        refusedQueue,
        0,
        0,
        0,
        offeredRps,
        new LatencySummary((int) (SENT - refusedQueue), p99Millis / 2, p99Millis),
        new LatencySummary(0, 0, 0),
        new Saturation(1, 0, 0, 1, 0),
        1,
        List.of());
  }
}
