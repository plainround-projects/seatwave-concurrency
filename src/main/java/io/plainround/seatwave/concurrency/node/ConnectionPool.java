package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * A fixed set of database connections that holds borrow, use and give back.
 *
 * <p>Every borrow waits at most its limit; a borrower that gets nothing in time receives an empty
 * result and the caller refuses the hold. The counters show saturation: how many borrowers are
 * waiting right now, how many connections are busy, how many borrows timed out.
 *
 * <p>Thread-safe: the idle connections live in a fair blocking queue, so waiting borrowers are
 * served in arrival order, and every counter is atomic. A connection is used by one borrower at a
 * time, between {@link #borrow} and {@link Lease#close}.
 */
public final class ConnectionPool implements AutoCloseable {

  private final List<Connection> allConnections;
  private final BlockingQueue<Connection> idleConnections;
  private final AtomicInteger waitingBorrowers = new AtomicInteger();
  private final LongAdder borrowTimeouts = new LongAdder();

  /**
   * Creates a pool around connections that are already open. The pool owns them from now on and
   * closes them in {@link #close}.
   *
   * @param connections the open connections; at least one
   */
  public ConnectionPool(List<Connection> connections) {
    Objects.requireNonNull(connections, "connections");
    if (connections.isEmpty()) {
      throw new IllegalArgumentException("a pool needs at least one connection");
    }
    this.allConnections = List.copyOf(connections);
    // Fair: a borrower that has waited longest gets the next connection, so no hold starves.
    this.idleConnections = new ArrayBlockingQueue<>(allConnections.size(), true, allConnections);
  }

  /**
   * Waits up to the limit for a free connection.
   *
   * @param limit the longest this call may wait
   * @return a lease on one connection, or empty if none became free in time
   * @throws InterruptedException if the waiting thread is interrupted
   */
  public Optional<Lease> borrow(Duration limit) throws InterruptedException {
    Objects.requireNonNull(limit, "limit");
    waitingBorrowers.incrementAndGet();
    try {
      Connection connection = idleConnections.poll(limit.toNanos(), TimeUnit.NANOSECONDS);
      if (connection == null) {
        borrowTimeouts.increment();
        return Optional.empty();
      }
      return Optional.of(new Lease(connection));
    } finally {
      waitingBorrowers.decrementAndGet();
    }
  }

  /**
   * Returns the saturation counters at this moment.
   *
   * @return a snapshot; each field is read separately, so under load they may be a moment apart
   */
  public PoolSnapshot snapshot() {
    int size = allConnections.size();
    return new PoolSnapshot(
        size, size - idleConnections.size(), waitingBorrowers.get(), borrowTimeouts.longValue());
  }

  /**
   * Closes every connection, busy or idle. Call it only after every lease has been given back.
   *
   * @throws SQLException if a connection fails to close; the others are still closed
   */
  @Override
  public void close() throws SQLException {
    List<SQLException> failures = new ArrayList<>();
    for (Connection connection : allConnections) {
      try {
        connection.close();
      } catch (SQLException failed) {
        failures.add(failed);
      }
    }
    if (!failures.isEmpty()) {
      SQLException first = failures.getFirst();
      failures.stream().skip(1).forEach(first::addSuppressed);
      throw first;
    }
  }

  /** One borrowed connection. Closing the lease gives the connection back to the pool. */
  public final class Lease implements AutoCloseable {

    private final Connection connection;
    private boolean isReturned;

    private Lease(Connection connection) {
      this.connection = connection;
    }

    /**
     * Returns the borrowed connection.
     *
     * @return the connection; valid until this lease is closed
     */
    public Connection connection() {
      return connection;
    }

    /**
     * Gives the connection back. A second call does nothing. Not thread-safe: one lease, one user.
     */
    @Override
    public void close() {
      if (isReturned) {
        return;
      }
      isReturned = true;
      // offer never fails here: the queue holds exactly as many slots as there are connections.
      idleConnections.offer(connection);
    }
  }
}
