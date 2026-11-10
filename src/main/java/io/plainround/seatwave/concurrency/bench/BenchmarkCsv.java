package io.plainround.seatwave.concurrency.bench;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Renders step results as CSV that any spreadsheet program opens and charts. Stateless. */
public final class BenchmarkCsv {

  /** The header line, one column per measured value. */
  public static final String HEADER =
      "mode,offered_rps,achieved_rps,sent,created_201,taken_409,refused_queue_503,refused_pool_503,"
          + "other_503,client_timeouts,error_pct,p50_ms,p99_ms,pool_wait_p99_ms,threads_alive_max,"
          + "platform_threads_max,queue_max,pool_waiters_max,pool_busy_pct,cpu_pct";

  private BenchmarkCsv() {}

  /**
   * Renders the results.
   *
   * @param results the steps, in order
   * @return the header and one line per step, each ending with a newline
   */
  public static String render(List<StepResult> results) {
    Objects.requireNonNull(results, "results");
    StringBuilder csv = new StringBuilder(HEADER).append('\n');
    for (StepResult step : results) {
      Saturation saturation = step.saturation();
      csv.append(
          String.format(
              Locale.ROOT,
              "%s,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%.2f,%.1f,%.1f,%.1f,%d,%d,%d,%d,%.1f,%.1f\n",
              step.mode().name().toLowerCase(Locale.ROOT),
              step.offeredRps(),
              step.achievedRps(),
              step.sent(),
              step.created(),
              step.seatTaken(),
              step.refusedQueue(),
              step.refusedPool(),
              step.otherFailures(),
              step.clientTimeouts(),
              step.errorPercent(),
              step.latency().p50Millis(),
              step.latency().p99Millis(),
              step.poolWait().p99Millis(),
              saturation.maxThreadsAlive(),
              saturation.maxPlatformThreads(),
              saturation.maxQueued(),
              saturation.maxPoolWaiters(),
              saturation.meanPoolBusyPercent(),
              step.cpuPercent()));
    }
    return csv.toString();
  }
}
