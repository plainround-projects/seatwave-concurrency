package io.plainround.seatwave.concurrency.node;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A fixed pool of platform threads with a bounded queue in front of it: SeatWave version 0's thread
 * model, with the queue bound that version 0 lacked.
 *
 * <p>Thread-safe: {@link ThreadPoolExecutor} and its {@link ArrayBlockingQueue} are.
 */
public final class PlatformWorkers implements Workers {

  private static final String THREAD_NAME_PREFIX = "seatwave-worker-";
  // Core and maximum size are equal, so threads never time out; the keep-alive value is unused.
  private static final long UNUSED_KEEP_ALIVE = 0L;

  private final ThreadPoolExecutor executor;

  /**
   * Starts every worker thread now, so the first holds do not pay for thread creation.
   *
   * @param workerThreads the pool size
   * @param queueBound how many accepted holds may wait for a thread
   */
  public PlatformWorkers(int workerThreads, int queueBound) {
    if (workerThreads < 1 || queueBound < 1) {
      throw new IllegalArgumentException("workers and queue bound must be 1 or more");
    }
    this.executor =
        new ThreadPoolExecutor(
            workerThreads,
            workerThreads,
            UNUSED_KEEP_ALIVE,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueBound),
            Thread.ofPlatform().name(THREAD_NAME_PREFIX, 1).factory(),
            // Abort, never "caller runs": running a hold on the accept loop would stall every
            // other arrival behind it.
            new ThreadPoolExecutor.AbortPolicy());
    executor.prestartAllCoreThreads();
  }

  /**
   * Returns the prefix of every worker thread's name, so a thread census can find them.
   *
   * @return the prefix
   */
  public static String threadNamePrefix() {
    return THREAD_NAME_PREFIX;
  }

  @Override
  public boolean tryExecute(Runnable task) {
    try {
      executor.execute(task);
      return true;
    } catch (RejectedExecutionException queueFull) {
      // The bound did its job: this is the refusal the module teaches, not an error to log.
      return false;
    }
  }

  @Override
  public int threadsAlive() {
    return executor.getPoolSize();
  }

  @Override
  public int queued() {
    return executor.getQueue().size();
  }

  @Override
  public void close() {
    executor.close();
  }
}
