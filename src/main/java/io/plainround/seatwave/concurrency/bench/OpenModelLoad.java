package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.HoldOutcome;
import io.plainround.seatwave.concurrency.node.HoldReply;
import io.plainround.seatwave.concurrency.node.HoldRequest;
import io.plainround.seatwave.concurrency.node.NodeSettings;
import io.plainround.seatwave.concurrency.node.PlatformWorkers;
import io.plainround.seatwave.concurrency.node.PoolSnapshot;
import io.plainround.seatwave.concurrency.node.SeatStore;
import io.plainround.seatwave.concurrency.node.SeatWaveNode;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * An open-model load generator: holds arrive at random times at a set average rate, whether or not
 * the node keeps up, as fans do at an on-sale.
 *
 * <p>Arrivals form a Poisson process with a fixed count: rate x duration holds at independent,
 * uniformly random moments, from a fixed seed, so a run is repeatable. Each latency is measured
 * from the planned send time, not the actual one, so a late send caused by a busy machine counts
 * against the node instead of hiding (the error called coordinated omission). The calling thread
 * plays SeatWave's accept loop. Not thread-safe: one thread runs one step at a time.
 */
public final class OpenModelLoad {

  private static final double NANOS_PER_SECOND = 1e9;
  private static final double HUNDRED_PERCENT = 100.0;
  private static final List<String> CENSUS_GROUPS =
      List.of(PlatformWorkers.threadNamePrefix(), "ForkJoinPool-");

  private final BenchmarkSettings settings;

  /**
   * Creates the generator.
   *
   * @param settings the rates, durations, seed and timeouts
   */
  public OpenModelLoad(BenchmarkSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /**
   * Warms the node up at the first rate, then runs every step in order.
   *
   * @param node a started node
   * @return one result per step
   * @throws InterruptedException if the calling thread is interrupted
   */
  public List<StepResult> runAll(SeatWaveNode node) throws InterruptedException {
    Objects.requireNonNull(node, "node");
    // The warm-up lets the JIT compiler compile the hot path; its numbers are thrown away.
    runStep(node, settings.stepRates().getFirst(), settings.warmup());
    List<StepResult> results = new ArrayList<>();
    for (int offeredRps : settings.stepRates()) {
      results.add(runStep(node, offeredRps, settings.stepDuration()));
    }
    return List.copyOf(results);
  }

  /**
   * Sends holds at one average rate for one duration and measures every reply.
   *
   * @param node a started node
   * @param offeredRps the average arrivals per second
   * @param duration how long to send
   * @return the step's result
   * @throws InterruptedException if the calling thread is interrupted
   */
  public StepResult runStep(SeatWaveNode node, int offeredRps, Duration duration)
      throws InterruptedException {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(duration, "duration");
    long cpuBeforeNanos = processCpuNanos();
    long startNanos = System.nanoTime();
    Sampler sampler = new Sampler(node);
    List<Pending> pending;
    List<CensusLine> census;
    try (ScheduledExecutorService samplerExecutor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("seatwave-sampler").factory())) {
      samplerExecutor.scheduleAtFixedRate(
          sampler::sample, 0, settings.sampleInterval().toNanos(), TimeUnit.NANOSECONDS);
      Future<List<CensusLine>> censusLater =
          offeredRps == settings.censusRate()
              ? samplerExecutor.schedule(
                  () -> ThreadCensus.take(CENSUS_GROUPS),
                  duration.toNanos() / 2,
                  TimeUnit.NANOSECONDS)
              : CompletableFuture.completedFuture(List.of());
      pending = sendArrivals(node, offeredRps, duration, startNanos);
      census = awaitCensus(censusLater, duration);
    }
    long wallNanos = System.nanoTime() - startNanos;
    double cpuPercent =
        (processCpuNanos() - cpuBeforeNanos)
            * HUNDRED_PERCENT
            / ((double) wallNanos * Runtime.getRuntime().availableProcessors());
    StepResult result =
        collect(
            pending,
            new StepFrame(node.settings().mode(), offeredRps, duration),
            new Observed(sampler.saturation(), cpuPercent, census));
    awaitDrain(node.settings(), pending);
    return result;
  }

  private List<Pending> sendArrivals(
      SeatWaveNode node, int offeredRps, Duration duration, long startNanos) {
    // One seed per rate: every run of the same step sends the same holds at the same moments.
    Random random = new Random(settings.randomSeed() + offeredRps);
    long durationNanos = duration.toNanos();
    int arrivals = (int) Math.round(offeredRps * (durationNanos / NANOS_PER_SECOND));
    // Exactly rate x duration arrivals at independent, uniformly random moments: a Poisson process
    // with its count fixed, so every step offers exactly its rate and is still random, as fans are.
    long[] offsetsNanos = new long[arrivals];
    for (int arrivalIndex = 0; arrivalIndex < arrivals; arrivalIndex++) {
      offsetsNanos[arrivalIndex] = (long) (random.nextDouble() * durationNanos);
    }
    Arrays.sort(offsetsNanos);
    int seatCount = SeatStore.SECTION_COUNT * SeatStore.SEATS_PER_SECTION;
    List<Pending> pending = new ArrayList<>(arrivals);
    for (long offsetNanos : offsetsNanos) {
      long plannedNanos = startNanos + offsetNanos;
      parkUntil(plannedNanos);
      HoldRequest request =
          new HoldRequest(
              SeatStore.DEMO_EVENT_ID,
              SeatStore.seatLabel(random.nextInt(seatCount)),
              "fan_" + pending.size());
      CompletableFuture<Timed> reply =
          node.endpoint().submit(request).thenApply(answer -> new Timed(answer, System.nanoTime()));
      pending.add(new Pending(plannedNanos, reply));
    }
    return pending;
  }

  private StepResult collect(List<Pending> pending, StepFrame frame, Observed observed)
      throws InterruptedException {
    long clientTimeoutNanos = settings.clientTimeout().toNanos();
    Tally tally = new Tally(pending.size());
    for (Pending one : pending) {
      long patienceNanos = one.plannedNanos() + clientTimeoutNanos - System.nanoTime();
      try {
        Timed timed = one.reply().get(Math.max(0, patienceNanos), TimeUnit.NANOSECONDS);
        long latencyNanos = timed.completedNanos() - one.plannedNanos();
        if (latencyNanos > clientTimeoutNanos) {
          tally.clientTimeouts++;
        } else {
          tally.count(timed.reply(), latencyNanos);
        }
      } catch (TimeoutException gaveUp) {
        // The fan stopped waiting; the node still owes the hold an answer, checked in awaitDrain.
        tally.clientTimeouts++;
      } catch (ExecutionException bug) {
        throw new IllegalStateException("a hold failed with an exception", bug.getCause());
      }
    }
    double achievedRps =
        (tally.created + tally.seatTaken) * NANOS_PER_SECOND / (double) frame.duration().toNanos();
    return new StepResult(
        frame.mode(),
        frame.offeredRps(),
        pending.size(),
        tally.created,
        tally.seatTaken,
        tally.refusedQueue,
        tally.refusedPool,
        tally.otherFailures,
        tally.clientTimeouts,
        achievedRps,
        LatencySummary.of(tally.goodLatencies()),
        LatencySummary.of(tally.poolWaits()),
        observed.saturation(),
        observed.cpuPercent(),
        observed.census());
  }

  // The node promises that every hold ends in bounded time. If any is still running after the
  // longest legal hold, that promise is broken, and the benchmark stops rather than report numbers.
  private void awaitDrain(NodeSettings node, List<Pending> pending) throws InterruptedException {
    Duration longestLegalHold =
        node.queueWaitLimit()
            .plus(node.poolBorrowLimit())
            .plus(node.databaseWait())
            .plus(Duration.ofSeconds(node.statementTimeoutSeconds()))
            .plus(settings.clientTimeout());
    CompletableFuture<?>[] replies =
        pending.stream().map(Pending::reply).toArray(CompletableFuture<?>[]::new);
    try {
      CompletableFuture.allOf(replies).get(longestLegalHold.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException invariantBroken) {
      throw new IllegalStateException("holds were still running after the longest legal hold");
    } catch (ExecutionException bug) {
      throw new IllegalStateException("a hold failed with an exception", bug.getCause());
    }
  }

  private static List<CensusLine> awaitCensus(Future<List<CensusLine>> census, Duration duration)
      throws InterruptedException {
    try {
      return census.get(duration.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("the thread census did not finish", failed);
    }
  }

  private static void parkUntil(long plannedNanos) {
    long remainingNanos = plannedNanos - System.nanoTime();
    while (remainingNanos > 0) {
      // Pacing, not synchronization: the accept loop sleeps until the next fan arrives.
      LockSupport.parkNanos(remainingNanos);
      remainingNanos = plannedNanos - System.nanoTime();
    }
  }

  private static long processCpuNanos() {
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
      return operatingSystem.getProcessCpuTime();
    }
    throw new IllegalStateException("this JVM does not report process CPU time");
  }

  private record Pending(long plannedNanos, CompletableFuture<Timed> reply) {}

  private record StepFrame(ExecutorMode mode, int offeredRps, Duration duration) {}

  private record Observed(Saturation saturation, double cpuPercent, List<CensusLine> census) {}

  private record Timed(HoldReply reply, long completedNanos) {}

  /** Counts outcomes on the calling thread only. Not thread-safe. */
  private static final class Tally {
    private final long[] goodLatencyNanos;
    private final long[] poolWaitNanos;
    private int goodCount;
    private int poolWaitCount;
    private long created;
    private long seatTaken;
    private long refusedQueue;
    private long refusedPool;
    private long otherFailures;
    private long clientTimeouts;

    Tally(int capacity) {
      goodLatencyNanos = new long[capacity];
      poolWaitNanos = new long[capacity];
    }

    void count(HoldReply reply, long latencyNanos) {
      if (reply.outcome().isGood()) {
        goodLatencyNanos[goodCount++] = latencyNanos;
      }
      if (reply.outcome().isGood() || reply.outcome() == HoldOutcome.POOL_TIMEOUT) {
        poolWaitNanos[poolWaitCount++] = reply.poolWaitNanos();
      }
      switch (reply.outcome()) {
        case CREATED -> created++;
        case SEAT_TAKEN -> seatTaken++;
        case QUEUE_FULL, QUEUE_WAIT_EXCEEDED -> refusedQueue++;
        case POOL_TIMEOUT -> refusedPool++;
        case DATABASE_FAILED, INTERRUPTED -> otherFailures++;
      }
    }

    long[] goodLatencies() {
      return Arrays.copyOf(goodLatencyNanos, goodCount);
    }

    long[] poolWaits() {
      return Arrays.copyOf(poolWaitNanos, poolWaitCount);
    }
  }

  /**
   * Reads the saturation counters on the sampler thread. Thread-safe: every field is atomic, so the
   * calling thread may read the result after the sampler stops.
   */
  private static final class Sampler {
    private final SeatWaveNode node;
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final AtomicInteger maxThreadsAlive = new AtomicInteger();
    private final AtomicInteger maxQueued = new AtomicInteger();
    private final AtomicInteger maxPoolWaiters = new AtomicInteger();
    private final AtomicInteger maxPlatformThreads = new AtomicInteger();
    private final LongAdder busyConnectionReadings = new LongAdder();
    private final LongAdder readings = new LongAdder();

    Sampler(SeatWaveNode node) {
      this.node = node;
    }

    void sample() {
      PoolSnapshot pool = node.pool().snapshot();
      maxThreadsAlive.accumulateAndGet(node.workers().threadsAlive(), Math::max);
      maxQueued.accumulateAndGet(node.workers().queued(), Math::max);
      maxPoolWaiters.accumulateAndGet(pool.waitingBorrowers(), Math::max);
      maxPlatformThreads.accumulateAndGet(threads.getThreadCount(), Math::max);
      busyConnectionReadings.add(pool.busy());
      readings.increment();
    }

    Saturation saturation() {
      long count = Math.max(readings.sum(), 1);
      double meanBusy = busyConnectionReadings.sum() / (double) count;
      return new Saturation(
          maxThreadsAlive.get(),
          maxQueued.get(),
          maxPoolWaiters.get(),
          maxPlatformThreads.get(),
          Math.min(HUNDRED_PERCENT, meanBusy * HUNDRED_PERCENT / node.pool().snapshot().size()));
    }
  }
}
