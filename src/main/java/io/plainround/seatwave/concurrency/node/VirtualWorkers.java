package io.plainround.seatwave.concurrency.node;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * One new virtual thread per hold, with a semaphore that caps how many holds are in flight.
 *
 * <p>Virtual threads are cheap, so they are never pooled; the semaphore, not a pool, is the bound,
 * as the virtual-thread JEP recommends. A hold that finds no permit is refused at once.
 *
 * <p>Thread-safe: the executor and the semaphore are.
 */
public final class VirtualWorkers implements Workers {

  private static final String THREAD_NAME_PREFIX = "seatwave-virtual-";

  private final int maxInFlight;
  private final Semaphore inFlightPermits;
  private final ExecutorService executor;

  /**
   * Creates the executor.
   *
   * @param maxInFlight the most holds that may run or wait at once
   */
  public VirtualWorkers(int maxInFlight) {
    if (maxInFlight < 1) {
      throw new IllegalArgumentException("maxInFlight must be 1 or more: " + maxInFlight);
    }
    this.maxInFlight = maxInFlight;
    this.inFlightPermits = new Semaphore(maxInFlight);
    this.executor =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name(THREAD_NAME_PREFIX, 1).factory());
  }

  @Override
  public boolean tryExecute(Runnable task) {
    // tryAcquire never waits: the accept loop must not block, so a full node answers at once.
    if (!inFlightPermits.tryAcquire()) {
      return false;
    }
    try {
      executor.execute(() -> {
        try {
          task.run();
        } finally {
          inFlightPermits.release();
        }
      });
      return true;
    } catch (RejectedExecutionException closed) {
      // The executor is shutting down: give the permit back and refuse, as a full node would.
      inFlightPermits.release();
      return false;
    }
  }

  @Override
  public int threadsAlive() {
    return maxInFlight - inFlightPermits.availablePermits();
  }

  @Override
  public int queued() {
    return 0;
  }

  @Override
  public void close() {
    executor.close();
  }
}
