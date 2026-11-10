package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small, fast node settings and databases for tests; every database has its own name. */
public final class TestNodes {

  /**
   * A wait long enough never to expire in a passing test, and short enough to end a failing one.
   */
  public static final Duration LONG_WAIT = Duration.ofSeconds(5);

  private static final int STATEMENT_TIMEOUT_SECONDS = 2;
  private static final int TWO_IN_FLIGHT = 2;
  private static final int ROOMY_WORKERS = 16;
  private static final int ROOMY_QUEUE = 1000;
  private static final int ROOMY_CONNECTIONS = 8;

  private TestNodes() {}

  /**
   * Returns settings with one worker, one queued hold, one connection and no database wait.
   *
   * @param mode the thread model
   * @return the settings
   */
  public static NodeSettings tiny(ExecutorMode mode) {
    return new NodeSettings(
        mode,
        1,
        1,
        TWO_IN_FLIGHT,
        LONG_WAIT,
        1,
        LONG_WAIT,
        Duration.ZERO,
        STATEMENT_TIMEOUT_SECONDS,
        uniqueUrl());
  }

  /**
   * Returns settings with room for many holds at once and no database wait.
   *
   * @param mode the thread model
   * @return the settings
   */
  public static NodeSettings roomy(ExecutorMode mode) {
    return new NodeSettings(
        mode,
        ROOMY_WORKERS,
        ROOMY_QUEUE,
        ROOMY_QUEUE,
        LONG_WAIT,
        ROOMY_CONNECTIONS,
        LONG_WAIT,
        Duration.ZERO,
        STATEMENT_TIMEOUT_SECONDS,
        uniqueUrl());
  }

  /**
   * Returns a JDBC URL no other test uses.
   *
   * @return the URL of a fresh in-memory database
   */
  public static String uniqueUrl() {
    return "jdbc:h2:mem:test-" + UUID.randomUUID();
  }

  /**
   * Opens connections to one fresh in-memory database.
   *
   * @param count how many
   * @return the open connections
   * @throws SQLException if H2 cannot open them
   */
  public static List<Connection> connections(int count) throws SQLException {
    String url = uniqueUrl();
    List<Connection> connections = new ArrayList<>();
    for (int connectionIndex = 0; connectionIndex < count; connectionIndex++) {
      connections.add(DriverManager.getConnection(url));
    }
    return connections;
  }
}
