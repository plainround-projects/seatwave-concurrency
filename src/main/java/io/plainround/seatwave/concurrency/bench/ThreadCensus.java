package io.plainround.seatwave.concurrency.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Counts platform threads by name group, state and the place they wait: the reading of a thread
 * dump, done in code.
 *
 * <p>It sees platform threads only, because {@link Thread#getAllStackTraces()} does not list
 * virtual threads; for those, use {@code jcmd <pid> Thread.dump_to_file -format=json <file>}.
 * Stateless.
 */
public final class ThreadCensus {

  private static final String SEATWAVE_PACKAGE = "io.plainround.seatwave.";
  private static final String NO_FRAME = "(no stack)";

  private ThreadCensus() {}

  /**
   * Takes a census now.
   *
   * @param groups the thread-name prefixes to count; other threads are ignored
   * @return one line per group, state and place, largest first
   */
  public static List<CensusLine> take(List<String> groups) {
    Objects.requireNonNull(groups, "groups");
    Map<String, Integer> counts = new TreeMap<>();
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      String name = entry.getKey().getName();
      for (String group : groups) {
        if (name.startsWith(group)) {
          String key =
              String.join(
                  "\t", group, entry.getKey().getState().name(), waitingIn(entry.getValue()));
          counts.merge(key, 1, Integer::sum);
        }
      }
    }
    List<CensusLine> lines = new ArrayList<>();
    counts.forEach((key, threads) -> {
      String[] parts = key.split("\t", -1);
      lines.add(new CensusLine(parts[0], parts[1], parts[2], threads));
    });
    lines.sort(Comparator.comparingInt(CensusLine::threads).reversed());
    return List.copyOf(lines);
  }

  // The first SeatWave frame says why the thread waits; the JDK frames above it only say how.
  private static String waitingIn(StackTraceElement[] frames) {
    for (StackTraceElement frame : frames) {
      if (frame.getClassName().startsWith(SEATWAVE_PACKAGE)) {
        return shortName(frame);
      }
    }
    return frames.length == 0 ? NO_FRAME : shortName(frames[0]);
  }

  private static String shortName(StackTraceElement frame) {
    String className = frame.getClassName();
    return className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
  }
}
