package io.plainround.seatwave.concurrency.node;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Every limit of one SeatWave node. The values come from a settings file; there are no defaults in
 * code, so a missing key stops the program before it serves anything.
 *
 * @param mode the thread model
 * @param platformWorkers threads in the fixed pool (platform mode)
 * @param executorQueueBound holds that may wait for a free platform thread
 * @param virtualMaxInFlight holds that may be in flight at once (virtual mode)
 * @param queueWaitLimit the longest a hold may wait between arrival and the start of its work
 * @param poolConnections database connections in the pool
 * @param poolBorrowLimit the longest a hold may wait for a connection
 * @param databaseWait the time each hold keeps its connection, standing for a remote database
 * @param statementTimeoutSeconds the JDBC query timeout of the hold statement
 * @param databaseUrl the JDBC URL of the embedded database
 */
public record NodeSettings(
    ExecutorMode mode,
    int platformWorkers,
    int executorQueueBound,
    int virtualMaxInFlight,
    Duration queueWaitLimit,
    int poolConnections,
    Duration poolBorrowLimit,
    Duration databaseWait,
    int statementTimeoutSeconds,
    String databaseUrl) {

  /** The largest thread or connection count a settings file may ask for. */
  public static final int MAX_COUNT = 100_000;

  // A minute is far above any budget in this module; a larger wait is a typo, not a design.
  private static final int MAX_WAIT_MILLIS = 60_000;
  private static final int MAX_STATEMENT_TIMEOUT_SECONDS = 60;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a count is below 1, a wait is negative, or the URL is not
   *     an H2 in-memory URL
   */
  public NodeSettings {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(queueWaitLimit, "queueWaitLimit");
    Objects.requireNonNull(poolBorrowLimit, "poolBorrowLimit");
    Objects.requireNonNull(databaseWait, "databaseWait");
    Objects.requireNonNull(databaseUrl, "databaseUrl");
    for (int count :
        new int[] {
          platformWorkers,
          executorQueueBound,
          virtualMaxInFlight,
          poolConnections,
          statementTimeoutSeconds
        }) {
      if (count < 1) {
        throw new IllegalArgumentException("every count and timeout must be 1 or more: " + count);
      }
    }
    for (Duration wait : new Duration[] {queueWaitLimit, poolBorrowLimit, databaseWait}) {
      if (wait.isNegative()) {
        throw new IllegalArgumentException("a wait is never negative: " + wait);
      }
    }
    // Only an in-memory database: the benchmark must never write files or reach another host.
    if (!databaseUrl.startsWith("jdbc:h2:mem:")) {
      throw new IllegalArgumentException("only jdbc:h2:mem: URLs are allowed: " + databaseUrl);
    }
  }

  /**
   * Reads the node keys from a settings file. The mode is not read here: the benchmark runs the
   * same settings once per mode in {@code executor.modes}.
   *
   * @param reader the settings reader, which collects every problem
   * @param mode the thread model to use
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<NodeSettings, List<String>> from(SettingsReader reader, ExecutorMode mode) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(mode, "mode");
    int workers = reader.intBetween("platform.workers", 1, MAX_COUNT);
    int queueBound = reader.intBetween("platform.queue.bound", 1, MAX_COUNT);
    int maxInFlight = reader.intBetween("virtual.max.in.flight", 1, MAX_COUNT);
    Duration queueWait = reader.millisBetween("queue.wait.limit.millis", 0, MAX_WAIT_MILLIS);
    int connections = reader.intBetween("pool.connections", 1, MAX_COUNT);
    Duration borrowLimit = reader.millisBetween("pool.borrow.limit.millis", 0, MAX_WAIT_MILLIS);
    Duration databaseWait = reader.millisBetween("database.wait.millis", 0, MAX_WAIT_MILLIS);
    int statementTimeout =
        reader.intBetween("database.statement.timeout.seconds", 1, MAX_STATEMENT_TIMEOUT_SECONDS);
    String url = reader.text("database.url");
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    try {
      return Result.success(
          new NodeSettings(
              mode,
              workers,
              queueBound,
              maxInFlight,
              queueWait,
              connections,
              borrowLimit,
              databaseWait,
              statementTimeout,
              url));
    } catch (IllegalArgumentException inconsistent) {
      // Each key was valid alone, but the URL breaks a rule the record enforces.
      return Result.error(List.of(inconsistent.getMessage()));
    }
  }

  /**
   * Returns the same settings with another thread model.
   *
   * @param otherMode the thread model
   * @return a copy with the mode replaced
   */
  public NodeSettings withMode(ExecutorMode otherMode) {
    return new NodeSettings(
        otherMode,
        platformWorkers,
        executorQueueBound,
        virtualMaxInFlight,
        queueWaitLimit,
        poolConnections,
        poolBorrowLimit,
        databaseWait,
        statementTimeoutSeconds,
        databaseUrl);
  }

  /**
   * Returns the same settings with another database URL, so two nodes in one JVM never share a
   * database.
   *
   * @param otherUrl the JDBC URL
   * @return a copy with the URL replaced
   */
  public NodeSettings withDatabaseUrl(String otherUrl) {
    return new NodeSettings(
        mode,
        platformWorkers,
        executorQueueBound,
        virtualMaxInFlight,
        queueWaitLimit,
        poolConnections,
        poolBorrowLimit,
        databaseWait,
        statementTimeoutSeconds,
        otherUrl);
  }
}
