package io.plainround.seatwave.concurrency.eventloop;

import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Command-line entry point of Lab 3: the same burst of holds against the event loop with the
 * blocking call inside it, then with the call moved off it. Exit status 2 means bad usage or
 * settings; 1 means a run failed.
 */
public final class EventLoopCli {

  private static final int EXIT_RUN_FAILED = 1;
  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int MAX_CLIENTS = 1000;
  private static final int MAX_MILLIS = 60_000;
  private static final double NANOS_PER_MILLI = 1e6;

  private EventLoopCli() {}

  /**
   * Runs both modes and prints each client's wait.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: eventLoop <event-loop.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int clients = reader.intBetween("clients", 1, MAX_CLIENTS);
      Duration work = reader.millisBetween("work.millis", 0, MAX_MILLIS);
      Duration timeout = reader.millisBetween("socket.timeout.millis", 1, MAX_MILLIS);
      int bound = reader.intBetween("offload.max.in.flight", 1, MAX_CLIENTS);
      if (!reader.problems().isEmpty()) {
        reader.problems().forEach(problem -> System.err.println("invalid setting: " + problem));
        System.exit(EXIT_BAD_USAGE);
      }
      EventLoopLab.Settings settings = new EventLoopLab.Settings(clients, work, timeout, bound);
      System.out.printf(
          Locale.ROOT,
          "%d clients send one hold each at the same moment; each hold blocks for %d ms.%n",
          clients,
          work.toMillis());
      for (LoopMode mode : LoopMode.values()) {
        List<Duration> waits = EventLoopLab.run(mode, settings);
        System.out.printf(
            Locale.ROOT,
            "%-17s waits in ms, shortest to longest: %s%n",
            mode.name().toLowerCase(Locale.ROOT),
            waits.stream()
                .map(wait -> String.format(Locale.ROOT, "%.0f", wait.toNanos() / NANOS_PER_MILLI))
                .collect(Collectors.joining(", ")));
      }
    } catch (IOException failed) {
      System.err.println("the lab failed: " + failed);
      System.exit(EXIT_RUN_FAILED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the lab was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }
}
