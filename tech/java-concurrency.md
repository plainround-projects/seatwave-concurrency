# Java concurrency: threads, executors, queues, semaphores and the interrupt flag

## What it is

**Concurrency** means doing several things in overlapping time. In Java the unit of doing is the **thread**: one line of execution inside a running program. A program may have many threads. They share the program's memory and run at the same time on different processor cores, or take turns on one core.

This file explains threads from zero, using this repository's code as the examples: what a thread is, the two kinds the repository compares (platform threads and virtual threads), executors (objects that run tasks on threads), bounded queues, semaphores, latches, futures, atomic counters, `volatile`, interruption, and timeouts. All of it is in the Java standard library (`java.util.concurrent` and `java.lang`). No extra library is used.

## Why this repository uses it

Module 4 asks what limits the throughput of one node. The answer is in how threads wait. A hold keeps a thread (and a database connection) for about 40 ms, and most of that time the thread does nothing: it waits for the database. The repository builds the node twice, with two thread models, and measures where each one runs out. Every queue and every wait in the code has a limit, because a wait with no limit is how a node hangs under load. Reading this file lets you follow every line of `PlatformWorkers`, `VirtualWorkers`, `ConnectionPool`, `HoldEndpoint`, `OpenModelLoad` and `EventLoopServer`.

## Where it appears

| Tool | Where in this repository |
|---|---|
| Platform thread, `Thread.ofPlatform()` | `PlatformWorkers`, `EventLoopServer` (the loop thread), `OpenModelLoad` (the sampler), `ThreadCost` |
| Virtual thread, `Thread.ofVirtual()`, `newThreadPerTaskExecutor`, `newVirtualThreadPerTaskExecutor` | `VirtualWorkers`, `EventLoopLab` (the fans), `CarrierPinning`, `ThreadCost` |
| `ThreadPoolExecutor` with a bounded queue and `AbortPolicy` | `PlatformWorkers` |
| `ScheduledExecutorService` | `OpenModelLoad.runStep` (the sampler and the census) |
| `ArrayBlockingQueue` (fair, with `poll` timeout) | `ConnectionPool`, `EventLoopServer.finishedReplies`, `PlatformWorkers` |
| `Semaphore` with `tryAcquire` | `VirtualWorkers` |
| `CountDownLatch` | `ThreadCost`, `EventLoopLab`, `CarrierPinning` |
| `CompletableFuture`, `Future` | `HoldEndpoint.submit`, `OpenModelLoad`, `EventLoopLab`, `CarrierPinning` |
| `AtomicInteger`, `LongAdder` | `ConnectionPool`, `OpenModelLoad.Sampler` |
| `volatile` | `EventLoopServer.isRunning` |
| `synchronized` | not used; it appears only in a comment of `CarrierPinning` |
| Interrupt flag | nine `catch (InterruptedException ...)` blocks that restore it |
| Try-with-resources on executors | `BenchmarkRun`, `OpenModelLoad`, `EventLoopLab`, `CarrierPinning` |

## How it works underneath

**A thread, a core and the scheduler.** A computer has a few **cores**, each able to run one instruction stream at a time. The operating system has a **scheduler** that decides which thread runs on which core, and for how long. When a thread must wait (for the disk, the network, a timer), the scheduler takes it off the core and runs another. Switching from one thread to another is a **context switch**, and it costs time.

**Thread states.** At any moment a thread is in one state. `RUNNABLE`: running or ready to run. `WAITING`: waiting with no time limit. `TIMED_WAITING`: waiting with a time limit. `BLOCKED`: waiting to enter a `synchronized` block. `NEW` and `TERMINATED` are before the start and after the end. The benchmark's thread census prints these names: in the unbounded run, 48 `seatwave-worker-` threads were `TIMED_WAITING in ConnectionPool.borrow` and 16 were `TIMED_WAITING in HoldEndpoint.waitForRemoteDatabase`.

**Platform threads.** A **platform thread** is a thin wrapper over an operating-system thread. It costs an operating-system thread: a stack that the system reserves (2,048 KiB by default here: `java -XX:+PrintFlagsFinal -version` printed `ThreadStackSize = 2048`) and an entry in the scheduler. The system limits how many exist: on the measuring machine `sysctl kern.num_taskthreads` printed 8192. In my `threadCost` run, 4,000 platform threads took 225.2 ms to start and the JVM then had 4,006 platform threads.

**Virtual threads (JDK 21).** A **virtual thread** is a thread that the JVM schedules itself. It is a small object whose stack lives on the heap and grows as needed, about 1 to 2 KiB for a thread that is waiting. The JVM runs virtual threads on a small pool of platform threads called **carriers**, by default one per processor (a `ForkJoinPool`). To run a virtual thread, the JVM **mounts** it on a carrier. When the virtual thread blocks (in `Thread.sleep`, a lock, a socket read, a blocking queue), the JVM saves its stack on the heap and **unmounts** it, and the carrier runs another virtual thread. When the wait ends, the thread is mounted again, maybe on a different carrier. So 100,000 waiting virtual threads need 12 carriers, not 100,000 operating-system threads. In my `threadCost` run, 100,000 virtual threads started in 134.0 ms and the JVM still had 20 platform threads.

Two more facts, both checked on JDK 25.0.2. Virtual threads are always **daemon** threads (they do not keep the program alive): `Thread.ofVirtual().unstarted(...).isDaemon()` printed `true`. And `Thread.getAllStackTraces()` does not list them: a sleeping virtual thread named `v-test` was not in the result. That is why `ThreadCensus` counts platform threads only.

**Pinning.** A virtual thread is **pinned** when it blocks but cannot unmount, so its carrier is stuck too. If all carriers are pinned, other virtual threads cannot run. Before JDK 24, blocking inside a `synchronized` block pinned the carrier. In JDK 24 (JEP 491) that changed. I checked on JDK 25.0.2: 12 virtual threads each slept 500 ms inside `synchronized` blocks, and an unrelated virtual task started after 0.6 ms. Blocking inside a **class initializer** still pins: `./gradlew -q pinning` printed 0.8 ms for the sleeping blockers and 501.6 ms for the class-initializer blockers. Rule of thumb for this repository: blocking is cheap for a virtual thread, so one thread per hold is fine; what limits the node is the pool the threads wait for.

**Executors.** Starting a thread by hand (`new Thread(...)`) for every task is clumsy. An **executor** (`ExecutorService`) is an object that takes tasks (a `Runnable` or a `Callable`) and runs them on threads it manages. The common kinds:

- A **thread pool** (`ThreadPoolExecutor`) keeps a fixed number of threads and a queue of waiting tasks. It saves the cost of starting a thread per task.
- A **thread-per-task executor** (`Executors.newThreadPerTaskExecutor(factory)`) starts a new thread for every task and has no queue. With virtual threads it is the normal choice.
- A **scheduled executor** (`ScheduledExecutorService`) runs a task after a delay or repeatedly.

**What `ThreadPoolExecutor` does with a new task**, in order:

1. If fewer than `corePoolSize` threads exist, start a new thread for the task.
2. Otherwise offer the task to the queue. If the queue accepts, done.
3. If the queue is full, try to start a thread up to `maximumPoolSize`.
4. If that is not possible either, call the **rejection handler**.

`PlatformWorkers` sets core and maximum equal (64), so step 3 cannot add a thread, and a full queue goes straight to the handler. With the default unbounded queue (`LinkedBlockingQueue`), step 2 always accepts and the pool never refuses, which is the unbounded queue the module warns about. The handler chosen here is `AbortPolicy`, which throws `RejectedExecutionException`. The alternative, `CallerRunsPolicy`, runs the task on the thread that submitted it, and in this node that thread is the accept loop, so it would stop accepting while it did the work.

**Bounded queues and rejection.** A **queue** is a first-in, first-out line. A **blocking queue** lets threads wait on it: `take()` waits for an item, `put(x)` waits for room. `ArrayBlockingQueue(capacity)` has a fixed capacity. A **bound** is a limit. A queue with a bound turns overload into a clear answer: when it is full, `offer(x)` returns `false` at once (and a pool rejects). A queue without a bound turns overload into memory growth and long waits. I ran `config/unbounded.properties`, which opens the queue to 100,000: at 600 holds per second the queue grew to 2,262 holds, p99 was 1,980 ms, 69.2 percent of holds got no good reply, and nothing was refused.

**A fair queue.** `new ArrayBlockingQueue<>(capacity, true, ...)` has **fairness** on: waiting threads are served in the order they arrived. Without it a thread can be overtaken again and again (**starved**).

**Semaphore.** A `Semaphore` holds a number of **permits**. `acquire()` takes one and waits if none is free. `tryAcquire()` takes one if free and returns `false` at once if not. `release()` gives one back. A semaphore is how you bound something that has no queue of its own, such as virtual threads. In an experiment I made `new Semaphore(1)` and called `tryAcquire()` twice: `true`, then `false`, with 0 permits left.

**CountDownLatch.** A latch is a gate with a count. Threads call `await()` and wait until the count reaches zero. Other threads call `countDown()` to lower it. `new CountDownLatch(1)` is a start gate that one thread opens for many. `new CountDownLatch(n)` waits for `n` things to happen.

**Future and CompletableFuture.** A **`Future`** is a handle on a result that is not ready yet. `get()` waits for the result; `get(timeout, unit)` waits at most that long and throws `TimeoutException` if it is not ready. A **`CompletableFuture`** is a future that some code can complete by hand with `complete(value)` or `completeExceptionally(error)`. It can also chain a step: `thenApply(f)` runs `f` on the result when it is ready, on the thread that completes it. `CompletableFuture.allOf(futures...)` is a future that completes when all of them have.

**Atomic counters.** `count++` on a plain `int` is three steps: read, add, write. Two threads can both read 5 and both write 6, so one increment is lost. This is a **race condition**. I ran two threads that each added 1 a million times: expected 2,000,000, and a plain `int` gave 1,862,759, a `volatile int` gave 1,833,451, while `AtomicInteger` and `LongAdder` both gave 2,000,000. An **atomic** operation is one that no other thread can see half done. `AtomicInteger.incrementAndGet()` is atomic. `LongAdder` is a counter that spreads its updates over several cells so many threads can add without fighting, and `sum()` adds the cells up: it is the right tool for a counter that is written often and read rarely. `accumulateAndGet(value, Math::max)` replaces the stored number with the larger of the two in one atomic step.

**`volatile` and `synchronized`.** Each thread may keep its own copy of a variable in a register or a cache. A **`volatile`** field is never cached this way: a write is seen by later reads in other threads. It makes a flag such as `isRunning` visible. It does **not** make `count++` atomic (see the `volatile int` result above). **`synchronized`** makes a block run by one thread at a time (a **lock**). This repository does not use `synchronized`: it uses queues, semaphores and atomics, which do the locking inside the library.

**Interruption.** A thread cannot be killed from outside. Instead another thread can **interrupt** it: `thread.interrupt()` sets the thread's **interrupt flag**. A blocking call such as `Thread.sleep`, `poll(timeout)` or `await` notices the flag, **clears it**, and throws `InterruptedException`. The convention: when you catch it you either let it go up, or you call `Thread.currentThread().interrupt()` to **set the flag again**, because catching the exception erased the only sign that someone asked this thread to stop. I ran it: after the `catch`, `isInterrupted()` printed `false`; after the restoring call it printed `true`. Code further up (an executor worker loop, `close`) looks at the flag to know it should stop. If you swallow it, the thread goes on as if nobody asked.

**A timeout on every blocking call.** A call that can wait forever will, one day. Every blocking call in this repository has a limit, set by the caller:

| Call | Limit | Where |
|---|---|---|
| `idleConnections.poll(limit, NANOSECONDS)` | the borrow limit | `ConnectionPool.borrow` |
| `inFlightPermits.tryAcquire()` | does not wait | `VirtualWorkers.tryExecute` |
| `executor.execute(task)` | does not wait: rejects | `PlatformWorkers.tryExecute` |
| `finishedReplies.offer(x)` | does not wait | `EventLoopServer.dispatch` |
| `Thread.sleep(databaseWait)` | the configured wait | `HoldEndpoint.waitForRemoteDatabase` |
| `update.setQueryTimeout(seconds)` | the statement timeout | `SeatStore.hold` |
| `reply.get(patience, NANOSECONDS)` | the client timeout | `OpenModelLoad.collect` |
| `CompletableFuture.allOf(...).get(limit)` | the longest legal hold | `OpenModelLoad.awaitDrain` |
| `latch.await(limit, NANOSECONDS)` | the wait limit | `ThreadCost`, `EventLoopLab`, `CarrierPinning` |
| `thread.join(limit)` | the wait limit | `ThreadCost`, `EventLoopServer.close` |
| `socket.connect(address, ms)`, `socket.setSoTimeout(ms)` | the socket timeout | `EventLoopLab.oneFan` |
| `LockSupport.parkNanos(remaining)` | the time to the next planned arrival | `OpenModelLoad.parkUntil` |
| `selector.select()` | none: `close()` calls `wakeup()` | `EventLoopServer.runLoop` |

The last row is the one exception, and it is by design: an event loop waits for events, and `wakeup()` is how it is told to stop.

**Why executors are closed with try-with-resources.** An executor owns threads. Platform threads are not daemon threads, so a pool nobody closed keeps the JVM running after `main` returns. Since Java 19, `ExecutorService` implements `AutoCloseable`: `close()` stops accepting tasks and waits until the accepted ones finish. If the thread that calls `close()` is interrupted while it waits, `close()` stops the running tasks (as `shutdownNow()` does) and keeps waiting for them to end. A `try (ExecutorService x = ...)` block therefore gives a guarantee: when the block ends, no task of this executor is still running. In an experiment I made a pool, filled it, opened a gate and called `close()`: `isTerminated()` was `true` afterwards.

## Walkthrough of this repository's use

Each snippet is copied from the source. The line-by-line explanation is in the walkthrough file of its package. This file ties them to the ideas above.

### Two thread models for one hold

The fixed pool with a bounded queue and the abort policy:

```java
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
```


- `new ArrayBlockingQueue<>(queueBound)` is the bound: at most `queueBound` accepted holds wait for a thread.
- `Thread.ofPlatform().name(THREAD_NAME_PREFIX, 1).factory()` makes the pool's platform threads, named `seatwave-worker-1`, `seatwave-worker-2` and then up by one for each thread.
- `new ThreadPoolExecutor.AbortPolicy()` is the rejection handler (step 4 of the rule above).
- `prestartAllCoreThreads()` starts every thread now.

Turning a rejection into a plain `false`:

```java
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
```


One virtual thread per hold, and a semaphore as the bound:

```java
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
```


- `tryAcquire()` never waits, so the accept loop never blocks.
- The permit is released in `finally`, so a task that throws cannot leak one.
- If the executor is closed (`RejectedExecutionException`), the permit goes back, so a late call during shutdown does not lose it.
- Counting permits in use gives `threadsAlive`: `maxInFlight - availablePermits()`.

### A bounded wait for a connection

```java
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
```


- `waitingBorrowers` is an `AtomicInteger`: raised before the wait, lowered in `finally`.
- `poll(limit, NANOSECONDS)` is the bound on the wait, and `borrowTimeouts` is a `LongAdder`.
- The queue is the fair `ArrayBlockingQueue` of idle connections (line 47), so the longest-waiting hold gets the next connection.

### Completing every future, and restoring the interrupt flag

```java
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
```

```java
    } catch (SQLException databaseFailed) {
      // The seat's state is unknown, so the only safe answer is a refusal the fan can retry.
      return new HoldReply(HoldOutcome.DATABASE_FAILED, queueWaitNanos, 0, 0);
    } catch (InterruptedException interrupted) {
      // Only a shutdown interrupts a worker: keep the flag for the executor and stop this hold.
      Thread.currentThread().interrupt();
      return new HoldReply(HoldOutcome.INTERRUPTED, queueWaitNanos, 0, 0);
    }
```


- The task completes the `CompletableFuture` on every path, including a bug (`completeExceptionally`), so no caller waits forever. A refusal at the door completes it at once.
- `Thread.currentThread().interrupt()` restores the flag that catching `InterruptedException` cleared. Only a shutdown interrupts a worker, and the executor's `close()` is the one waiting for the flag.

### The accept loop is a platform thread that never waits for a reply

```java
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
```

```java
  private static void parkUntil(long plannedNanos) {
    long remainingNanos = plannedNanos - System.nanoTime();
    while (remainingNanos > 0) {
      // Pacing, not synchronization: the accept loop sleeps until the next fan arrives.
      LockSupport.parkNanos(remainingNanos);
      remainingNanos = plannedNanos - System.nanoTime();
    }
  }
```


`parkUntil` uses `LockSupport.parkNanos`, which pauses the thread for up to the given time and may return early, so it loops and reads the clock again. The `.thenApply(...)` runs on the thread that completes the reply and stamps the completion time.

### The sampler and the executor that is closed by `try`

```java
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
```


The scheduled executor has one platform thread (`seatwave-sampler`). The block ends only after the arrivals were sent and the census is in hand, and then `close()` stops the sampler. The counters it writes are atomic, so the load thread can read them safely afterwards:

```java
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
```


### Latches: a start gate and a ready count

```java
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
```

```java
      CountDownLatch connected = new CountDownLatch(settings.clients());
      CountDownLatch startGate = new CountDownLatch(1);
      List<Future<Duration>> waits = new ArrayList<>();
      for (int clientIndex = 0; clientIndex < settings.clients(); clientIndex++) {
        String seatLabel = "A-" + (clientIndex + 1);
        waits.add(
            fans.submit(() -> oneFan(server.address(), seatLabel, settings, connected, startGate)));
      }
      if (!connected.await(settings.socketTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
        throw new IOException("not every client connected within the socket timeout");
      }
      startGate.countDown();
      return collect(waits, settings);
```


`ThreadCost` uses `waiting` (count of threads that are in place) and `release` (one gate that lets them go). `EventLoopLab` uses `connected` and `startGate` so that every fan sends at the same moment.

### One thread that must never block: the event loop

```java
  private volatile boolean isRunning = true;
```

```java
  @Override
  public void close() throws IOException {
    isRunning = false;
    selector.wakeup();
    try {
      if (!loopThread.join(closeLimit)) {
        throw new IllegalStateException("the event loop did not stop within " + closeLimit);
      }
    } catch (InterruptedException interrupted) {
      // Keep the interrupt visible to the caller, and report it the way I/O code does.
      Thread.currentThread().interrupt();
      InterruptedIOException stopped = new InterruptedIOException("interrupted while closing");
      stopped.initCause(interrupted);
      throw stopped;
    }
    offloadWorkers.close();
    for (SelectionKey key : selector.keys()) {
      key.channel().close();
    }
    selector.close();
  }
```


`isRunning` is `volatile`, because the loop thread reads it and `close` writes it from another thread. `selector.wakeup()` ends the loop's wait, the `join(closeLimit)` bounds the wait for the loop to end, and the `catch` restores the interrupt flag before it reports the interruption as an I/O exception.

### Pinning, measured

```java
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
```


One blocker per carrier fills every carrier. If they unmount (`Thread.sleep`), the unrelated task starts at once. If they pin (class initializer), it waits for the block time.

## Try it yourself

All commands run from the repository folder. Timing numbers differ on every run and every machine.

**1. Pinning.** `./gradlew -q pinning` printed:

```text
12 carriers; 12 virtual threads each block for 500 ms, then an unrelated virtual task is started.
blocked in Thread.sleep (unmounts):    the unrelated task waited    0.8 ms to start
blocked in a class initializer (pins): the unrelated task waited  501.6 ms to start
```

**2. What waiting threads cost.** `./gradlew -q threadCost --args='config/thread-cost.properties'` printed, among other lines, `platform 4000 225.2 1146 293 4006` and `virtual 100000 134.0 114771 1175 20`: 4,000 platform threads left 4,006 platform threads in the JVM, and 100,000 virtual threads left 20.

**3. The refusal tests.** `./gradlew test --tests '*WorkersTest' --tests '*ConnectionPoolTest' --console=plain` ran eleven tests, all `PASSED`. Look at `platformWorkersRefuseWhenEveryThreadIsBusyAndTheQueueIsFull`, `virtualWorkersRefuseOverTheInFlightBoundAndFreeThePermitAfterward` and `givesUpAfterTheLimitWhenEveryConnectionIsLent`.

**4. The event loop.** `./gradlew -q eventLoop --args='config/event-loop.properties'` printed a staircase of waits (47 to 437 ms) when the blocking work ran in the loop, and ten equal waits (48 ms) when it was moved to virtual threads.

**5. Overload without bounds.** `./gradlew -q benchmark --args='config/unbounded.properties build/bench'` took 33 seconds and showed no refusals but 69.2 percent errors at 600 holds per second (`java-bench-walkthrough.md`).

## Mistakes people make

- **An unbounded queue.** `Executors.newFixedThreadPool` uses one. Give the queue a capacity and decide what happens when it is full.
- **A wait with no limit.** `take()`, `acquire()` and `get()` with no timeout wait forever. Use the form with a timeout, or `tryAcquire`.
- **Swallowing `InterruptedException`.** Catching it and carrying on erases the stop request. Restore the flag or rethrow.
- **A lost increment.** `count++` from two threads loses updates. Use `AtomicInteger` or `LongAdder`.
- **Thinking `volatile` makes `count++` safe.** It does not. It only makes the value visible.
- **Leaking a permit or a thread pool.** Release in `finally`; close executors with try-with-resources.
- **Blocking the event loop.** One blocking call in the loop stalls every connection (`java-eventloop-walkthrough.md`).
- **Pooling virtual threads.** They are cheap; the bound is a semaphore, not a pool.
- **Believing "virtual threads make everything faster".** They make waiting cheap. If the database connections are the limit, as in `config/production.properties`, both models hit the same wall (`594 holds/s` for both in the repository `README.md`).

## Check yourself

1. **A platform pool has 64 threads and a queue of 120. A 185th hold arrives while all are busy. What happens?** The pool's rule reaches step 4 and the abort policy throws `RejectedExecutionException`. `tryExecute` returns `false`, and `submit` answers `QUEUE_FULL` (503).
2. **Why is the permit released in `finally`?** So the permit comes back on every path, including a task that throws. A lost permit would shrink the node's capacity for ever.
3. **What happens to the interrupt flag when `Thread.sleep` throws `InterruptedException`?** It is cleared. The catching code must set it again with `Thread.currentThread().interrupt()` if it does not rethrow.
4. **Why is the benchmark's sampler a platform thread and not a virtual one?** A design choice, not a rule: a platform thread has its own operating-system thread, so its readings are not delayed while many virtual threads compete for the carriers.
5. **When is a virtual thread pinned on JDK 25?** Not when it blocks in `synchronized` (since JDK 24). Still when it blocks in a class initializer, as `CarrierPinning` shows: 501.6 ms against 0.8 ms.

## Further reading

Official documentation only, for JDK 25:

- The `java.util.concurrent` package: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html
- `ThreadPoolExecutor`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
- `Thread`, including interruption and the builders: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html
- Virtual threads, JEP 444: https://openjdk.org/jeps/444
- Synchronizing virtual threads without pinning, JEP 491: https://openjdk.org/jeps/491
