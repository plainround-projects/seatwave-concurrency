package io.plainround.seatwave.concurrency.threads;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Measures what it costs to have many threads waiting at once: the time to start them, the heap
 * they add, and the platform threads the JVM then has. Stateless.
 */
public final class ThreadCost {

  private ThreadCost() {}

  /**
   * What one measurement found.
   *
   * @param kind platform or virtual threads
   * @param threads how many were started and left waiting
   * @param startTime the time to start all of them until each was waiting
   * @param heapGrowthBytes heap used while they waited, minus heap used before; virtual threads
   *     keep their stacks on the heap, platform threads keep theirs outside it
   * @param platformThreadsInJvm the JVM's platform threads while they waited
   */
  public record Result(
      ExecutorMode kind,
      int threads,
      Duration startTime,
      long heapGrowthBytes,
      int platformThreadsInJvm) {

    /** Creates the result. */
    public Result {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(startTime, "startTime");
    }
  }

  /**
   * Starts threads that each wait on one latch, measures, then releases and joins them.
   *
   * @param kind platform or virtual threads
   * @param threads how many, at least 1
   * @param waitLimit the longest any start, wait or join may take
   * @return what was measured
   * @throws InterruptedException if interrupted while waiting
   */
  public static Result measure(ExecutorMode kind, int threads, Duration waitLimit)
      throws InterruptedException {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(waitLimit, "waitLimit");
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be 1 or more: " + threads);
    }
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    Thread.Builder builder =
        switch (kind) {
          case PLATFORM -> Thread.ofPlatform().name("seatwave-cost-", 0);
          case VIRTUAL -> Thread.ofVirtual().name("seatwave-cost-", 0);
        };
    CountDownLatch waiting = new CountDownLatch(threads);
    CountDownLatch release = new CountDownLatch(1);
    // A hint, not a guarantee: it makes the before and after readings mostly live objects.
    System.gc();
    long heapBefore = memory.getHeapMemoryUsage().getUsed();
    long startNanos = System.nanoTime();
    List<Thread> started = new ArrayList<>(threads);
    for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
      started.add(builder.start(() -> waitForRelease(waiting, release, waitLimit)));
    }
    if (!waiting.await(waitLimit.toNanos(), TimeUnit.NANOSECONDS)) {
      release.countDown();
      throw new IllegalStateException("not every thread started within " + waitLimit);
    }
    Duration startTime = Duration.ofNanos(System.nanoTime() - startNanos);
    int platformThreads = ManagementFactory.getThreadMXBean().getThreadCount();
    System.gc();
    long heapGrowth = memory.getHeapMemoryUsage().getUsed() - heapBefore;
    release.countDown();
    for (Thread thread : started) {
      if (!thread.join(waitLimit)) {
        throw new IllegalStateException("a thread did not finish within " + waitLimit);
      }
    }
    return new Result(kind, threads, startTime, heapGrowth, platformThreads);
  }

  private static void waitForRelease(
      CountDownLatch waiting, CountDownLatch release, Duration waitLimit) {
    waiting.countDown();
    try {
      // Every thread parks here: this is the cost of a thread that waits, as a hold waits on I/O.
      if (!release.await(waitLimit.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new IllegalStateException("the release did not come within " + waitLimit);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
