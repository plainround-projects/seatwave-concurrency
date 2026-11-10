package io.plainround.seatwave.concurrency.threads;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shows when a blocked virtual thread frees its carrier and when it pins it, on JDK 25.
 *
 * <p>Since JDK 24, a virtual thread that blocks inside {@code synchronized} no longer pins its
 * carrier. A few cases remain; one is blocking inside a class initializer, and another is waiting
 * for a class that another thread is initializing. Each demo starts one virtual thread per carrier,
 * lets each block for the same time, and then asks an unrelated virtual thread to run. The class
 * initializer runs once per JVM, so the pinning demo shows its effect only on its first call.
 * Stateless.
 */
public final class CarrierPinning {

  // How long each blocker blocks: long enough to read, short enough for a demo.
  static final Duration BLOCKS_FOR = Duration.ofMillis(500);

  private CarrierPinning() {}

  /**
   * Blocks every carrier's virtual thread in {@code Thread.sleep}, which unmounts it, and measures
   * how long an unrelated virtual task waits to start.
   *
   * @param waitLimit the longest any wait in the demo may take
   * @return the delay from submitting the unrelated task to the moment it ran
   * @throws InterruptedException if interrupted while waiting
   * @throws ExecutionException if a task failed
   * @throws TimeoutException if a wait passed the limit
   */
  public static Duration delayWhileSleeping(Duration waitLimit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return unrelatedTaskDelay(
        () -> {
          Thread.sleep(BLOCKS_FOR);
          return 1;
        },
        waitLimit);
  }

  /**
   * Blocks every carrier's virtual thread in or on a class initializer, which pins it, and measures
   * how long an unrelated virtual task waits to start.
   *
   * @param waitLimit the longest any wait in the demo may take
   * @return the delay from submitting the unrelated task to the moment it ran
   * @throws InterruptedException if interrupted while waiting
   * @throws ExecutionException if a task failed
   * @throws TimeoutException if a wait passed the limit
   */
  public static Duration delayWhilePinned(Duration waitLimit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return unrelatedTaskDelay(() -> SlowToInitialize.VALUE, waitLimit);
  }

  private static Duration unrelatedTaskDelay(Callable<Integer> blocker, Duration waitLimit)
      throws InterruptedException, ExecutionException, TimeoutException {
    Objects.requireNonNull(waitLimit, "waitLimit");
    // The scheduler's parallelism defaults to the number of processors: one blocker per carrier.
    // Once every blocker has counted down, each one is running on its own carrier.
    int blockers = Runtime.getRuntime().availableProcessors();
    CountDownLatch blockersStarted = new CountDownLatch(blockers);
    try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int blockerIndex = 0; blockerIndex < blockers; blockerIndex++) {
        virtualThreads.submit(() -> {
          blockersStarted.countDown();
          return blocker.call();
        });
      }
      if (!blockersStarted.await(waitLimit.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new TimeoutException("the blocking threads did not start within " + waitLimit);
      }
      long submittedNanos = System.nanoTime();
      Future<Long> unrelated = virtualThreads.submit(System::nanoTime);
      long ranAtNanos = unrelated.get(waitLimit.toNanos(), TimeUnit.NANOSECONDS);
      return Duration.ofNanos(ranAtNanos - submittedNanos);
    }
  }

  /** A class whose initializer blocks, as a slow static setup that calls the network would. */
  private static final class SlowToInitialize {
    static final int VALUE = initialize();

    private static int initialize() {
      try {
        Thread.sleep(BLOCKS_FOR);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return 1;
    }
  }
}
