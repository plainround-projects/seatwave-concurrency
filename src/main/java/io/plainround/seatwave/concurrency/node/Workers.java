package io.plainround.seatwave.concurrency.node;

/**
 * Runs holds on worker threads and refuses a hold at once when the bound is reached.
 *
 * <p>The interface is sealed because the module compares exactly two thread models. Every
 * implementation is thread-safe: {@link #tryExecute} is called by the accept loop while workers
 * finish other holds.
 */
public sealed interface Workers extends AutoCloseable permits PlatformWorkers, VirtualWorkers {

  /**
   * Hands a task to a worker, or refuses it.
   *
   * @param task the work of one hold; it must finish in bounded time
   * @return true if the task was accepted, false if the bound was reached and nothing will run
   */
  boolean tryExecute(Runnable task);

  /**
   * Counts the threads that exist for SeatWave work now.
   *
   * @return the pool size in platform mode; the virtual threads in flight in virtual mode
   */
  int threadsAlive();

  /**
   * Counts the holds accepted but not yet started.
   *
   * @return the executor queue length in platform mode; 0 in virtual mode, where every accepted
   *     hold starts on its own thread at once
   */
  int queued();

  /**
   * Stops accepting work and waits for the accepted holds to finish. The wait is bounded because
   * every hold is bounded by the queue, pool and database limits.
   */
  @Override
  void close();
}
