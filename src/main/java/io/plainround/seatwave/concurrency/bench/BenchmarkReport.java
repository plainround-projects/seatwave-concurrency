package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders step results as the plain-text table, chart and summary the benchmark prints. Stateless.
 */
public final class BenchmarkReport {

  private static final int BAR_WIDTH = 50;
  private static final char BAR = '#';

  private BenchmarkReport() {}

  /**
   * Renders the table of every step.
   *
   * @param results the steps of every mode, in order
   * @return the table, one line per step
   */
  public static String table(List<StepResult> results) {
    Objects.requireNonNull(results, "results");
    StringBuilder table =
        new StringBuilder(
            "mode      offered achieved  p50 ms   p99 ms  errors%  503q  503p  late  threads"
                + "  platform  waiters  waitp99  busy%  cpu%\n");
    for (StepResult step : results) {
      Saturation saturation = step.saturation();
      table.append(
          String.format(
              Locale.ROOT,
              "%-8s %8d %8.1f %7.1f %8.1f %8.2f %5d %5d %5d %8d %9d %8d %8.1f %6.1f %5.1f%n",
              step.mode().name().toLowerCase(Locale.ROOT),
              step.offeredRps(),
              step.achievedRps(),
              step.latency().p50Millis(),
              step.latency().p99Millis(),
              step.errorPercent(),
              step.refusedQueue(),
              step.refusedPool(),
              step.clientTimeouts(),
              saturation.maxThreadsAlive(),
              saturation.maxPlatformThreads(),
              saturation.maxPoolWaiters(),
              step.poolWait().p99Millis(),
              saturation.meanPoolBusyPercent(),
              step.cpuPercent()));
    }
    return table.toString();
  }

  /**
   * Draws p99 against the offered rate as rows of bars, one block per mode.
   *
   * @param results the steps of every mode
   * @return the chart
   */
  public static String textChart(List<StepResult> results) {
    Objects.requireNonNull(results, "results");
    double longest =
        results.stream().mapToDouble(step -> step.latency().p99Millis()).max().orElse(1);
    StringBuilder chart = new StringBuilder();
    ExecutorMode current = null;
    for (StepResult step : results) {
      if (step.mode() != current) {
        current = step.mode();
        chart.append(
            String.format(
                Locale.ROOT,
                "p99 against offered load, %s threads%n",
                current.name().toLowerCase(Locale.ROOT)));
      }
      int barLength = (int) Math.round(BAR_WIDTH * step.latency().p99Millis() / longest);
      chart.append(
          String.format(
              Locale.ROOT,
              "%6d holds/s | %-" + BAR_WIDTH + "s | p99 %8.1f ms, errors %6.2f%%%n",
              step.offeredRps(),
              String.valueOf(BAR).repeat(barLength),
              step.latency().p99Millis(),
              step.errorPercent()));
    }
    return chart.toString();
  }

  /**
   * Finds the highest offered rate up to which every step met the target.
   *
   * @param results the steps of one mode, in increasing rate
   * @param targetP99 the p99 a healthy step must stay under
   * @param maxErrorPercent the error share a healthy step may not exceed
   * @return the rate, or empty if even the first step missed the target
   */
  public static Optional<Integer> lastHealthyRate(
      List<StepResult> results, Duration targetP99, double maxErrorPercent) {
    Objects.requireNonNull(results, "results");
    Objects.requireNonNull(targetP99, "targetP99");
    double targetMillis = targetP99.toNanos() / (double) Duration.ofMillis(1).toNanos();
    Optional<Integer> lastHealthy = Optional.empty();
    for (StepResult step : results) {
      boolean isHealthy =
          step.latency().hasSamples()
              && step.latency().p99Millis() <= targetMillis
              && step.errorPercent() <= maxErrorPercent;
      if (!isHealthy) {
        return lastHealthy;
      }
      lastHealthy = Optional.of(step.offeredRps());
    }
    return lastHealthy;
  }

  /**
   * Renders a thread census.
   *
   * @param lines the census lines
   * @return one line per group, state and place
   */
  public static String census(List<CensusLine> lines) {
    Objects.requireNonNull(lines, "lines");
    StringBuilder census = new StringBuilder();
    for (CensusLine line : lines) {
      census.append(
          String.format(
              Locale.ROOT,
              "  %5d  %-18s %-14s in %s%n",
              line.threads(),
              line.group(),
              line.state(),
              line.waitingIn()));
    }
    return census.toString();
  }
}
