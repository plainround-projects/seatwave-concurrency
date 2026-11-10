package io.plainround.seatwave.concurrency.sizing;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Prints every step of sizing one node, with the arithmetic visible. Stateless. */
public final class SizingReport {

  private static final double HUNDRED_PERCENT = 100.0;
  private static final double MILLIS_PER_SECOND = 1000.0;
  // The utilizations of Figure 2 in the module.
  private static final List<Integer> CLIFF_POINTS_PERCENT = List.of(50, 80, 90, 95, 99);

  private SizingReport() {}

  /**
   * Renders the sizing.
   *
   * @param inputs the node's numbers
   * @return the report, one step per line
   */
  public static String render(SizingInputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    StringBuilder report = new StringBuilder();
    holds(report, inputs);
    seatMaps(report, inputs);
    report.append("WAITING FACTOR u / (1 - u), one server\n ");
    for (int percent : CLIFF_POINTS_PERCENT) {
      report.append(
          String.format(
              Locale.ROOT,
              "  %d %%: %.1f",
              percent,
              LittlesLaw.waitingFactor(percent / HUNDRED_PERCENT)));
    }
    return report.append('\n').toString();
  }

  private static void holds(StringBuilder report, SizingInputs inputs) {
    double ceiling = inputs.poolCeilingPercent() / HUNDRED_PERCENT;
    double inFlight = LittlesLaw.inFlight(inputs.holdsPerSecond(), inputs.holdTime());
    int pool = LittlesLaw.poolSize(inputs.holdsPerSecond(), inputs.holdTime(), ceiling);
    double capacity = LittlesLaw.capacityPerSecond(pool, inputs.holdTime());
    report.append("HOLDS\n");
    line(
        report,
        "In flight (Little's Law): %,d holds/s x %.3f s = %.1f holds",
        inputs.holdsPerSecond(),
        seconds(inputs.holdTime()),
        inFlight);
    line(
        report,
        "Connection pool, ceiling %d %%: ceiling of %.1f / %.2f = %d connections;"
            + " the database allows %d: %s",
        inputs.poolCeilingPercent(),
        inFlight,
        ceiling,
        pool,
        inputs.databaseMaxConnections(),
        pool <= inputs.databaseMaxConnections() ? "fits" : "DOES NOT FIT");
    line(
        report,
        "Capacity of %d connections: %d / %.3f s = %,.0f holds/s; at %,d holds/s the pool is %.0f %% busy",
        pool,
        pool,
        seconds(inputs.holdTime()),
        capacity,
        inputs.holdsPerSecond(),
        inputs.holdsPerSecond() * HUNDRED_PERCENT / capacity);
    line(
        report,
        "Platform worker pool, same rule: %d threads, each blocked for the %.0f ms of every hold",
        pool,
        inputs.holdTime().toNanos() / (double) Duration.ofMillis(1).toNanos());
    line(
        report,
        "Virtual threads: one per hold, %.1f alive on average; the pool, not the threads, sets the"
            + " limit",
        inFlight);
  }

  private static void seatMaps(StringBuilder report, SizingInputs inputs) {
    double busyCores = LittlesLaw.inFlight(inputs.seatMapsPerSecond(), inputs.seatMapCpu());
    double knee = LittlesLaw.capacityPerSecond(inputs.vcpus(), inputs.seatMapCpu());
    double safe = knee * inputs.cpuTargetPercent() / HUNDRED_PERCENT;
    report.append("SEAT MAPS\n");
    line(
        report,
        "CPU busy (Little's Law on cores): %,d seat maps/s x %.3f s = %.1f vCPUs busy; the node has %d",
        inputs.seatMapsPerSecond(),
        seconds(inputs.seatMapCpu()),
        busyCores,
        inputs.vcpus());
    line(
        report,
        "Utilization: %.1f / %d = %.0f %%: %s",
        busyCores,
        inputs.vcpus(),
        busyCores * HUNDRED_PERCENT / inputs.vcpus(),
        busyCores <= inputs.vcpus()
            ? "fits on one node"
            : "one node cannot serve this; more threads add no CPU");
    line(
        report,
        "One node's CPU knee: %d / %.3f s = %,.0f seat maps/s; safe at %d %%: %,.0f seat maps/s",
        inputs.vcpus(),
        seconds(inputs.seatMapCpu()),
        knee,
        inputs.cpuTargetPercent(),
        safe);
    line(
        report,
        "Nodes for seat maps: ceiling of %,d / %,.0f = %d nodes",
        inputs.seatMapsPerSecond(),
        safe,
        (int) Math.ceil(inputs.seatMapsPerSecond() / safe));
  }

  private static double seconds(Duration duration) {
    return duration.toNanos() / (MILLIS_PER_SECOND * Duration.ofMillis(1).toNanos());
  }

  private static void line(StringBuilder report, String format, Object... values) {
    report.append("  ").append(String.format(Locale.ROOT, format, values)).append('\n');
  }
}
