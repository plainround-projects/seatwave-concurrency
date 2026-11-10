package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One SeatWave process: the workers, the connection pool and the embedded database, wired from one
 * {@link NodeSettings}. Create one, call {@link #endpoint()}, and close it when done.
 *
 * <p>Thread-safe once constructed: every part is.
 */
public final class SeatWaveNode implements AutoCloseable {

  private final NodeSettings settings;
  private final Workers workers;
  private final ConnectionPool pool;
  private final HoldEndpoint endpoint;

  private SeatWaveNode(NodeSettings settings, Workers workers, ConnectionPool pool, Clock clock) {
    this.settings = settings;
    this.workers = workers;
    this.pool = pool;
    this.endpoint =
        new HoldEndpoint(
            workers,
            pool,
            new SeatStore(clock, settings.statementTimeoutSeconds()),
            HoldEndpoint.Limits.of(settings),
            System::nanoTime);
  }

  /**
   * Opens the database, creates the seats, fills the pool and starts the workers.
   *
   * @param settings every limit of the node
   * @param clock the source of "now" for hold expiry
   * @return the running node
   * @throws SQLException if the database cannot be opened or seeded
   */
  public static SeatWaveNode start(NodeSettings settings, Clock clock) throws SQLException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(clock, "clock");
    List<Connection> connections = new ArrayList<>();
    try {
      for (int connectionIndex = 0;
          connectionIndex < settings.poolConnections();
          connectionIndex++) {
        connections.add(DriverManager.getConnection(settings.databaseUrl()));
      }
      new SeatStore(clock, settings.statementTimeoutSeconds()).createSchema(connections.getFirst());
    } catch (SQLException failed) {
      closeQuietlyAfter(failed, connections);
      throw failed;
    }
    ConnectionPool pool = new ConnectionPool(connections);
    Workers workers =
        switch (settings.mode()) {
          case PLATFORM ->
              new PlatformWorkers(settings.platformWorkers(), settings.executorQueueBound());
          case VIRTUAL -> new VirtualWorkers(settings.virtualMaxInFlight());
        };
    return new SeatWaveNode(settings, workers, pool, clock);
  }

  /**
   * Returns the hold operation.
   *
   * @return the endpoint
   */
  public HoldEndpoint endpoint() {
    return endpoint;
  }

  /**
   * Returns the settings the node runs with.
   *
   * @return the settings
   */
  public NodeSettings settings() {
    return settings;
  }

  /**
   * Returns the workers, so a sampler can read their saturation.
   *
   * @return the workers
   */
  public Workers workers() {
    return workers;
  }

  /**
   * Returns the pool, so a sampler can read its saturation.
   *
   * @return the pool
   */
  public ConnectionPool pool() {
    return pool;
  }

  /**
   * Waits for accepted holds to finish, then closes every connection. The in-memory database
   * disappears with its last connection.
   *
   * @throws SQLException if a connection fails to close
   */
  @Override
  public void close() throws SQLException {
    workers.close();
    pool.close();
  }

  private static void closeQuietlyAfter(SQLException cause, List<Connection> connections) {
    for (Connection connection : connections) {
      try {
        connection.close();
      } catch (SQLException alsoFailed) {
        // Keep the first failure as the reason; the later one is attached, not lost.
        cause.addSuppressed(alsoFailed);
      }
    }
  }
}
