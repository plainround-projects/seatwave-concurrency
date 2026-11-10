package io.plainround.seatwave.concurrency.sizing;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The numbers one node is sized from (Module 4, Section 7). Read from a settings file with no
 * defaults.
 *
 * @param holdsPerSecond the hold rate at the busiest moment
 * @param holdTime how long each hold keeps a thread and a connection
 * @param databaseMaxConnections the connections the database allows this node
 * @param seatMapsPerSecond the seat-map rate at the busiest moment
 * @param seatMapCpu the CPU time one seat map costs
 * @param vcpus the node's vCPUs
 * @param poolCeilingPercent the highest average use of a pool before it is too close to its limit
 * @param cpuTargetPercent the planned share of CPU at peak (Modules 2 and 3)
 */
public record SizingInputs(
    int holdsPerSecond,
    Duration holdTime,
    int databaseMaxConnections,
    int seatMapsPerSecond,
    Duration seatMapCpu,
    int vcpus,
    int poolCeilingPercent,
    int cpuTargetPercent) {

  private static final int MAX_RATE = 1_000_000;
  private static final int MAX_MILLIS = 60_000;
  private static final int MAX_COUNT = 100_000;
  private static final int MAX_PERCENT = 100;

  /**
   * Creates the inputs.
   *
   * @throws IllegalArgumentException if a value is outside its range
   */
  public SizingInputs {
    Objects.requireNonNull(holdTime, "holdTime");
    Objects.requireNonNull(seatMapCpu, "seatMapCpu");
    if (holdsPerSecond < 1 || seatMapsPerSecond < 1 || databaseMaxConnections < 1 || vcpus < 1) {
      throw new IllegalArgumentException("rates and counts must be 1 or more");
    }
    if (holdTime.isZero()
        || holdTime.isNegative()
        || seatMapCpu.isZero()
        || seatMapCpu.isNegative()) {
      throw new IllegalArgumentException("times must be positive");
    }
    if (poolCeilingPercent < 1 || poolCeilingPercent > MAX_PERCENT) {
      throw new IllegalArgumentException("the pool ceiling is 1 to 100 percent");
    }
    if (cpuTargetPercent < 1 || cpuTargetPercent > MAX_PERCENT) {
      throw new IllegalArgumentException("the CPU target is 1 to 100 percent");
    }
  }

  /**
   * Reads the inputs.
   *
   * @param reader the settings reader
   * @return the inputs, or one line per missing or invalid key
   */
  public static Result<SizingInputs, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int holds = reader.intBetween("hold.rate.per.second", 1, MAX_RATE);
    Duration holdTime = reader.millisBetween("hold.time.millis", 1, MAX_MILLIS);
    int connections = reader.intBetween("database.max.connections", 1, MAX_COUNT);
    int seatMaps = reader.intBetween("seatmap.rate.per.second", 1, MAX_RATE);
    Duration seatMapCpu = reader.millisBetween("seatmap.cpu.millis", 1, MAX_MILLIS);
    int vcpus = reader.intBetween("node.vcpus", 1, MAX_COUNT);
    int poolCeiling = reader.intBetween("pool.utilization.ceiling.percent", 1, MAX_PERCENT);
    int cpuTarget = reader.intBetween("cpu.utilization.target.percent", 1, MAX_PERCENT);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new SizingInputs(
            holds, holdTime, connections, seatMaps, seatMapCpu, vcpus, poolCeiling, cpuTarget));
  }
}
