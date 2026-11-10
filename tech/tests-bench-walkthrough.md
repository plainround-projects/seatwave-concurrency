# Tests of the `bench` package, walked through

## What it is

The **benchmark** is a program that measures how one SeatWave node behaves as the load on it grows. It starts a node, sends it holds at a fixed rate for a few seconds (a **step**), records how long each hold took, then raises the rate and repeats. It does this once for the platform-thread mode and once for the virtual-thread mode, and it writes the results as a table, a text chart, a thread census, a CSV file and two SVG charts.

Words that the benchmark and its tests use:

- A **latency** is the time one hold takes from the moment it was due to the moment the answer arrives, here measured in milliseconds (ms, thousandths of a second).
- A **percentile** is a position in a sorted list of latencies. The **p50** (the median) is the middle one: half of the holds were faster. The **p99** is the value that 99 percent of the holds were not slower than. It shows the slow tail that an average hides. With 100 samples sorted from fastest to slowest, the p99 is the 99th.
- An **open model** of load sends requests on a schedule that does not depend on the answers. Real fans do not wait politely for the previous fan's answer, so a closed model (send, wait, send again) would make a slow node look better than it is: slower answers would slow the sending. This benchmark is open, with **Poisson** arrivals, which means the moments of arrival are random but their number per step is fixed.
- A **thread census** is a count of threads grouped by what they are doing: how many are `TIMED_WAITING` (waiting with a time limit) and in which method. It shows where the threads of an overloaded node are stuck.
- A **CSV** file is plain text with one row per record and the values separated by commas. **SVG** is a text format for drawings (`csv-and-svg-output.md`).
- **Warm-up** is a short run whose results are thrown away. A Java program runs slowly at first, until the JIT (just-in-time) compiler has turned the busy code into fast machine code.

This file explains the five test files of the `bench` package. `tests-node-walkthrough.md` explains the helper `TestNodes` and the tools for testing threads (latches, fake clocks, `await()`), which are reused here.

## Why this repository uses it

The numbers that the benchmark prints, a p99 of 453.4 ms for 380 platform-thread holds per second in the README, are only worth anything if the program that makes them is correct. The tests check the **machinery** with exact numbers, and they check the **real load generator** only for facts that are true on every machine. A timing test must never say "p99 is under 50 ms", because the answer depends on the computer. So the tests assert counts, bucket sums and the structure of the output, and let the benchmark itself, run by hand, produce the speeds.

The `bench` package has 568 lines, and 513 of them run in the tests (`jacoco.md`); 33 of the 55 missed lines are `BenchmarkCli`, the `main` method that prints and exits.

## Where it appears

| Path | What it does |
| --- | --- |
| `src/test/java/io/plainround/seatwave/concurrency/bench/StepResults.java` | A helper, not a test: hand-made step results with a chosen p99 and refusal count. |
| `.../bench/ThreadCensusTest.java` | 1 test of the thread census. |
| `.../bench/BenchmarkRunTest.java` | 1 test that runs the whole benchmark in miniature and checks the files it writes. |
| `.../bench/OpenModelLoadTest.java` | 4 tests of the load generator, with real short runs. |
| `.../bench/ReportsTest.java` | 8 tests of the percentile arithmetic, the value records, the CSV, the SVG and the text report. |

## How it works underneath

1. **A real benchmark run takes minutes, and a test must take milliseconds.** The tests use tiny settings (steps of 100 or 250 milliseconds, rates of 50 to 2,000 holds per second, 4 workers) and check structure, not speed.
2. **Exact numbers need exact inputs.** `ReportsTest` does not measure anything. It builds `StepResult` objects by hand with `StepResults.step(...)` and checks that the CSV line, the table and the SVG show exactly those numbers.
3. **The count of arrivals is exact by design.** For a rate of 200 holds per second and a step of 250 ms, the load generator draws exactly 200 x 0.25 = 50 random arrival moments from a **seeded** random generator (`new Random(seed)`, a generator that gives the same sequence for the same seed). So the test can say `sent == 50` on every machine. What varies is only how fast the node answers.
4. **Every hold ends in exactly one bucket.** A `StepResult` has counters for each way a hold can end (created, seat taken, refused at the door, refused for a pool timeout, other failure, client timeout). The constructor checks that their sum equals the number sent. The tests use this identity as a check that nothing was lost or counted twice.
5. **Timing facts are checked only as bounds.** Where a test touches time, it uses a limit that is true on any machine, for example "the client timeout did not fire" (5 seconds against steps of 100 milliseconds).

## Walkthrough of this repository's use

Words that first appear here.

- A **record** is a short way to declare a class that only holds values (see `tests-node-walkthrough.md`). `StepResult` is a record with 15 fields.
- **`Optional<Integer>`** is a box that holds a whole number or nothing. `contains(200)` checks that it holds 200.
- A **`Path`** is Java's object for a file or folder location. `Files.readString(path, ...)` reads a whole text file.
- **`@TempDir`** gives a test a new empty folder that JUnit deletes afterwards (`junit.md`).
- **`DocumentBuilderFactory`** and **`Document`** are the standard Java classes for reading XML. SVG is XML, so a test can parse it and count its elements.

### `StepResults.java`: the helper

```java
package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.util.List;

/** Hand-made step results for the report tests. */
final class StepResults {

  private static final long SENT = 1000;

  private StepResults() {}
```

`StepResults` is a helper with one static method. `SENT = 1000` is the number of holds each hand-made step claims to have sent. The class is `final` with a private constructor, so it only holds static methods, like `TestNodes`. It is package-private, since only the tests of this package use it.

```java
  /**
   * Returns a step where every hold got a 201, with the given latency.
   *
   * @param mode the thread model
   * @param offeredRps the offered rate
   * @param p99Millis the p99
   * @param refusedQueue how many of the 1,000 holds were refused at the door instead
   * @return the step
   */
  static StepResult step(ExecutorMode mode, int offeredRps, double p99Millis, long refusedQueue) {
    return new StepResult(
        mode,
        offeredRps,
        SENT,
        SENT - refusedQueue,
        0,
        refusedQueue,
        0,
        0,
        0,
        offeredRps,
        new LatencySummary((int) (SENT - refusedQueue), p99Millis / 2, p99Millis),
        new LatencySummary(0, 0, 0),
        new Saturation(1, 0, 0, 1, 0),
        1,
        List.of());
  }
}
```

`step(mode, offeredRps, p99Millis, refusedQueue)` returns a `StepResult` in which `refusedQueue` of the 1,000 holds were refused at the door and the rest got a `201`. The fifteen values of the `StepResult` constructor, in order, are:

1. `mode`, the thread model.
2. `offeredRps`, the rate offered.
3. `SENT`, how many were sent (1,000).
4. `SENT - refusedQueue`, how many were created.
5. `0`, seat taken.
6. `refusedQueue`, refused at the door.
7. `0`, refused for a pool timeout.
8. `0`, other failures.
9. `0`, client timeouts.
10. `offeredRps`, the achieved rate (the same as the offered rate, as if nothing slowed it).
11. `new LatencySummary((int) (SENT - refusedQueue), p99Millis / 2, p99Millis)`, the latency summary of the good holds: their count, a p50 of half the p99, and the p99 the caller chose. `(int)` is a cast that turns the `long` into an `int`.
12. `new LatencySummary(0, 0, 0)`, the pool-wait summary, empty.
13. `new Saturation(1, 0, 0, 1, 0)`, the saturation counters: one thread alive at most, nothing queued, no pool waiters, one platform thread, and the pool 0 percent busy.
14. `1`, the CPU percent.
15. `List.of()`, an empty thread census.

The numbers add up on purpose: `SENT - refusedQueue` created plus `refusedQueue` refused equals `SENT`, which the `StepResult` constructor requires.

### `ThreadCensusTest.java`

The thread census (`ThreadCensus.take(groups)`) looks at every live thread whose name starts with one of the given prefixes, and counts them grouped by prefix, state and the first SeatWave method on the thread's stack, the place where it waits. It returns a list of `CensusLine` records.

```java
package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.concurrency.node.TestNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThreadCensusTest {

  private static final String GROUP = "census-test-";
  private static final int THREADS = 3;
```

`GROUP = "census-test-"` is the name prefix the test gives its threads, and `THREADS = 3` is how many it starts. `ArrayList` and `List` hold the threads. The import of `TestNodes` is from the `node` package: the test uses its `LONG_WAIT`.

```java
  @Test
  void countsWaitingThreadsOfOneGroupByStateAndPlace() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int threadIndex = 0; threadIndex < THREADS; threadIndex++) {
      threads.add(Thread.ofPlatform().name(GROUP, threadIndex).start(() -> waitFor(release)));
    }
    await()
        .atMost(TestNodes.LONG_WAIT)
        .until(() ->
            threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING));

    List<CensusLine> census = ThreadCensus.take(List.of(GROUP));

    release.countDown();
    for (Thread thread : threads) {
      thread.join(TestNodes.LONG_WAIT);
    }
    assertThat(census).hasSize(1);
    assertThat(census.getFirst().threads()).isEqualTo(THREADS);
    assertThat(census.getFirst().state()).isEqualTo("TIMED_WAITING");
    assertThat(census.getFirst().waitingIn()).startsWith("ThreadCensusTest.");
  }
```

**`countsWaitingThreadsOfOneGroupByStateAndPlace`** proves that the census counts correctly.

1. A latch `release` is the gate that keeps the three threads waiting.
2. The loop starts three **platform threads** (ordinary operating-system threads, not virtual ones): `Thread.ofPlatform().name(GROUP, threadIndex).start(() -> waitFor(release))`. `name(GROUP, threadIndex)` names the thread `census-test-0`, `census-test-1` or `census-test-2`. `start(...)` starts the thread, which runs the lambda `waitFor(release)`.
3. The `await()` waits until all three threads are in the state `TIMED_WAITING`. `threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING)` is true when every thread in the list is in that state. A thread that waits on a latch with a time limit is `TIMED_WAITING`. Before this point a thread that has not yet reached the wait would be `RUNNABLE`, and the census would count it wrongly. Without this wait the test would be a race.
4. `ThreadCensus.take(List.of(GROUP))` takes the census of the group.
5. The test opens the gate (`release.countDown()`) and waits for each thread to end (`thread.join(TestNodes.LONG_WAIT)`, a join with a time limit). It cleans up **before** it asserts, so that a failing assertion cannot leave three threads behind.
6. The checks: one census line (`hasSize(1)`: all three threads are in the same group, state and place), it counts 3 threads, its state is `TIMED_WAITING`, and the place starts with `ThreadCensusTest.` (the first frame of the stack that belongs to this project is `ThreadCensusTest.waitFor`, so `waitingIn` is that text).

```java
  private static void waitFor(CountDownLatch release) {
    try {
      if (!release.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the test never released the thread");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
```

`waitFor` is the job of each thread. It waits on the latch for at most five seconds (`release.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)` returns `false` if the time ran out). If it ran out, the thread throws `IllegalStateException("the test never released the thread")`, so a forgotten `countDown()` ends the threads instead of leaving them for ever. If the thread is interrupted, it restores the interrupt flag and stops.

### `BenchmarkRunTest.java`: the whole benchmark in miniature

```java
package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkRunTest {

  private static final int STEPS_PER_MODE = 2;
  private static final int FILES = 3;
```

`STEPS_PER_MODE = 2` and `FILES = 3` are the numbers the test expects: two steps per mode, and three output files (a CSV and two SVG charts). `StandardCharsets` and `Files` read the written CSV, `TempDir` is the folder annotation, and `ExecutorMode` names the two modes.

```java
  @Test
  void runsEveryModeOnItsOwnNodeAndWritesTheCsvAndCharts(@TempDir Path folder) throws Exception {
    BenchmarkSettings settings =
        BenchmarkSettings.from(new SettingsReader(properties())).discardError().orElseThrow();

    List<StepResult> all = BenchmarkRun.runEveryMode(settings);
    String summary = BenchmarkRun.summary(settings, all);
    List<Path> written = BenchmarkRun.writeFiles(folder, "tiny", all, settings);

    assertThat(all)
        .extracting(StepResult::mode)
        .containsExactly(
            ExecutorMode.PLATFORM,
            ExecutorMode.PLATFORM,
            ExecutorMode.VIRTUAL,
            ExecutorMode.VIRTUAL);
    assertThat(all).hasSize(STEPS_PER_MODE * 2);
    assertThat(BenchmarkRun.header(settings)).contains("Node: 4 platform workers", "seed 20261110");
    assertThat(summary)
        .contains(
            "Thread census at 100 holds/s, platform mode",
            "p99 against offered load, virtual threads",
            "platform: last step inside the target");
    assertThat(written).hasSize(FILES).allSatisfy(path -> assertThat(path).isRegularFile());
    assertThat(Files.readString(written.getFirst(), StandardCharsets.UTF_8))
        .startsWith(BenchmarkCsv.HEADER);
  }
```

**`runsEveryModeOnItsOwnNodeAndWritesTheCsvAndCharts`** is the one test that runs the benchmark pipeline from start to end, with settings so small that it finishes in about a second.

- `@TempDir Path folder` is an empty folder for the output files, deleted afterwards.
- `BenchmarkSettings.from(new SettingsReader(properties())).discardError().orElseThrow()` reads the settings from the `properties()` helper below and turns the result into the settings object (or fails the test if the settings were invalid).
- `BenchmarkRun.runEveryMode(settings)` is the real thing: for each mode it starts a node with its own database, runs the warm-up and every step, and closes the node. It returns one `StepResult` per step.
- `BenchmarkRun.summary(settings, all)` builds the text report, and `BenchmarkRun.writeFiles(folder, "tiny", all, settings)` writes the three files and returns their paths.

The checks use no timing:

- `extracting(StepResult::mode)` turns the four results into their modes and `containsExactly` requires the order `PLATFORM, PLATFORM, VIRTUAL, VIRTUAL`: two steps per mode, modes in the order of the settings. `hasSize(STEPS_PER_MODE * 2)` checks the count again.
- `BenchmarkRun.header(settings)` contains `Node: 4 platform workers` and `seed 20261110`: the header states the settings that produced the numbers, so a reader can repeat the run.
- The summary contains the heading of the census (`Thread census at 100 holds/s, platform mode`), the chart heading (`p99 against offered load, virtual threads`) and the verdict line (`platform: last step inside the target`). These strings prove that the three parts of the report were produced.
- Three files were written, and each `isRegularFile()`; the first one, the CSV, `startsWith(BenchmarkCsv.HEADER)`.

The test does not assert a latency, a throughput or an error rate. Those depend on the machine. What is the same everywhere is that every part of the pipeline ran and wrote something of the right shape.

```java
  private static Properties properties() {
    Properties properties = new Properties();
    properties.setProperty("executor.modes", "platform,virtual");
    properties.setProperty("load.steps.rps", "50,100");
    properties.setProperty("load.warmup.millis", "50");
    properties.setProperty("load.step.millis", "100");
    properties.setProperty("load.random.seed", "20261110");
    properties.setProperty("client.timeout.millis", "5000");
    properties.setProperty("sampler.interval.millis", "5");
    properties.setProperty("census.at.rps", "100");
    properties.setProperty("target.p99.millis", "300");
    properties.setProperty("target.max.error.percent", "0.05");
    properties.setProperty("platform.workers", "4");
    properties.setProperty("platform.queue.bound", "10");
    properties.setProperty("virtual.max.in.flight", "100");
    properties.setProperty("queue.wait.limit.millis", "300");
    properties.setProperty("pool.connections", "4");
    properties.setProperty("pool.borrow.limit.millis", "500");
    properties.setProperty("database.wait.millis", "1");
    properties.setProperty("database.statement.timeout.seconds", "2");
    properties.setProperty("database.url", "jdbc:h2:mem:run-test-");
    return properties;
  }
}
```

`properties()` builds the settings of a tiny benchmark. Every key is a line of the real settings files (`config/simple.properties`), with smaller values:

- `executor.modes=platform,virtual` and `load.steps.rps=50,100`: two modes and two steps, at 50 and 100 holds per second.
- `load.warmup.millis=50` and `load.step.millis=100`: a 50 ms warm-up and steps of 100 ms (so about 5 and 10 holds per step).
- `load.random.seed=20261110`: the seed of the arrival times.
- `client.timeout.millis=5000`: a client gives up after five seconds, which never happens here.
- `sampler.interval.millis=5`: every 5 ms a sampler records the node's counters (threads alive, queue length, pool use).
- `census.at.rps=100`: the census is taken during the 100-per-second step. It must be one of the steps.
- `target.p99.millis=300` and `target.max.error.percent=0.05`: what counts as a healthy step.
- `platform.workers=4`, `platform.queue.bound=10`, `virtual.max.in.flight=100`, `queue.wait.limit.millis=300`, `pool.connections=4`, `pool.borrow.limit.millis=500`, `database.wait.millis=1`, `database.statement.timeout.seconds=2`: the node. The simulated database wait is 1 ms, so each hold is quick.
- `database.url=jdbc:h2:mem:run-test-`: a **prefix**. The benchmark adds the mode name, so each mode gets its own empty database: the platform run does not leave seats held for the virtual run.

### `OpenModelLoadTest.java`: four tests of the load generator

`OpenModelLoad` is the load generator: it plans the arrival moments, sends each hold at its moment, waits for the answers and counts them by outcome. These four tests run it for real, for 100 to 250 milliseconds. The class comment explains the rule: "Timing varies by machine, so the tests check what is true everywhere: the number of holds, that each lands in one bucket, and where the refusals come from."

```java
package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.NodeSettings;
import io.plainround.seatwave.concurrency.node.SeatWaveNode;
import io.plainround.seatwave.concurrency.node.TestNodes;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Short real runs of the load generator. Timing varies by machine, so the tests check what is true
 * everywhere: the number of holds, that each lands in one bucket, and where the refusals come from.
 */
class OpenModelLoadTest {

  private static final int RATE = 200;
  private static final Duration SHORT_STEP = Duration.ofMillis(250);
  private static final int EXPECTED_HOLDS = 50;
  private static final int OVERLOAD_RATE = 2000;
  private static final int OVERLOAD_HOLDS = 200;
  private static final Duration OVERLOAD_STEP = Duration.ofMillis(100);
```

- `RATE = 200` and `SHORT_STEP = 250 ms` give `EXPECTED_HOLDS = 50` (200 holds per second times a quarter of a second).
- `OVERLOAD_RATE = 2000` and `OVERLOAD_STEP = 100 ms` give `OVERLOAD_HOLDS = 200` (2,000 per second times a tenth of a second).
- `Clock` is used with `Clock.systemUTC()`, the real clock: these tests run a real node, and no rule here depends on a fake time.

```java
  @Test
  void sendsExactlyRateTimesDurationHoldsAndAccountsForEach() throws Exception {
    BenchmarkSettings settings = settings("200", "200");
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.roomy(ExecutorMode.VIRTUAL), Clock.systemUTC())) {

      StepResult step = new OpenModelLoad(settings).runStep(node, RATE, SHORT_STEP);

      assertThat(step.sent()).isEqualTo(EXPECTED_HOLDS);
      assertThat(step.created() + step.seatTaken()).isEqualTo(EXPECTED_HOLDS);
      assertThat(step.errorPercent()).isZero();
      assertThat(step.latency().count()).isEqualTo(EXPECTED_HOLDS);
      assertThat(step.census()).isNotEmpty();
    }
  }
```

**`sendsExactlyRateTimesDurationHoldsAndAccountsForEach`** proves the accounting of a healthy run.

- The node is `roomy` and virtual: nothing can be refused.
- `new OpenModelLoad(settings).runStep(node, RATE, SHORT_STEP)` runs one step of 250 ms at 200 holds per second.
- `step.sent()` is exactly 50. The count of arrivals is the rate times the duration, by construction, so this is true on every machine.
- `step.created() + step.seatTaken()` is also 50: every hold ended well, either with a seat or with "seat taken" (the generator picks seats at random out of 20,000, so two holds may pick the same seat).
- `step.errorPercent()` is zero: no hold failed.
- `step.latency().count()` is 50: all 50 good replies were measured.
- `step.census()` is not empty: the thread census was taken at the middle of the step, and a virtual node always has some carrier threads (the platform threads that run virtual threads).

```java
  @Test
  void refusesAtTheDoorWhenOneWorkerMeetsABurst() throws Exception {
    BenchmarkSettings settings = settings("2000", "2000");
    NodeSettings oneWorkerSlowDatabase =
        withDatabaseWait(TestNodes.tiny(ExecutorMode.PLATFORM), Duration.ofMillis(20));
    try (SeatWaveNode node = SeatWaveNode.start(oneWorkerSlowDatabase, Clock.systemUTC())) {

      StepResult step = new OpenModelLoad(settings).runStep(node, OVERLOAD_RATE, OVERLOAD_STEP);

      assertThat(step.sent()).isEqualTo(OVERLOAD_HOLDS);
      assertThat(step.refusedQueue()).isPositive();
      assertThat(step.clientTimeouts()).isZero();
      assertThat(step.census())
          .anySatisfy(line -> assertThat(line.group()).isEqualTo("seatwave-worker-"));
    }
  }
```

**`refusesAtTheDoorWhenOneWorkerMeetsABurst`** proves that the refusals come from the right place. It builds an **overloaded** node:

- `settings("2000", "2000")` sets the step rate to 2,000 holds per second and the census rate to the same value.
- `withDatabaseWait(TestNodes.tiny(ExecutorMode.PLATFORM), Duration.ofMillis(20))` is a `tiny` platform node (1 worker, a queue of 1, 1 connection) in which every hold takes 20 ms in the simulated database.
- The step lasts 100 ms: 200 holds arrive in a tenth of a second.

One worker that needs 20 ms per hold can finish about five holds in that time, and one more can wait in the queue. So most of the 200 holds must be refused at the door, and the checks say so: `sent()` is 200, `refusedQueue()` is positive (`isPositive()`), and `clientTimeouts()` is zero (every client got an answer well within five seconds, so no hold was lost, only refused). The census must contain a line for the group `seatwave-worker-`: the worker threads of the platform pool (`anySatisfy` requires at least one line to match). The test cannot say how many holds were refused, since the number depends on how fast the machine is, but it can say that it was positive, because 200 holds in 100 ms against 5 holds' worth of capacity is overload on any machine.

```java
  @Test
  void runsAWarmUpAndThenOneResultPerStep() throws Exception {
    BenchmarkSettings settings = settings("100,200", "100");
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.roomy(ExecutorMode.PLATFORM), Clock.systemUTC())) {

      List<StepResult> results = new OpenModelLoad(settings).runAll(node);

      assertThat(results).extracting(StepResult::offeredRps).containsExactly(100, 200);
      assertThat(results)
          .allSatisfy(step -> assertThat(step.mode()).isEqualTo(ExecutorMode.PLATFORM));
    }
  }
```

**`runsAWarmUpAndThenOneResultPerStep`** proves that `runAll` returns one result per step and not one for the warm-up. The steps are 100 and 200 holds per second; the warm-up result is thrown away. `extracting(StepResult::offeredRps)` must be exactly `100, 200`, and every result has the mode `PLATFORM`.

```java
  @Test
  void refusesSettingsWhoseCensusRateIsNotAStep() {
    Properties properties = properties("200,320", "999");

    Result<BenchmarkSettings, List<String>> read =
        BenchmarkSettings.from(new SettingsReader(properties));

    assertThat(((Result.Error<BenchmarkSettings, List<String>>) read).error())
        .containsExactly("census.at.rps must be one of the steps: 999");
  }

  private static BenchmarkSettings settings(String rates, String censusRate) {
    return BenchmarkSettings.from(new SettingsReader(properties(rates, censusRate)))
        .discardError()
        .orElseThrow();
  }
```

**`refusesSettingsWhoseCensusRateIsNotAStep`** proves a rule of the settings: the census is taken during a step, so `census.at.rps` must be one of the step rates. With steps `200,320` and a census at `999`, the result is an error with exactly one message: `census.at.rps must be one of the steps: 999`. The cast `(Result.Error<BenchmarkSettings, List<String>>) read` opens the error case (see `assertj.md`).

`settings(rates, censusRate)` is a helper that builds valid settings from a pair of strings.

```java
  private static Properties properties(String rates, String censusRate) {
    Properties properties = new Properties();
    properties.setProperty("executor.modes", "virtual,platform");
    properties.setProperty("load.steps.rps", rates);
    properties.setProperty("load.warmup.millis", "50");
    properties.setProperty("load.step.millis", "100");
    properties.setProperty("load.random.seed", "20261110");
    properties.setProperty("client.timeout.millis", "5000");
    properties.setProperty("sampler.interval.millis", "5");
    properties.setProperty("census.at.rps", censusRate);
    properties.setProperty("target.p99.millis", "300");
    properties.setProperty("target.max.error.percent", "0.05");
    properties.setProperty("platform.workers", "4");
    properties.setProperty("platform.queue.bound", "10");
    properties.setProperty("virtual.max.in.flight", "100");
    properties.setProperty("queue.wait.limit.millis", "300");
    properties.setProperty("pool.connections", "4");
    properties.setProperty("pool.borrow.limit.millis", "500");
    properties.setProperty("database.wait.millis", "0");
    properties.setProperty("database.statement.timeout.seconds", "2");
    properties.setProperty("database.url", "jdbc:h2:mem:bench-test-");
    return properties;
  }

  private static NodeSettings withDatabaseWait(NodeSettings settings, Duration databaseWait) {
    return new NodeSettings(
        settings.mode(),
        settings.platformWorkers(),
        settings.executorQueueBound(),
        settings.virtualMaxInFlight(),
        settings.queueWaitLimit(),
        settings.poolConnections(),
        settings.poolBorrowLimit(),
        databaseWait,
        settings.statementTimeoutSeconds(),
        settings.databaseUrl());
  }
```

- `properties(rates, censusRate)` is the same tiny settings as in `BenchmarkRunTest`, with two values given by the caller. Its notable differences: the modes are listed as `virtual,platform`, the simulated database wait is `0`, and the database URL prefix is `jdbc:h2:mem:bench-test-`.
- `withDatabaseWait(settings, databaseWait)` builds a copy of the node settings with one value changed (the eighth, the simulated database wait). Records cannot be changed, so the copy repeats every field through its accessor.

### `ReportsTest.java`: eight tests of the arithmetic and the output

These tests use no thread and no clock. They check the calculation of the percentiles, the value records, the CSV, the SVG chart and the text report with exact, hand-made numbers.

```java
package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** The CSV, the SVG chart, the text report and the value records they read. */
class ReportsTest {

  private static final Duration TARGET = Duration.ofMillis(300);
  private static final double BUDGET_PERCENT = 0.05;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final int HUNDRED = 100;

  private final List<StepResult> results =
      List.of(
          StepResults.step(ExecutorMode.PLATFORM, 200, 49.2, 0),
          StepResults.step(ExecutorMode.PLATFORM, 400, 586.9, 20),
          StepResults.step(ExecutorMode.VIRTUAL, 200, 45.9, 0),
          StepResults.step(ExecutorMode.VIRTUAL, 400, 87.0, 0));
```

- The imports bring in the standard XML classes (`DocumentBuilderFactory`, `Document`, `InputSource`, `XMLConstants`), `StringReader` to read text as if it were a file, and `Duration` and `List`.
- The class comment lists what is tested: "The CSV, the SVG chart, the text report and the value records they read."
- `TARGET` is 300 ms, the p99 a healthy step must stay under. `BUDGET_PERCENT = 0.05` is the share of failed holds a healthy step may have. `NANOS_PER_MILLI` is 1,000,000. `HUNDRED = 100`.
- `results` is a field: JUnit creates a new `ReportsTest` object for each test (`junit.md`), so every test gets its own copy of the list. It has four hand-made steps with `StepResults.step(mode, rate, p99, refusedQueue)`:

| Mode | Offered per second | p99 in ms | Refused at the door |
| --- | --- | --- | --- |
| platform | 200 | 49.2 | 0 |
| platform | 400 | 586.9 | 20 |
| virtual | 200 | 45.9 | 0 |
| virtual | 400 | 87.0 | 0 |

The numbers are from the shape of the real benchmark: platform threads collapse at 400, and virtual threads hold on.

```java
  @Test
  void readsP50AndP99ByNearestRank() {
    long[] oneToHundredMillis = new long[HUNDRED];
    for (int index = 0; index < HUNDRED; index++) {
      oneToHundredMillis[index] = (HUNDRED - index) * NANOS_PER_MILLI;
    }

    LatencySummary summary = LatencySummary.of(oneToHundredMillis);

    assertThat(summary).isEqualTo(new LatencySummary(HUNDRED, 50, 99));
    assertThat(LatencySummary.of(new long[0]).hasSamples()).isFalse();
    assertThatThrownBy(() -> new LatencySummary(1, 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**`readsP50AndP99ByNearestRank`** proves the percentile arithmetic. The test builds an array of 100 latencies in **descending** order, from 100 ms down to 1 ms: `oneToHundredMillis[index] = (HUNDRED - index) * NANOS_PER_MILLI`. `LatencySummary.of(...)` must sort them itself. The **nearest-rank** rule says: the p-th percentile is the smallest value that at least p percent of the values do not exceed. For 100 values 1 ms to 100 ms the p50 is the 50th value, 50 ms, and the p99 is the 99th, 99 ms. So the expected summary is `new LatencySummary(HUNDRED, 50, 99)`: count 100, p50 50.0, p99 99.0. The rule is a convention, so the test pins it down: a different rule (interpolating between values) would give different numbers.

The other two checks cover the edges: an empty array gives a summary with no samples (`hasSamples()` is `false`), and a summary whose p99 is below its p50 (`new LatencySummary(1, 2, 1)`) is impossible and throws `IllegalArgumentException`.

```java
  @Test
  void refusesAStepWhoseOutcomesDoNotAddUp() {
    assertThatThrownBy(() ->
            new StepResult(
                ExecutorMode.VIRTUAL,
                1,
                2,
                1,
                0,
                0,
                0,
                0,
                0,
                1,
                new LatencySummary(0, 0, 0),
                new LatencySummary(0, 0, 0),
                new Saturation(0, 0, 0, 0, 0),
                0,
                List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Saturation(0, 0, 0, 0, HUNDRED + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**`refusesAStepWhoseOutcomesDoNotAddUp`** proves the accounting identity. The test builds a `StepResult` with `sent` equal to 2 but outcomes that add up to 1 (one created, all other counters 0). The constructor throws `IllegalArgumentException`, so a bug that loses a hold, or counts one twice, stops the benchmark instead of producing a wrong table. The second check, `new Saturation(0, 0, 0, 0, HUNDRED + 1)`, tries a pool utilization of 101 percent, which is impossible, and also throws. (The long argument list is the fifteen values of `StepResult` in the order listed above, with `new LatencySummary(0, 0, 0)` twice, a `Saturation` and an empty list.)

```java
  @Test
  void countsRefusedHoldsAsErrors() {
    assertThat(results.get(1).errorPercent()).isEqualTo(2.0);
    assertThat(results.getFirst().errorPercent()).isZero();
  }
```

**`countsRefusedHoldsAsErrors`** proves `errorPercent()`. Step 2 has 20 holds refused out of 1,000: `20 x 100 / 1,000` is exactly `2.0`. Step 1 has none, so its error percent is zero. A refused hold counts as an error because the fan got no seat.

```java
  @Test
  void writesOneCsvLinePerStepWithEveryColumn() {
    String csv = BenchmarkCsv.render(results);

    List<String> lines = csv.lines().toList();
    int columns = BenchmarkCsv.HEADER.split(",").length;
    assertThat(lines).hasSize(results.size() + 1);
    assertThat(lines.subList(1, lines.size()))
        .allSatisfy(line -> assertThat(line.split(",")).hasSize(columns));
    assertThat(lines.get(2)).startsWith("platform,400,400.0,1000,980,0,20,");
  }
```

**`writesOneCsvLinePerStepWithEveryColumn`** proves the CSV shape. `BenchmarkCsv.render(results)` returns text; `csv.lines().toList()` splits it into lines. The test counts the columns of the header (`BenchmarkCsv.HEADER.split(",").length`) and requires: one line per step plus the header (`results.size() + 1` lines), every data line has the same number of columns (`lines.subList(1, lines.size())` skips the header), and the third line (the second platform step) starts with `platform,400,400.0,1000,980,0,20,`: the mode, the offered rate, the achieved rate, the 1,000 sent, 980 created, 0 seat taken and 20 refused. This pins the column order, so a spreadsheet that reads the file always finds the same data in the same column.

```java
  @Test
  void drawsAValidSvgWithOneLinePerModeAndTheTarget() throws Exception {
    String svg = LoadChartSvg.render("p99 <&> load", results, ChartMetric.P99_MILLIS, 300);

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    assertThat(document.getElementsByTagName("polyline").getLength()).isEqualTo(2);
    assertThat(document.getElementsByTagName("circle").getLength()).isEqualTo(results.size());
    assertThat(svg).contains("p99 &lt;&amp;&gt; load", "target 300", "virtual threads");
  }
```

**`drawsAValidSvgWithOneLinePerModeAndTheTarget`** proves that the SVG chart is well-formed XML and has the right parts. `LoadChartSvg.render("p99 <&> load", results, ChartMetric.P99_MILLIS, 300)` draws a chart of the p99, with a title that has the characters `<`, `&` and `>` on purpose.

- `DocumentBuilderFactory.newInstance()` makes a reader for XML. `factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)` turns on its safe mode, which refuses dangerous XML features. A test that parses text should not open a security hole, even though the text is its own.
- `factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)))` parses the drawing. If the text were not well-formed XML (for example an unescaped `<` in the title), this line would throw, and the test would fail. That is the main check.
- `getElementsByTagName("polyline").getLength()` is 2: one polyline (a drawn line through points) per mode, platform and virtual.
- `getElementsByTagName("circle").getLength()` is `results.size()`, 4: one circle marker per step.
- The text contains `p99 &lt;&amp;&gt; load`: the title with its three special characters **escaped**, written as the XML entities `&lt;`, `&amp;` and `&gt;`. It also contains `target 300`, the label of the target line, and `virtual threads`, the legend.

```java
  @Test
  void findsTheLastStepInsideTheTarget() {
    List<StepResult> platform = results.subList(0, 2);
    List<StepResult> virtual = results.subList(2, 4);

    assertThat(BenchmarkReport.lastHealthyRate(platform, TARGET, BUDGET_PERCENT)).contains(200);
    assertThat(BenchmarkReport.lastHealthyRate(virtual, TARGET, BUDGET_PERCENT)).contains(400);
    assertThat(BenchmarkReport.lastHealthyRate(results.subList(1, 2), TARGET, BUDGET_PERCENT))
        .isEmpty();
  }
```

**`findsTheLastStepInsideTheTarget`** proves `lastHealthyRate`, the function that says up to which load a mode stays healthy. A step is healthy if it has samples, its p99 is at most the target (300 ms) and its error percent is at most the budget (0.05). The function walks through the steps in order and stops at the first unhealthy one.

- Platform: step 200 (p99 49.2, no errors) is healthy; step 400 (p99 586.9) is not. The answer is `200`, wrapped in an `Optional`, so `contains(200)`.
- Virtual: both steps are healthy (p99 45.9 and 87.0), so the answer is the last one, `400`.
- A list with only the second platform step (`results.subList(1, 2)`): even the first step is unhealthy, so the answer is an empty `Optional`.

```java
  @Test
  void printsATableAndABarPerStep() {
    String table = BenchmarkReport.table(results);
    String chart = BenchmarkReport.textChart(results);

    assertThat(table.lines()).hasSize(results.size() + 1);
    assertThat(chart).contains("platform threads", "virtual threads", "p99    586.9 ms");
    assertThat(chart.lines().filter(line -> line.contains("#".repeat(50)))).hasSize(1);
  }
```

**`printsATableAndABarPerStep`** proves the text report. `BenchmarkReport.table(results)` has one line per step plus a header, so `table.lines()` has `results.size() + 1` lines. `BenchmarkReport.textChart(results)` draws each step as a bar of `#` characters, scaled so that the slowest step has a bar of 50. The chart contains the headings `platform threads` and `virtual threads` and the text `p99    586.9 ms` (the number is padded with spaces to line up in the columns). Exactly one line has 50 `#` in a row (`"#".repeat(50)`): the slowest step, 586.9 ms. All other steps have shorter bars.

```java
  @Test
  void printsACensusLinePerGroupStateAndPlace() {
    String census =
        BenchmarkReport.census(
            List.of(
                new CensusLine("seatwave-worker-", "TIMED_WAITING", "ConnectionPool.borrow", 48)));

    assertThat(census).contains("48", "TIMED_WAITING", "in ConnectionPool.borrow");
    assertThatThrownBy(() -> new CensusLine("g", "s", "w", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

**`printsACensusLinePerGroupStateAndPlace`** proves the census text. A `CensusLine` for 48 threads of the group `seatwave-worker-` in the state `TIMED_WAITING` in `ConnectionPool.borrow` prints as a line that contains `48`, `TIMED_WAITING` and `in ConnectionPool.borrow`. Then `new CensusLine("g", "s", "w", 0)` must throw `IllegalArgumentException`: a census line counts at least one thread, since a line for zero threads would mean nothing.

## Try it yourself

1. Run the `bench` package tests:

```
./gradlew cleanTest test --tests '*bench*'
```

   Expected: 14 lines ending in `PASSED` (1 + 1 + 4 + 8, from `BenchmarkRunTest`, `ThreadCensusTest`, `OpenModelLoadTest` and `ReportsTest`) and `BUILD SUCCESSFUL`. The helper `StepResults` has no test.

2. Run the real benchmark once, to see the numbers the tests deliberately do not check (it takes about two minutes, and the numbers differ on every machine and every run):

```
./gradlew -q benchmark --args='config/simple.properties build/bench'
```

   Expected: a `Machine:` line, a `Node:` line, a `Load:` line, a table with a row per mode and step, a thread census for the 440-per-second step of each mode, two text charts, the two verdict lines and the closing line `Wrote build/bench/simple.csv, build/bench/simple-p99.svg, build/bench/simple-errors.svg`. In my run it took 2 minutes 9.5 seconds. The verdicts were `platform: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 360 holds/s` and `virtual: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 380 holds/s`. The p99 of the platform mode at 440 holds per second was 602.7 ms with 12.23 percent errors, and of the virtual mode 486.7 ms with 9.32 percent. The README shows an earlier run, and the two differ by a few percent. `ls build/bench` shows `simple-errors.svg`, `simple-p99.svg` and `simple.csv`.

3. Check that the SVG that the tests parse is valid XML, with a file the benchmark wrote:

```
python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('build/bench/simple-p99.svg'); print('valid XML')"
```

   Expected: `valid XML`.

4. Run one test by name:

```
./gradlew cleanTest test --tests '*ReportsTest.readsP50AndP99ByNearestRank'
```

   Expected: one `PASSED` line.

5. Check that the count of holds is exact: `sent` is rate times duration. Run:

```
./gradlew cleanTest test --tests '*OpenModelLoadTest.sendsExactlyRateTimesDurationHoldsAndAccountsForEach'
```

   Expected: one `PASSED` line, and the same on every run, because the arrival count does not depend on the machine.

## Mistakes people make

- **Asserting a speed.** "The p99 is under 50 ms" passes on one machine and fails on another. These tests assert counts, sums and shapes.
- **Closed-model load.** A test client that waits for each answer before it sends the next hides overload. The load generator sends on a schedule.
- **Averages.** An average of latencies hides the slow tail. The benchmark reports p50 and p99, and the tests pin down how they are computed.
- **Losing a request in the accounting.** The `StepResult` constructor checks that every hold lands in exactly one bucket.
- **Unescaped text in XML.** A `<` or `&` in a chart title breaks the file. The SVG test uses a title with both on purpose.
- **Sharing a database between modes.** The second mode would find the seats the first one held. The run gives each mode its own database name.
- **Leaving threads running.** `ThreadCensusTest` opens the gate and joins its threads before it asserts.

## Check yourself

1. *How is the p99 of 100 sorted samples chosen by the nearest-rank rule?* It is the 99th smallest value.
2. *Why can `OpenModelLoadTest` say that exactly 50 holds are sent?* The load generator draws exactly rate times duration arrival moments (200 holds per second times 0.25 s), so the count does not depend on the machine.
3. *Why does `refusesAtTheDoorWhenOneWorkerMeetsABurst` only say `isPositive()` and not an exact number?* How many holds are refused depends on how fast the machine is, but that some are refused is true on any machine, because 200 holds in 100 ms are far more than one worker that needs 20 ms per hold can take.
4. *What does the `StepResult` constructor check, and why does `refusesAStepWhoseOutcomesDoNotAddUp` need it?* That created, seat taken, refused, failed and timed-out holds add up to the number sent, so no hold is lost or counted twice.
5. *What proves in `drawsAValidSvgWithOneLinePerModeAndTheTarget` that the chart is valid XML?* The parser accepts the text without throwing, even though the title contains `<`, `&` and `>`.

## Further reading

- JUnit 6.1.3, temporary directories: <https://docs.junit.org/6.1.3/writing-tests/built-in-extensions.html>
- Java XML parsing, `DocumentBuilderFactory`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.xml/javax/xml/parsers/DocumentBuilderFactory.html>
- `java.util.Random`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Random.html>
- `Thread.State`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.State.html>
- SVG 2 specification: <https://www.w3.org/TR/SVG2/>
