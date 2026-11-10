package io.plainround.seatwave.concurrency.node;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The hold operation of one SeatWave node, without the HTTP layer: what runs after the request has
 * been parsed and before the response is written.
 *
 * <p>The invariant of this module lives here: no hold waits without bound. Every hold passes three
 * limits, and over any of them it is refused with 503 instead of being left waiting: the executor's
 * bound (checked at the door), the queue wait limit (checked when a worker starts it), and the
 * pool's borrow limit (checked while it waits for a connection). The statement has its own timeout.
 * Every returned future completes.
 *
 * <p>Thread-safe: it keeps no mutable state of its own; the workers, the pool and the store are
 * thread-safe.
 */
public final class HoldEndpoint {

  private final Workers workers;
  private final ConnectionPool pool;
  private final SeatStore seatStore;
  private final Limits limits;
  private final LongSupplier nanoClock;

  /**
   * The waits one hold may spend, taken from {@link NodeSettings}.
   *
   * @param queueWaitLimit the longest from arrival to the start of the work
   * @param poolBorrowLimit the longest wait for a connection
   * @param databaseWait how long the remote database takes, while the connection is held
   */
  public record Limits(Duration queueWaitLimit, Duration poolBorrowLimit, Duration databaseWait) {

    /** Creates the limits. */
    public Limits {
      Objects.requireNonNull(queueWaitLimit, "queueWaitLimit");
      Objects.requireNonNull(poolBorrowLimit, "poolBorrowLimit");
      Objects.requireNonNull(databaseWait, "databaseWait");
    }

    /**
     * Takes the limits from the node settings.
     *
     * @param settings the node settings
     * @return the limits
     */
    public static Limits of(NodeSettings settings) {
      return new Limits(
          settings.queueWaitLimit(), settings.poolBorrowLimit(), settings.databaseWait());
    }
  }

  /**
   * Creates the endpoint.
   *
   * @param workers the thread model
   * @param pool the connection pool
   * @param seatStore the seats table
   * @param limits the waits allowed
   * @param nanoClock a monotonic clock in nanoseconds: {@code System::nanoTime}, or a fake in tests
   */
  public HoldEndpoint(
      Workers workers,
      ConnectionPool pool,
      SeatStore seatStore,
      Limits limits,
      LongSupplier nanoClock) {
    this.workers = Objects.requireNonNull(workers, "workers");
    this.pool = Objects.requireNonNull(pool, "pool");
    this.seatStore = Objects.requireNonNull(seatStore, "seatStore");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
  }

  /**
   * Accepts a hold, or refuses it at once. Never blocks the caller, which plays the accept loop.
   *
   * @param request the validated request
   * @return a future that always completes with a reply, or exceptionally only on a bug
   */
  public CompletableFuture<HoldReply> submit(HoldRequest request) {
    Objects.requireNonNull(request, "request");
    long arrivedNanos = nanoClock.getAsLong();
    CompletableFuture<HoldReply> reply = new CompletableFuture<>();
    boolean isAccepted =
        workers.tryExecute(() -> {
          try {
            reply.complete(serve(request, arrivedNanos));
          } catch (RuntimeException bug) {
            // A bug must not leave the caller waiting forever: complete, then let it surface.
            reply.completeExceptionally(bug);
            throw bug;
          }
        });
    if (!isAccepted) {
      reply.complete(HoldReply.refusedAtTheDoor(HoldOutcome.QUEUE_FULL));
    }
    return reply;
  }

  private HoldReply serve(HoldRequest request, long arrivedNanos) {
    long startedNanos = nanoClock.getAsLong();
    long queueWaitNanos = startedNanos - arrivedNanos;
    // A hold that already used its whole budget in the queue is refused before it costs anything.
    if (queueWaitNanos > limits.queueWaitLimit().toNanos()) {
      return new HoldReply(HoldOutcome.QUEUE_WAIT_EXCEEDED, queueWaitNanos, 0, 0);
    }
    try {
      Optional<ConnectionPool.Lease> borrowed = pool.borrow(limits.poolBorrowLimit());
      long borrowedNanos = nanoClock.getAsLong();
      long poolWaitNanos = borrowedNanos - startedNanos;
      if (borrowed.isEmpty()) {
        return new HoldReply(HoldOutcome.POOL_TIMEOUT, queueWaitNanos, poolWaitNanos, 0);
      }
      try (ConnectionPool.Lease lease = borrowed.get()) {
        HoldOutcome outcome = seatStore.hold(lease.connection(), request);
        waitForRemoteDatabase();
        long workNanos = nanoClock.getAsLong() - borrowedNanos;
        return new HoldReply(outcome, queueWaitNanos, poolWaitNanos, workNanos);
      }
    } catch (SQLException databaseFailed) {
      // The seat's state is unknown, so the only safe answer is a refusal the fan can retry.
      return new HoldReply(HoldOutcome.DATABASE_FAILED, queueWaitNanos, 0, 0);
    } catch (InterruptedException interrupted) {
      // Only a shutdown interrupts a worker: keep the flag for the executor and stop this hold.
      Thread.currentThread().interrupt();
      return new HoldReply(HoldOutcome.INTERRUPTED, queueWaitNanos, 0, 0);
    }
  }

  // Embedded H2 answers in well under a millisecond. A real database on another machine adds a
  // network round trip, a disk flush and lock waits; this wait stands for them, with the
  // connection still borrowed, which is what makes the pool the queue.
  private void waitForRemoteDatabase() throws InterruptedException {
    if (!limits.databaseWait().isZero()) {
      Thread.sleep(limits.databaseWait());
    }
  }
}
