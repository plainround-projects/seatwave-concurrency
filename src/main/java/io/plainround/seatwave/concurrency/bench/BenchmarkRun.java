package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.NodeSettings;
import io.plainround.seatwave.concurrency.node.SeatWaveNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One benchmark run: the same load against a fresh node per thread model, and everything the run
 * prints and writes. Stateless; the command-line class only prints what this returns.
 */
public final class BenchmarkRun {

  private BenchmarkRun() {}

  /**
   * Runs every mode in order, each on a fresh node with its own in-memory database.
   *
   * @param settings the run's settings
   * @return every step of every mode, in order
   * @throws SQLException if a node cannot start
   * @throws InterruptedException if interrupted
   */
  public static List<StepResult> runEveryMode(BenchmarkSettings settings)
      throws SQLException, InterruptedException {
    Objects.requireNonNull(settings, "settings");
    List<StepResult> all = new ArrayList<>();
    OpenModelLoad load = new OpenModelLoad(settings);
    for (ExecutorMode mode : settings.modes()) {
      String modeName = mode.name().toLowerCase(Locale.ROOT);
      // Each mode gets a fresh database, so the second run does not find the first run's holds.
      NodeSettings node =
          settings.node().withMode(mode).withDatabaseUrl(settings.node().databaseUrl() + modeName);
      try (SeatWaveNode running = SeatWaveNode.start(node, Clock.systemUTC())) {
        all.addAll(load.runAll(running));
      }
    }
    return List.copyOf(all);
  }

  /**
   * Describes the machine, the node and the load, so every printed number carries its setting.
   *
   * @param settings the run's settings
   * @return three lines
   */
  public static String header(BenchmarkSettings settings) {
    Objects.requireNonNull(settings, "settings");
    NodeSettings node = settings.node();
    return String.format(
            Locale.ROOT,
            "Machine: %s %s on %s, %d logical processors, Java %s%n",
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"))
        + String.format(
            Locale.ROOT,
            "Node: %d platform workers, queue bound %d, virtual in-flight bound %d, %d connections,"
                + " borrow limit %d ms, queue wait limit %d ms, database wait %d ms%n",
            node.platformWorkers(),
            node.executorQueueBound(),
            node.virtualMaxInFlight(),
            node.poolConnections(),
            node.poolBorrowLimit().toMillis(),
            node.queueWaitLimit().toMillis(),
            node.databaseWait().toMillis())
        + String.format(
            Locale.ROOT,
            "Load: open model, Poisson arrivals, seed %d, warm-up %d ms, steps of %d ms at %s"
                + " holds/s, client timeout %d ms%n",
            settings.randomSeed(),
            settings.warmup().toMillis(),
            settings.stepDuration().toMillis(),
            settings.stepRates(),
            settings.clientTimeout().toMillis());
  }

  /**
   * Renders the table, each thread census, the text chart and the last healthy step of each mode.
   *
   * @param settings the run's settings
   * @param all every step of every mode
   * @return the summary
   */
  public static String summary(BenchmarkSettings settings, List<StepResult> all) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(all, "all");
    StringBuilder summary = new StringBuilder(BenchmarkReport.table(all));
    for (StepResult step : all) {
      if (!step.census().isEmpty()) {
        summary.append(
            String.format(
                Locale.ROOT,
                "%nThread census at %d holds/s, %s mode (platform threads only):%n",
                step.offeredRps(),
                step.mode().name().toLowerCase(Locale.ROOT)));
        summary.append(BenchmarkReport.census(step.census()));
      }
    }
    summary.append(System.lineSeparator()).append(BenchmarkReport.textChart(all));
    summary.append(System.lineSeparator());
    for (ExecutorMode mode : settings.modes()) {
      List<StepResult> ofMode = all.stream().filter(step -> step.mode() == mode).toList();
      Optional<Integer> healthy =
          BenchmarkReport.lastHealthyRate(ofMode, settings.targetP99(), settings.maxErrorPercent());
      summary.append(
          String.format(
              Locale.ROOT,
              "%s: last step inside the target (p99 at most %d ms, errors at most %.2f%%): %s%n",
              mode.name().toLowerCase(Locale.ROOT),
              settings.targetP99().toMillis(),
              settings.maxErrorPercent(),
              healthy.map(rate -> rate + " holds/s").orElse("none")));
    }
    return summary.toString();
  }

  /**
   * Writes the CSV and the two SVG charts.
   *
   * @param folder the folder; created if missing
   * @param name the run's name, used in every file name
   * @param all every step of every mode
   * @param settings the run's settings, for the target line
   * @return the paths written, in order: CSV, p99 chart, error chart
   * @throws IOException if a file cannot be written
   */
  public static List<Path> writeFiles(
      Path folder, String name, List<StepResult> all, BenchmarkSettings settings)
      throws IOException {
    Objects.requireNonNull(folder, "folder");
    Objects.requireNonNull(name, "name");
    Files.createDirectories(folder);
    Path csv = folder.resolve(name + ".csv");
    Path p99Chart = folder.resolve(name + "-p99.svg");
    Path errorChart = folder.resolve(name + "-errors.svg");
    Files.writeString(csv, BenchmarkCsv.render(all), StandardCharsets.UTF_8);
    Files.writeString(
        p99Chart,
        LoadChartSvg.render(
            name + ": hold p99 against load",
            all,
            ChartMetric.P99_MILLIS,
            settings.targetP99().toMillis()),
        StandardCharsets.UTF_8);
    Files.writeString(
        errorChart,
        LoadChartSvg.render(
            name + ": holds without a good reply", all, ChartMetric.ERROR_PERCENT, 0),
        StandardCharsets.UTF_8);
    return List.of(csv, p99Chart, errorChart);
  }
}
