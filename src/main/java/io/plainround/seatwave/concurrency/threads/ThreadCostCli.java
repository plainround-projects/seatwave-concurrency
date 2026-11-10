package io.plainround.seatwave.concurrency.threads;

import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the thread-cost measurement. Exit status 2 means bad usage or
 * settings; 1 means a run failed.
 */
public final class ThreadCostCli {

  private static final int EXIT_RUN_FAILED = 1;
  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  // macOS on the verification machine allows 8,192 threads per process (kern.num_taskthreads).
  private static final int MAX_PLATFORM_THREADS = 6000;
  private static final int MAX_VIRTUAL_THREADS = 1_000_000;
  private static final int MAX_MILLIS = 120_000;
  private static final double BYTES_PER_KIB = 1024.0;
  private static final double NANOS_PER_MILLI = 1e6;

  private ThreadCostCli() {}

  /**
   * Measures each configured count of each kind and prints one line per measurement.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: threadCost <thread-cost.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      List<Integer> platformCounts =
          reader.intListBetween("platform.thread.counts", 1, MAX_PLATFORM_THREADS);
      List<Integer> virtualCounts =
          reader.intListBetween("virtual.thread.counts", 1, MAX_VIRTUAL_THREADS);
      Duration limit = reader.millisBetween("wait.limit.millis", 1, MAX_MILLIS);
      if (!reader.problems().isEmpty()) {
        reader.problems().forEach(problem -> System.err.println("invalid setting: " + problem));
        System.exit(EXIT_BAD_USAGE);
      }
      System.out.printf(
          Locale.ROOT,
          "Java %s, %d logical processors, heap limit %d MiB%n",
          System.getProperty("java.version"),
          Runtime.getRuntime().availableProcessors(),
          Runtime.getRuntime().maxMemory() >> 20);
      System.out.println(
          "kind      threads  start ms  heap growth KiB  heap per thread bytes  platform threads");
      for (int count : platformCounts) {
        print(ThreadCost.measure(ExecutorMode.PLATFORM, count, limit));
      }
      for (int count : virtualCounts) {
        print(ThreadCost.measure(ExecutorMode.VIRTUAL, count, limit));
      }
    } catch (IOException unreadable) {
      System.err.println("cannot read " + arguments[0] + ": " + unreadable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the measurement was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }

  private static void print(ThreadCost.Result result) {
    System.out.printf(
        Locale.ROOT,
        "%-8s %8d %9.1f %16.0f %22d %17d%n",
        result.kind().name().toLowerCase(Locale.ROOT),
        result.threads(),
        result.startTime().toNanos() / NANOS_PER_MILLI,
        result.heapGrowthBytes() / BYTES_PER_KIB,
        result.heapGrowthBytes() / result.threads(),
        result.platformThreadsInJvm());
  }
}
