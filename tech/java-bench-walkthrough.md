# Java walkthrough: the `bench` package

## What it is

This file reads, one source file at a time, the Java package `io.plainround.seatwave.concurrency.bench`. It is the **benchmark**: a program that sends the same load to a SeatWave node twice, once with platform threads and once with virtual threads, measures every reply, and writes a table, a text chart, a CSV file and two SVG charts. It has thirteen files. Only `BenchmarkCli` prints and exits. Every other class returns values.

A **benchmark** measures how fast something is. A **load generator** is the part that sends requests. A **step** is one stretch of the run at one fixed rate, such as 380 holds per second for 10 seconds. A **percentile** such as p99 is the value that 99 percent of the measurements do not exceed.

Read `java.md` and `java-concurrency.md` first, and `java-node-walkthrough.md` for the node that this package drives.

## Why this repository uses it

Module 4 claims that latency does not grow slowly with load: it stays flat, then jumps as a pool nears full use. The claim is easy to read and easy to forget. The benchmark makes it a measurement on your own machine: the p99 of a hold at 50, 80, 90, 95, 99 and 110 percent of the node's capacity, for each thread model. The same code also compares the two thread models, and shows by thread census what the threads are doing while the node is saturated.

## Where it appears

| File | What it does |
|---|---|
| `BenchmarkSettings.java` | Every setting of a run, read from the settings file with no defaults. |
| `OpenModelLoad.java` | The load generator: plans the arrivals, sends the holds, waits for every reply, samples the pools. |
| `StepResult.java` | What one step measured. |
| `LatencySummary.java` | The p50 and p99 of a set of durations, by the nearest-rank method. |
| `Saturation.java` | The saturation of each pool during a step. |
| `ThreadCensus.java` and `CensusLine.java` | A count of platform threads by group, state and place. |
| `ChartMetric.java` | Which value of a step a chart plots. |
| `BenchmarkCsv.java` | Renders the steps as CSV. |
| `LoadChartSvg.java` | Draws a line chart as SVG text with no library. |
| `BenchmarkReport.java` | Renders the printed table, the text chart, the census and the last healthy step. |
| `BenchmarkRun.java` | Runs every mode on a fresh node and builds the header, the summary and the files. |
| `BenchmarkCli.java` | The program started from the terminal. |

## How it works underneath

**An open model, not a closed one.** A **closed** load generator has a fixed number of clients. Each sends a request, waits for the reply, then sends the next. When the server slows down, the clients slow down with it, so the load falls exactly when the server struggles. Real fans do not behave like that: at an on-sale they keep arriving whether or not the node keeps up. An **open** model sends requests at planned moments, whatever the replies are doing. `OpenModelLoad` is open. It plans the arrival moments of a step first, then sends each at its moment.

**Arrivals.** For a step at `rate` holds per second for `duration` seconds, the generator makes exactly `rate x duration` arrival moments, each a random point in the step, sorted. Random points mean random bunching: sometimes three holds arrive within a millisecond, sometimes none for ten. The count is fixed, so every step offers exactly its rate, and two steps can be compared. The random numbers come from a fixed **seed**, so the same step sends the same holds at the same moments on every run. The seed is `load.random.seed` plus the rate.

**Coordinated omission.** Each latency is measured from the **planned** send time, not from the moment the call actually happened. If the machine is busy and the generator sends a hold 20 ms late, those 20 ms count against the node. A generator that measured from the actual send would hide the delay: the slow period would make fewer requests and the delay would not be recorded. That mistake has a name, **coordinated omission**.

**Warm-up.** The JVM compiles hot code while it runs (the **JIT compiler**, see `java.md`). The first seconds of a run are slower and the numbers would be too high. So `runAll` first runs one step at the first rate and throws its numbers away.

**One fresh node per mode.** `BenchmarkRun` starts a new node with its own in-memory database for each thread model, so the second mode does not find the first mode's holds.

**The sampler.** A separate platform thread reads the pool counters every `sampler.interval.millis` (10 ms) and keeps the largest values and the average pool use. The load thread never waits on it.

**What is checked after every step.** The node promises that every hold ends in bounded time. After each step, `awaitDrain` waits for every hold's future, at most the longest legal hold (queue wait limit + borrow limit + database wait + statement timeout + client timeout). If any hold is still running after that, the benchmark stops with an exception rather than print numbers from a node that broke its promise.

**The columns of the table** are explained below, with the code that prints them (`BenchmarkReport.table`).

## Walkthrough of this repository's use

The order is the data from the inside out: the settings, the small records that hold results, the thread census, the load generator, then the renderers and the run.

### `BenchmarkSettings.java`

Everything one benchmark run measures. Every value comes from the settings file. A record.

**Lines 1 to 9: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.NodeSettings;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
```

- `Result`: the result4j type that is either a success value or an error value (see `result4j.md`).
- `SettingsReader`: the class that reads typed values from a settings file.
- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `NodeSettings`: the record of every limit of one node.
- `Duration`: a length of time.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.

**Lines 10 to 37: the Javadoc and the header**

```java
/**
 * What one benchmark run measures. Every value comes from the settings file; there are no defaults.
 *
 * @param modes the thread models to run, in order; the same load runs once per mode
 * @param stepRates the offered holds per second of each load step, in order
 * @param warmup how long the first step's rate runs before measuring, per mode
 * @param stepDuration how long each measured step sends
 * @param randomSeed the seed of the arrival times and seat choices
 * @param clientTimeout how long a client waits for one reply before it gives up
 * @param sampleInterval how often the saturation counters are read
 * @param censusRate the step at which a thread census is taken
 * @param targetP99 the hold p99 target that marks a step as healthy (Module 2)
 * @param maxErrorPercent the largest error share a healthy step may have (Module 2)
 * @param node the node settings, with the first mode
 */
public record BenchmarkSettings(
    List<ExecutorMode> modes,
    List<Integer> stepRates,
    Duration warmup,
    Duration stepDuration,
    long randomSeed,
    Duration clientTimeout,
    Duration sampleInterval,
    int censusRate,
    Duration targetP99,
    double maxErrorPercent,
    NodeSettings node) {
```

- Eleven components. `modes` is the list of thread models to run. `stepRates` is the offered holds per second of each step. `warmup` and `stepDuration` are lengths of time. `randomSeed` is the seed. `clientTimeout` is how long a client waits for a reply before it gives up. `sampleInterval` is how often the counters are read. `censusRate` is the step at which a thread census is taken. `targetP99` and `maxErrorPercent` are the target that marks a step as healthy (Module 2: 300 ms and 0.05 percent). `node` is the `NodeSettings`, built with the first mode.
- `List<ExecutorMode>` is a list whose elements are `ExecutorMode` values. The angle brackets are **generics**: they say what a list holds.

**Lines 38 to 44: limits on the settings**

```java
  // A rate above 100,000 holds per second is not something one node in this module can be asked.
  private static final int MAX_RATE = 100_000;
  // Steps longer than a minute belong in a capacity test (Module 41), not in this teaching run.
  private static final int MAX_STEP_MILLIS = 60_000;
  private static final int MAX_SEED = Integer.MAX_VALUE;
  private static final int MAX_PERCENT = 100;
```

- Four constants cap what a settings file may say. A rate above 100,000 holds per second is not something one node in this module can be asked. A step longer than a minute belongs in a capacity test (Module 41), not in this teaching run. `MAX_SEED` is `Integer.MAX_VALUE`, 2,147,483,647, the largest `int`.

**Lines 45 to 68: the compact constructor**

```java
  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a list is empty, a duration is not positive, or the census
   *     rate is not one of the steps
   */
  public BenchmarkSettings {
    modes = List.copyOf(modes);
    stepRates = List.copyOf(stepRates);
    Objects.requireNonNull(node, "node");
    if (modes.isEmpty() || stepRates.isEmpty()) {
      throw new IllegalArgumentException("a benchmark needs at least one mode and one step");
    }
    for (Duration positive :
        new Duration[] {warmup, stepDuration, clientTimeout, sampleInterval, targetP99}) {
      if (positive.isNegative() || positive.isZero()) {
        throw new IllegalArgumentException("every duration must be positive: " + positive);
      }
    }
    if (!stepRates.contains(censusRate)) {
      throw new IllegalArgumentException("census.at.rps must be one of the steps: " + censusRate);
    }
  }
```

- `modes = List.copyOf(modes)` replaces the parameter with an unchangeable copy before it is stored. In a compact constructor, assigning to a parameter changes what the record stores. Now a caller cannot change the lists later. `List.copyOf` also throws on a null list.
- An empty `modes` or `stepRates` is refused: a benchmark needs at least one of each.
- The loop over `new Duration[] {...}` checks five durations. `positive.isNegative() || positive.isZero()` refuses zero and negative values, because a step of no length or a sampler with no interval is meaningless.
- `stepRates.contains(censusRate)` is true if the census rate is one of the steps. If not, the census would never be taken, so the constructor refuses the settings.

**Lines 69 to 89: reading the keys**

```java
  /**
   * Reads and checks every key of a benchmark settings file.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<BenchmarkSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    List<ExecutorMode> modes = reader.enumList("executor.modes", ExecutorMode.class);
    List<Integer> rates = reader.intListBetween("load.steps.rps", 1, MAX_RATE);
    Duration warmup = reader.millisBetween("load.warmup.millis", 1, MAX_STEP_MILLIS);
    Duration step = reader.millisBetween("load.step.millis", 1, MAX_STEP_MILLIS);
    long seed = reader.intBetween("load.random.seed", 1, MAX_SEED);
    Duration clientTimeout = reader.millisBetween("client.timeout.millis", 1, MAX_STEP_MILLIS);
    Duration sample = reader.millisBetween("sampler.interval.millis", 1, MAX_STEP_MILLIS);
    int census = reader.intBetween("census.at.rps", 1, MAX_RATE);
    Duration targetP99 = reader.millisBetween("target.p99.millis", 1, MAX_STEP_MILLIS);
    double maxErrors = reader.decimalBetween("target.max.error.percent", 0, MAX_PERCENT);
    ExecutorMode firstMode = modes.isEmpty() ? ExecutorMode.PLATFORM : modes.getFirst();
    Result<NodeSettings, List<String>> node = NodeSettings.from(reader, firstMode);
```

- `from` reads every key with the reader and gets typed values. `reader.enumList("executor.modes", ExecutorMode.class)` reads `platform,virtual` as a list of enum values. `intListBetween` reads the step list. `millisBetween` reads a duration in milliseconds. `intBetween("load.random.seed", 1, MAX_SEED)` reads the seed, and assigning an `int` to a `long` widens it without a cast. `decimalBetween` reads the error percentage, which may be fractional such as `0.05`.
- `modes.isEmpty() ? ExecutorMode.PLATFORM : modes.getFirst()` picks the first mode, or a stand-in if the list is empty (the problem was already recorded). The `? :` is a short `if`. `getFirst()` is Java 21.
- `NodeSettings.from(reader, firstMode)` reads the node's keys with the same reader, so one pass collects every problem of the whole file.

**Lines 90 to 116: joining the two results**

```java
    return switch (node) {
      case Result.Error<NodeSettings, List<String>>(List<String> problems) ->
          Result.error(problems);
      case Result.Success<NodeSettings, List<String>>(NodeSettings nodeSettings) -> {
        try {
          yield Result.success(
              new BenchmarkSettings(
                  modes,
                  rates,
                  warmup,
                  step,
                  seed,
                  clientTimeout,
                  sample,
                  census,
                  targetP99,
                  maxErrors,
                  nodeSettings));
        } catch (IllegalArgumentException inconsistent) {
          // Each key was valid alone, but together they break a rule, such as a census rate that is
          // not a step.
          yield Result.error(List.of(inconsistent.getMessage()));
        }
      }
    };
  }
}
```

- `return switch (node) {...}` is a **switch expression** over a `Result`. The two `case` lines use **record patterns**: `Result.Error<...>(List<String> problems)` matches an error and gives its content the name `problems`. `Result.Success<...>(NodeSettings nodeSettings)` matches a success. `Result` is sealed with exactly these two cases, so no `default` is needed. Record patterns and pattern-matching `switch` need Java 21.
- The error case returns `Result.error(problems)` unchanged: the node keys had problems, so the whole file does.
- In the success case the code builds the record inside `try`. `yield` gives the value of the block, as `return` would give a method's value (Java 14 or newer). The `catch (IllegalArgumentException inconsistent)` handles keys that are each valid but not together, such as a census rate that is not a step. The comment says so.

### `ChartMetric.java`

A value of a step that a chart can plot against the offered rate. An enum whose values carry a label and a function.

**Lines 1 to 3: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import java.util.function.ToDoubleFunction;
```

- `ToDoubleFunction`: a function from an object to a `double`.

**Lines 4 to 10: the two metrics**

```java
/** A value of a step that a chart can plot against the offered rate. */
public enum ChartMetric {
  /** The p99 of the good replies, in milliseconds. */
  P99_MILLIS("p99 latency of 201 and 409 replies (ms)", step -> step.latency().p99Millis()),
  /** The share of holds without a good reply in time, in percent. */
  ERROR_PERCENT("holds without a good reply in time (%)", StepResult::errorPercent);
```

- `P99_MILLIS("...", step -> step.latency().p99Millis())` has two arguments. The text is the label of the vertical axis. The second is a **lambda**: `step -> step.latency().p99Millis()` is a function that takes a `StepResult` named `step` and returns its p99 in milliseconds.
- `ERROR_PERCENT("...", StepResult::errorPercent)` passes a **method reference**: `StepResult::errorPercent` means "call `errorPercent()` on the step".
- An enum value that carries behavior replaces a `switch` over the metric: each value knows how to read itself.

**Lines 11 to 18: the fields and the constructor**

```java
  private final String axisLabel;
  private final ToDoubleFunction<StepResult> reading;

  ChartMetric(String axisLabel, ToDoubleFunction<StepResult> reading) {
    this.axisLabel = axisLabel;
    this.reading = reading;
  }
```

- `ToDoubleFunction<StepResult>` is a function type from a `StepResult` to a `double`. The enum's constructor stores the label and the function.

**Lines 19 to 27: the label**

```java
  /**
   * Returns the label of the vertical axis.
   *
   * @return the label, with its unit
   */
  public String axisLabel() {
    return axisLabel;
  }
```

- Returns the text for the vertical axis, with its unit.

**Lines 28 to 38: reading a step**

```java
  /**
   * Reads this metric from a step.
   *
   * @param step the step
   * @return the value
   */
  public double read(StepResult step) {
    return reading.applyAsDouble(step);
  }
}
```

- `reading.applyAsDouble(step)` calls the stored function on the step and returns the number.

### `LatencySummary.java`

The p50 and p99 of a set of durations, by the nearest-rank method. A record.

**Lines 1 to 4: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import java.util.Arrays;
import java.util.Objects;
```

- `Arrays`: static helpers for arrays: sort, copy.
- `Objects`: helpers such as `requireNonNull`.

**Lines 5 to 13: the header**

```java
/**
 * The p50 and p99 of a set of durations, read by the nearest-rank method.
 *
 * @param count how many durations were summarized; 0 means none, and both percentiles are then 0
 * @param p50Millis the duration half of them did not exceed
 * @param p99Millis the duration 99 in 100 of them did not exceed
 */
public record LatencySummary(int count, double p50Millis, double p99Millis) {
```

- `count` is how many durations were summarized. `p50Millis` is the duration that half of them did not exceed (the **median**: the typical hold). `p99Millis` is the one that 99 in 100 did not exceed (the slow end that one fan in a hundred sees). Millisecond values are `double` because they are fractions: 44.3 ms.

**Lines 14 to 18: constants**

```java
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final int P50 = 50;
  private static final int P99 = 99;
  private static final int HUNDRED = 100;
```

- `NANOS_PER_MILLI = 1_000_000.0` converts nanoseconds to milliseconds. `P50`, `P99` and `HUNDRED` are named so the formula below has no bare numbers.

**Lines 19 to 29: the compact constructor**

```java
  /**
   * Creates the summary.
   *
   * @throws IllegalArgumentException if a value is negative or p99 is below p50
   */
  public LatencySummary {
    if (count < 0 || p50Millis < 0 || p99Millis < p50Millis) {
      throw new IllegalArgumentException("impossible summary: " + count + ", " + p50Millis);
    }
  }
```

- Refuses a negative count or time, and a p99 below the p50. A p99 can never be below the p50, so such a pair would mean a bug in the calculation.

**Lines 30 to 48: summarizing**

```java
  /**
   * Summarizes durations.
   *
   * @param durationsNanos the durations in nanoseconds, in any order; not changed
   * @return the summary
   */
  public static LatencySummary of(long[] durationsNanos) {
    Objects.requireNonNull(durationsNanos, "durationsNanos");
    if (durationsNanos.length == 0) {
      return new LatencySummary(0, 0, 0);
    }
    long[] sorted = durationsNanos.clone();
    Arrays.sort(sorted);
    return new LatencySummary(
        sorted.length,
        nearestRank(sorted, P50) / NANOS_PER_MILLI,
        nearestRank(sorted, P99) / NANOS_PER_MILLI);
  }
```

- `of` takes the durations in nanoseconds in any order. For no durations it returns a summary of zeros: `count` 0 means "none".
- `durationsNanos.clone()` copies the array, and `Arrays.sort(sorted)` sorts the copy. The caller's array is not changed, as the Javadoc promises.
- The summary is built from the count and the two percentiles, each divided by `NANOS_PER_MILLI`.

**Lines 49 to 57: is there data**

```java
  /**
   * Tells whether any duration was summarized.
   *
   * @return true when the count is above 0
   */
  public boolean hasSamples() {
    return count > 0;
  }
```

- `hasSamples()` tells whether anything was summarized. A step where every hold failed has no good durations, and the report must not call a step of zeros "fast".

**Lines 58 to 64: nearest rank**

```java
  // Nearest rank: the smallest value that at least percent of the values do not exceed.
  private static long nearestRank(long[] sorted, int percent) {
    int rank = (int) Math.ceil(percent / (double) HUNDRED * sorted.length);
    return sorted[Math.max(rank, 1) - 1];
  }
}
```

- The comment states the rule: the nearest rank is the smallest value that at least `percent` percent of the values do not exceed.
- `Math.ceil(percent / (double) HUNDRED * sorted.length)` is the **rank**, counting from 1. For 99 and 4,000 values: 0.99 x 4000 = 3960, so the 3,960th smallest. `Math.ceil` rounds up to a whole number. `(double)` turns `HUNDRED` into a decimal first, so the division is not a whole-number division that rounds down.
- `sorted[Math.max(rank, 1) - 1]` turns the rank into an array index. Arrays count from 0, so the 3,960th value is at index 3,959. `Math.max(rank, 1)` keeps the rank at least 1 for tiny inputs.
- I checked the rule on five numbers, 5, 1, 9, 3, 7: sorted they are 1, 3, 5, 7, 9; p50 is rank ceiling(2.5) = 3, which is 5; p99 is rank ceiling(4.95) = 5, which is 9. The program printed `5 9`.

### `Saturation.java`

The saturation of each pool during one step, from the sampler's readings. A record.

**Lines 1 to 1: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;
```


**Lines 2 to 17: the header**

```java
/**
 * The saturation of each pool during one step, from the sampler's readings.
 *
 * @param maxThreadsAlive the most threads that existed for SeatWave work at one reading
 * @param maxQueued the longest the executor queue was at one reading
 * @param maxPoolWaiters the most threads waiting for a connection at one reading
 * @param maxPlatformThreads the most platform threads in the whole JVM at one reading
 * @param meanPoolBusyPercent the average share of connections lent out: the pool's utilization
 */
public record Saturation(
    int maxThreadsAlive,
    int maxQueued,
    int maxPoolWaiters,
    int maxPlatformThreads,
    double meanPoolBusyPercent) {
```

- `maxThreadsAlive`: the most threads that existed for SeatWave work at one reading. `maxQueued`: the longest the executor queue was at one reading. `maxPoolWaiters`: the most threads waiting for a connection. `maxPlatformThreads`: the most platform threads in the whole JVM. `meanPoolBusyPercent`: the average share of connections lent out, which is the pool's **utilization**.
- This file has only the `package` line before the declaration, because it uses no other class.

**Lines 18 to 19: a constant**

```java
  private static final double HUNDRED_PERCENT = 100.0;
```

- `HUNDRED_PERCENT = 100.0`, so the limit check has no bare number.

**Lines 20 to 34: the compact constructor**

```java
  /**
   * Creates the record.
   *
   * @throws IllegalArgumentException if a count is negative or the utilization is outside 0 to 100
   */
  public Saturation {
    if (maxThreadsAlive < 0 || maxQueued < 0 || maxPoolWaiters < 0 || maxPlatformThreads < 0) {
      throw new IllegalArgumentException("a count is never negative");
    }
    if (meanPoolBusyPercent < 0 || meanPoolBusyPercent > HUNDRED_PERCENT) {
      throw new IllegalArgumentException("utilization is 0 to 100 percent: " + meanPoolBusyPercent);
    }
  }
}
```

- Refuses a negative count, and a utilization outside 0 to 100. A pool cannot be more than 100 percent busy.

### `StepResult.java`

What one load step measured. A record with fifteen components, and one rule: every hold sent ends in exactly one bucket.

**Lines 1 to 5: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.util.List;
import java.util.Objects;
```

- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.

**Lines 6 to 41: the Javadoc and the header**

```java
/**
 * What one load step measured.
 *
 * @param mode the thread model
 * @param offeredRps the holds per second the load generator aimed to send
 * @param sent holds actually sent
 * @param created 201 replies
 * @param seatTaken 409 replies
 * @param refusedQueue 503 replies from the executor bound or the queue wait limit
 * @param refusedPool 503 replies from the pool's borrow limit
 * @param otherFailures 503 replies from the database or an interrupt, and failed futures
 * @param clientTimeouts replies that came later than the client timeout, or never
 * @param achievedRps good replies (201 and 409) per second of the step
 * @param latency p50 and p99 of the good replies, from the planned send time
 * @param poolWait p50 and p99 of the wait for a connection, over holds that waited for one
 * @param saturation the sampler's readings
 * @param cpuPercent the process's CPU time over the step, as a share of every core
 * @param census the thread census, if this was the census step; otherwise empty
 */
public record StepResult(
    ExecutorMode mode,
    int offeredRps,
    long sent,
    long created,
    long seatTaken,
    long refusedQueue,
    long refusedPool,
    long otherFailures,
    long clientTimeouts,
    double achievedRps,
    LatencySummary latency,
    LatencySummary poolWait,
    Saturation saturation,
    double cpuPercent,
    List<CensusLine> census) {
```

- `mode` and `offeredRps` say which thread model and which rate. `sent` is how many holds were really sent. The next six are the **buckets** that every hold falls into: `created` (201), `seatTaken` (409), `refusedQueue` (503 from the door or the queue wait limit), `refusedPool` (503 from the borrow limit), `otherFailures` (503 from the database or an interrupt, and failed futures) and `clientTimeouts` (replies later than the client timeout, or never).
- `achievedRps` is the good replies (201 and 409) per second. `latency` and `poolWait` are `LatencySummary` records. `saturation` is the sampler's readings. `cpuPercent` is the process's CPU time over the step. `census` is a list of `CensusLine`, empty unless this was the census step.

**Lines 42 to 43: a constant**

```java
  private static final double HUNDRED_PERCENT = 100.0;
```

- `HUNDRED_PERCENT`, for the error percentage.

**Lines 44 to 62: the compact constructor**

```java
  /**
   * Creates the result.
   *
   * @throws IllegalArgumentException if the outcome counts do not add up to the holds sent
   */
  public StepResult {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(latency, "latency");
    Objects.requireNonNull(poolWait, "poolWait");
    Objects.requireNonNull(saturation, "saturation");
    census = List.copyOf(census);
    long accounted =
        created + seatTaken + refusedQueue + refusedPool + otherFailures + clientTimeouts;
    // Every hold sent must end in exactly one bucket: nothing is lost, nothing counted twice.
    if (accounted != sent) {
      throw new IllegalArgumentException("outcomes add up to " + accounted + ", not " + sent);
    }
  }
```

- Four null checks. `census = List.copyOf(census)` stores an unchangeable copy.
- `long accounted = created + seatTaken + refusedQueue + refusedPool + otherFailures + clientTimeouts` adds up the six buckets. The comment says every hold sent must end in exactly one bucket: nothing is lost, nothing counted twice. If `accounted != sent` the record cannot be built. This one line catches a forgotten outcome in the counting code, and a hold counted in two places.

**Lines 63 to 75: the error percentage**

```java
  /**
   * Returns the share of holds that did not get a good reply in time.
   *
   * @return a percentage from 0 to 100; 0 when nothing was sent
   */
  public double errorPercent() {
    if (sent == 0) {
      return 0;
    }
    return (sent - created - seatTaken) * HUNDRED_PERCENT / sent;
  }
}
```

- `errorPercent` is the share of holds that did **not** get a good reply in time: `(sent - created - seatTaken) * 100 / sent`. It counts 503s and client timeouts together. If nothing was sent, it returns 0 and does not divide by zero.

### `CensusLine.java`

One line of a thread census: how many threads of one group are in one state at one place. A record.

**Lines 1 to 3: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import java.util.Objects;
```

- `Objects`: helpers such as `requireNonNull`.

**Lines 4 to 13: the header**

```java
/**
 * One line of a thread census: how many threads of one group are in one state at one place.
 *
 * @param group the thread-name prefix, such as {@code seatwave-worker-}
 * @param state the thread state, such as {@code TIMED_WAITING}
 * @param waitingIn the first SeatWave method on the stack, or the top frame if there is none
 * @param threads how many threads match
 */
public record CensusLine(String group, String state, String waitingIn, int threads) {
```

- `group` is the thread-name prefix, such as `seatwave-worker-`. `state` is the thread state, such as `TIMED_WAITING` (waiting with a time limit). `waitingIn` is the first SeatWave method on the thread's stack, or the top frame if there is none. A **stack** is the list of method calls a thread is in the middle of. `threads` is how many threads match.

**Lines 14 to 24: the compact constructor**

```java
  /** Creates the line. */
  public CensusLine {
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(waitingIn, "waitingIn");
    if (threads < 1) {
      throw new IllegalArgumentException("a census line counts at least one thread");
    }
  }
}
```

- Refuses a null text, and a line with fewer than one thread: a census line exists only to count at least one.

### `ThreadCensus.java`

Counts platform threads by name group, state and the place they wait: the reading of a thread dump, done in code.

**Lines 1 to 8: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
```

- `ArrayList`: a growable list.
- `Comparator`: a rule for ordering objects.
- `List`: an ordered collection.
- `Map`: a collection of key and value pairs.
- `Objects`: helpers such as `requireNonNull`.
- `TreeMap`: a map that keeps its keys sorted.

**Lines 9 to 23: the class comment and constants**

```java
/**
 * Counts platform threads by name group, state and the place they wait: the reading of a thread
 * dump, done in code.
 *
 * <p>It sees platform threads only, because {@link Thread#getAllStackTraces()} does not list
 * virtual threads; for those, use {@code jcmd <pid> Thread.dump_to_file -format=json <file>}.
 * Stateless.
 */
public final class ThreadCensus {

  private static final String SEATWAVE_PACKAGE = "io.plainround.seatwave.";
  private static final String NO_FRAME = "(no stack)";

  private ThreadCensus() {}
```

- The Javadoc says it sees platform threads only, because `Thread.getAllStackTraces()` does not list virtual threads. For those, use `jcmd <pid> Thread.dump_to_file -format=json <file>`. I confirmed this on JDK 25: a virtual thread named `v-test` that was sleeping did not appear in `Thread.getAllStackTraces()`.
- `SEATWAVE_PACKAGE` is the start of every SeatWave class name. `NO_FRAME` is the text for a thread with no stack.

**Lines 24 to 52: taking a census**

```java
  /**
   * Takes a census now.
   *
   * @param groups the thread-name prefixes to count; other threads are ignored
   * @return one line per group, state and place, largest first
   */
  public static List<CensusLine> take(List<String> groups) {
    Objects.requireNonNull(groups, "groups");
    Map<String, Integer> counts = new TreeMap<>();
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      String name = entry.getKey().getName();
      for (String group : groups) {
        if (name.startsWith(group)) {
          String key =
              String.join(
                  "\t", group, entry.getKey().getState().name(), waitingIn(entry.getValue()));
          counts.merge(key, 1, Integer::sum);
        }
      }
    }
    List<CensusLine> lines = new ArrayList<>();
    counts.forEach((key, threads) -> {
      String[] parts = key.split("\t", -1);
      lines.add(new CensusLine(parts[0], parts[1], parts[2], threads));
    });
    lines.sort(Comparator.comparingInt(CensusLine::threads).reversed());
    return List.copyOf(lines);
  }
```

- `Thread.getAllStackTraces()` returns a map from every live platform thread to its stack. Each entry's key is the thread and the value is an array of frames.
- `new TreeMap<>()` is a map that keeps its keys sorted, so the output order is stable. The key is a text made of the group, the state and the place, joined by a tab; the value is a count.
- `name.startsWith(group)` picks the threads of each group. `String.join("\t", ...)` glues the three parts with a tab character. `entry.getKey().getState().name()` is the thread state as text.
- `counts.merge(key, 1, Integer::sum)` adds 1 to the count for the key, or starts it at 1. `Integer::sum` is a method reference to the function that adds two numbers.
- `counts.forEach((key, threads) -> {...})` walks the map. `key.split("\t", -1)` cuts the key back into its three parts. The `-1` keeps empty trailing parts. Each becomes a `CensusLine`.
- `lines.sort(Comparator.comparingInt(CensusLine::threads).reversed())` sorts largest first. `List.copyOf(lines)` returns an unchangeable list.

**Lines 53 to 62: where a thread waits**

```java
  // The first SeatWave frame says why the thread waits; the JDK frames above it only say how.
  private static String waitingIn(StackTraceElement[] frames) {
    for (StackTraceElement frame : frames) {
      if (frame.getClassName().startsWith(SEATWAVE_PACKAGE)) {
        return shortName(frame);
      }
    }
    return frames.length == 0 ? NO_FRAME : shortName(frames[0]);
  }
```

- The comment: the first SeatWave frame says why the thread waits, and the JDK frames above it only say how. A stack is listed from the innermost call outward. The loop returns the first frame whose class is in the SeatWave package. If there is none, it returns the top frame, or `(no stack)` for an empty stack.
- That is why the census prints `in ConnectionPool.borrow` and not the JDK method that actually parks the thread.

**Lines 63 to 68: a short name**

```java
  private static String shortName(StackTraceElement frame) {
    String className = frame.getClassName();
    return className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
  }
}
```

- `className.substring(className.lastIndexOf('.') + 1)` keeps the part of the class name after the last dot, so `io.plainround.seatwave.concurrency.node.ConnectionPool` becomes `ConnectionPool`. Adding `"." + frame.getMethodName()` gives `ConnectionPool.borrow`.

### `OpenModelLoad.java`

The load generator. Holds arrive at random times at a set average rate, whether or not the node keeps up, as fans do at an on-sale.

**Lines 1 to 29: package and imports**

```java
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
```

- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `HoldOutcome`: the enum of every way a hold can end.
- `HoldReply`: the record that carries one hold's outcome and times.
- `HoldRequest`: the record that carries one request to hold a seat.
- `NodeSettings`: the record of every limit of one node.
- `PlatformWorkers`: the platform-thread pool with a bounded queue.
- `PoolSnapshot`: the record of the connection pool counters at one moment.
- `SeatStore`: the class that runs the seat SQL.
- `SeatWaveNode`: the class that wires one running node.
- `ManagementFactory`: the entry to the JVM's own measurements: memory, threads, operating system.
- `ThreadMXBean`: the object that reports how many platform threads exist.
- `Duration`: a length of time.
- `ArrayList`: a growable list.
- `Arrays`: static helpers for arrays: sort, copy.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.
- `Random`: a pseudo-random number generator that repeats for the same seed.
- `CompletableFuture`: a future that some code completes by hand.
- `ExecutionException`: the exception that wraps a failure inside a task.
- `Executors`: factory methods that build executors.
- `Future`: a handle on a result that is not ready yet.
- `ScheduledExecutorService`: an executor that runs tasks later or repeatedly.
- `TimeUnit`: a unit of time for waits, such as nanoseconds.
- `TimeoutException`: thrown when a wait ran out of time.
- `AtomicInteger`: an integer that threads can update safely.
- `LongAdder`: a counter built for many threads adding at once.
- `LockSupport`: low-level park and unpark of the current thread.

**Lines 30 to 46: the class comment and constants**

```java
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
```

- The class comment says the arrivals form a Poisson process with a fixed count, from a fixed seed. Each latency is measured from the planned send time. The calling thread plays SeatWave's accept loop. It is not thread-safe: one thread runs one step at a time.
- `CENSUS_GROUPS` is the list of thread-name prefixes the census counts: `seatwave-worker-` (the platform pool) and `ForkJoinPool-`. The second is the name of the threads that carry virtual threads, the **carriers**, which are platform threads. Counting them shows how few platform threads a virtual node uses.

**Lines 47 to 57: the constructor**

```java
  private final BenchmarkSettings settings;

  /**
   * Creates the generator.
   *
   * @param settings the rates, durations, seed and timeouts
   */
  public OpenModelLoad(BenchmarkSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }
```

- Stores the settings after a null check.

**Lines 58 to 75: running every step**

```java
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
```

- `runAll` warms up first: `runStep(node, settings.stepRates().getFirst(), settings.warmup())` runs the first rate for the warm-up time, and the result is dropped. The comment says the warm-up lets the JIT compiler compile the hot path.
- Then one `runStep` for each rate, each with the normal step duration. The results are collected in a list and returned as an unchangeable copy.

**Lines 76 to 94: the start of a step**

```java
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
```

- `processCpuNanos()` reads the process's CPU time before the step. `System.nanoTime()` is the start of the step. `new Sampler(node)` makes the object that reads the counters.
- Two variables are declared and assigned later inside the `try`: `pending` (every hold sent) and `census`.

**Lines 95 to 109: the sampler and the arrivals**

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

- `try (ScheduledExecutorService samplerExecutor = Executors.newSingleThreadScheduledExecutor(...))` makes an executor with one platform thread named `seatwave-sampler`, closed automatically at the end of the block (Java 19 or newer). A **scheduled** executor runs a task later or repeatedly.
- `samplerExecutor.scheduleAtFixedRate(sampler::sample, 0, interval, NANOSECONDS)` runs `sample` immediately and then every `sampleInterval`, from the same thread.
- `censusLater` is a `Future<List<CensusLine>>`. If this step's rate equals `census.at.rps`, the census is **scheduled** for half-way through the step: `samplerExecutor.schedule(() -> ThreadCensus.take(CENSUS_GROUPS), duration / 2, ...)`, so the node is busy when it is taken. Otherwise `CompletableFuture.completedFuture(List.of())` is a future that is already done with an empty list.
- `sendArrivals(...)` is the whole step: it returns after the last hold has been sent. `awaitCensus` then gets the census.

**Lines 110 to 122: the end of a step**

```java
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
```

- `wallNanos` is how long the step took. `cpuPercent` is the CPU time the process used, as a share of every core: `cpuTime x 100 / (wallTime x processors)`. On the measuring machine, 12 processors, a result of 1 percent means 12 percent of one core.
- `collect` waits for every reply and builds the `StepResult`. It takes two small records: a `StepFrame` (mode, rate, duration) and an `Observed` (saturation, CPU, census).
- `awaitDrain` checks that no hold is still running. The result is returned only after the check.

**Lines 123 to 138: planning the arrivals**

```java
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
```

- `new Random(settings.randomSeed() + offeredRps)`: one seed per rate. A `Random` with a seed makes the same sequence on every run, so every run of the same step sends the same holds at the same moments.
- `arrivals = Math.round(offeredRps * (durationNanos / NANOS_PER_SECOND))` is rate x duration: 380 per second for 10 seconds is 3,800 holds.
- The comment: exactly rate x duration arrivals at independent, uniformly random moments: a Poisson process with its count fixed. Each offset is `random.nextDouble() * durationNanos`, a random point in the step. `Arrays.sort(offsetsNanos)` puts the moments in time order.
- `seatCount` is the 20,000 seats. `pending` will hold one entry per hold sent.

**Lines 139 to 152: sending the holds**

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
    return pending;
  }
```

- For each moment: `plannedNanos = startNanos + offsetNanos` is the planned send time. `parkUntil(plannedNanos)` sleeps until then.
- The request is made with the demo event, a random seat (`SeatStore.seatLabel(random.nextInt(seatCount))`), and a customer name `fan_` plus the number sent so far.
- `node.endpoint().submit(request)` returns a future immediately. `.thenApply(answer -> new Timed(answer, System.nanoTime()))` attaches a step that runs when the future completes, on the thread that completes it (a worker, or the caller for a refusal at the door). It stamps the **completion time** at that moment. So the latency is not distorted by how late this thread gets around to reading the future.
- `pending.add(new Pending(plannedNanos, reply))` keeps the planned time with the future. The loop never waits for a reply: that is the open model.

**Lines 153 to 174: collecting the replies**

```java
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
```

- `clientTimeoutNanos` is the client's patience. `Tally` counts the outcomes.
- For each pending hold: `patienceNanos = plannedNanos + clientTimeout - now` is how long the client would still wait. `one.reply().get(Math.max(0, patienceNanos), NANOSECONDS)` waits for the reply for that long. `Math.max(0, ...)` keeps it from going negative.
- `latencyNanos = timed.completedNanos() - one.plannedNanos()` is the latency from the **planned** send time. If it is above the client timeout, the reply came too late: `tally.clientTimeouts++`. Otherwise `tally.count(...)` records it.
- `catch (TimeoutException gaveUp)`: the client stopped waiting. It counts as a client timeout. The comment says the node still owes the hold an answer, which `awaitDrain` checks.
- `catch (ExecutionException bug)`: a future that failed with an exception means a bug in the node. It is rethrown as `IllegalStateException` with the cause attached.

**Lines 175 to 193: building the result**

```java
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
```

- `achievedRps` is the good replies per second: `(created + seatTaken) x 1e9 / durationNanos`.
- `new StepResult(...)` is built from the counts, two `LatencySummary` values (`goodLatencies()` and `poolWaits()`), and the observed values. The constructor of `StepResult` checks that the buckets add up to `sent`.

**Lines 194 to 213: checking the invariant**

```java
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
```

- The comment states the promise: every hold ends in bounded time. `longestLegalHold` adds the five waits a hold may spend: the queue wait limit, the borrow limit, the database wait, the statement timeout and the client timeout. `Duration.plus` adds two durations.
- `pending.stream().map(Pending::reply).toArray(CompletableFuture<?>[]::new)` makes an array of every future. `CompletableFuture.allOf(replies)` is a future that completes when all of them have. `.get(longestLegalHold, NANOSECONDS)` waits for that long at most.
- `catch (TimeoutException invariantBroken)`: some hold has been running past the longest legal time. The benchmark stops with `IllegalStateException` and a message, because numbers from a node that hangs would be wrong.

**Lines 214 to 222: getting the census**

```java
  private static List<CensusLine> awaitCensus(Future<List<CensusLine>> census, Duration duration)
      throws InterruptedException {
    try {
      return census.get(duration.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("the thread census did not finish", failed);
    }
  }
```

- Waits for the census at most one step length. If the sampler thread failed or took too long, the run stops with a message that says the census did not finish.

**Lines 223 to 231: waiting for the next arrival**

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

- `parkUntil` sleeps until a planned time. `LockSupport.parkNanos(remaining)` pauses the current thread for up to that long. It may return early, so the loop reads the clock again and parks again while time remains. The comment says this is pacing, not synchronization.

**Lines 232 to 239: process CPU time**

```java
  private static long processCpuNanos() {
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
      return operatingSystem.getProcessCpuTime();
    }
    throw new IllegalStateException("this JVM does not report process CPU time");
  }
```

- `ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean operatingSystem` tests whether the JVM's operating-system object is the extended type, and if so names it `operatingSystem` in the same expression (pattern matching for `instanceof`, Java 16). `getProcessCpuTime()` returns the CPU time used by the whole process, in nanoseconds. If the JVM does not report it, the method throws.

**Lines 240 to 247: four small records**

```java
  private record Pending(long plannedNanos, CompletableFuture<Timed> reply) {}

  private record StepFrame(ExecutorMode mode, int offeredRps, Duration duration) {}

  private record Observed(Saturation saturation, double cpuPercent, List<CensusLine> census) {}

  private record Timed(HoldReply reply, long completedNanos) {}
```

- `Pending` pairs a planned time with a future of a `Timed`. `StepFrame` groups the mode, rate and duration. `Observed` groups the sampler's saturation, the CPU share and the census. `Timed` pairs a `HoldReply` with the nanosecond at which it completed. Each is a one-line record: a name and its components, and no body. They exist so a method takes three arguments and not eight.

**Lines 248 to 265: the tally**

```java
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
```

- `Tally` counts outcomes on the calling thread only. Two arrays hold the good latencies and the pool waits, sized to the number of holds so they never need to grow. Counters for each bucket are `long`.

**Lines 266 to 281: counting one reply**

```java
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
```

- A good reply (`isGood()`) has its latency stored in `goodLatencyNanos` at the next free index: `goodLatencyNanos[goodCount++]` uses the index and then adds 1.
- The pool wait is stored for good replies and for pool timeouts. Those are the holds that waited for a connection, and they are the ones whose wait is worth summarizing. Holds refused before they reach the pool have no wait.
- The `switch` puts each outcome in its bucket. `case QUEUE_FULL, QUEUE_WAIT_EXCEEDED ->` lists two outcomes for one result. The compiler does not force a `switch` **statement** over an enum to list every value, so a forgotten outcome would count nowhere. The check in `StepResult` would then fail at run time, because the buckets would not add up to `sent`.

**Lines 282 to 290: copying the filled part**

```java
    long[] goodLatencies() {
      return Arrays.copyOf(goodLatencyNanos, goodCount);
    }

    long[] poolWaits() {
      return Arrays.copyOf(poolWaitNanos, poolWaitCount);
    }
  }
```

- `goodLatencies` and `poolWaits` return a copy of only the part of the array that was filled: `Arrays.copyOf(array, count)`.

**Lines 291 to 308: the sampler**

```java
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
```

- `Sampler` reads the counters on the sampler thread. It is thread-safe: every field is atomic, so the calling thread may read the result after the sampler stops.
- `ThreadMXBean threads = ManagementFactory.getThreadMXBean()` reports the number of platform threads.
- Four `AtomicInteger`s hold the largest value seen. Two `LongAdder`s add up the busy connections over every reading and count the readings.

**Lines 309 to 318: one reading**

```java
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

- `pool.snapshot()` reads the pool's counters. `maxThreadsAlive.accumulateAndGet(value, Math::max)` replaces the stored number with the larger of the two in one atomic step. It is called by one thread here, but the atomic form means a reader on another thread never sees a torn value.
- `busyConnectionReadings.add(pool.busy())` adds the busy count of this reading, and `readings.increment()` counts the reading. The mean is their ratio.

**Lines 319 to 331: the result of sampling**

```java
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
```

- `Math.max(readings.sum(), 1)` avoids dividing by zero if no reading was taken.
- `meanBusy = busyConnectionReadings.sum() / (double) count` is the average number of busy connections. The utilization is that mean times 100 divided by the pool size, capped at 100 with `Math.min`.

### `BenchmarkCsv.java`

Renders the step results as CSV, a plain-text table that any spreadsheet program opens and charts. See `csv-and-svg-output.md`.

**Lines 1 to 5: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
```

- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Objects`: helpers such as `requireNonNull`.

**Lines 6 to 16: the header line**

```java
/** Renders step results as CSV that any spreadsheet program opens and charts. Stateless. */
public final class BenchmarkCsv {

  /** The header line, one column per measured value. */
  public static final String HEADER =
      "mode,offered_rps,achieved_rps,sent,created_201,taken_409,refused_queue_503,refused_pool_503,"
          + "other_503,client_timeouts,error_pct,p50_ms,p99_ms,pool_wait_p99_ms,threads_alive_max,"
          + "platform_threads_max,queue_max,pool_waiters_max,pool_busy_pct,cpu_pct";

  private BenchmarkCsv() {}
```

- `HEADER` is one text built from three pieces joined by `+`. The compiler joins them at compile time. There are twenty column names, in the same order as the values below. A line break in the source does not put a newline in the text; the text is one line.
- `private BenchmarkCsv() {}` is a **private constructor**: no one can make an object of this class. It only has static methods.

**Lines 17 to 56: rendering**

```java
  /**
   * Renders the results.
   *
   * @param results the steps, in order
   * @return the header and one line per step, each ending with a newline
   */
  public static String render(List<StepResult> results) {
    Objects.requireNonNull(results, "results");
    StringBuilder csv = new StringBuilder(HEADER).append('\n');
    for (StepResult step : results) {
      Saturation saturation = step.saturation();
      csv.append(
          String.format(
              Locale.ROOT,
              "%s,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%.2f,%.1f,%.1f,%.1f,%d,%d,%d,%d,%.1f,%.1f\n",
              step.mode().name().toLowerCase(Locale.ROOT),
              step.offeredRps(),
              step.achievedRps(),
              step.sent(),
              step.created(),
              step.seatTaken(),
              step.refusedQueue(),
              step.refusedPool(),
              step.otherFailures(),
              step.clientTimeouts(),
              step.errorPercent(),
              step.latency().p50Millis(),
              step.latency().p99Millis(),
              step.poolWait().p99Millis(),
              saturation.maxThreadsAlive(),
              saturation.maxPlatformThreads(),
              saturation.maxQueued(),
              saturation.maxPoolWaiters(),
              saturation.meanPoolBusyPercent(),
              step.cpuPercent()));
    }
    return csv.toString();
  }
}
```

- `StringBuilder csv = new StringBuilder(HEADER).append('\n')` starts a text with the header and a newline. A `StringBuilder` builds a long text without copying it at each append.
- For each step, one `String.format` fills the template with twenty values. The template `%s,%d,%.1f,...` has one place for each: `%s` text, `%d` whole number, `%.1f` a decimal with one digit after the point, `%.2f` with two. `Locale.ROOT` guarantees a dot as the decimal separator and no thousands separators, so a spreadsheet in any country reads the numbers.
- The values, in order: the mode name in lower case, the offered rate, the achieved rate, the holds sent, the 201s, the 409s, the queue refusals, the pool refusals, other failures, client timeouts, the error percentage, p50, p99, the pool wait p99, the most threads alive, the most platform threads, the longest queue, the most pool waiters, the pool use and the CPU share.
- The line ends with `\n`, not `%n`: a CSV file has the same line ends on every system.

### `LoadChartSvg.java`

Draws one metric against the offered rate as a small SVG line chart, one line per thread model, with no charting library. The file is plain text a browser opens.

**Lines 1 to 8: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
```

- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Map`: a collection of key and value pairs.
- `Objects`: helpers such as `requireNonNull`.
- `TreeMap`: a map that keeps its keys sorted.

**Lines 9 to 33: sizes and offsets**

```java
/**
 * Draws one metric against the offered rate as a small SVG line chart, one line per thread model,
 * with no charting library: the file is plain text a browser opens. Stateless.
 */
public final class LoadChartSvg {

  private static final int WIDTH = 720;
  private static final int HEIGHT = 420;
  private static final int LEFT = 80;
  private static final int RIGHT = 160;
  private static final int TOP = 50;
  private static final int BOTTOM = 60;
  private static final int TICKS = 5;
  private static final int POINT_RADIUS = 4;
  private static final int LEGEND_LINE_HEIGHT = 22;
  // Offsets in pixels that keep labels clear of the lines they name.
  private static final double AXIS_TITLE_GAP = 18;
  private static final double TICK_LABEL_GAP = 8;
  private static final double BASELINE_NUDGE = 4;
  private static final int LEGEND_SWATCH_GAP = 10;
  private static final double LEGEND_TEXT_GAP = 30;
  private static final double HALF = 2.0;
  private static final double DECIMAL_BASE = 10;
  private static final double[] NICE_STEPS = {1, 2, 5, 10};
```

- **SVG** (Scalable Vector Graphics) is a text format for drawings: shapes written as tags. A browser draws it. See `csv-and-svg-output.md`.
- The chart is 720 wide and 420 high, in SVG units that a browser treats as pixels. `LEFT`, `RIGHT`, `TOP` and `BOTTOM` are the margins around the plot area, which leave room for the axis numbers and the legend. `TICKS = 5` is the number of intervals on each axis.
- The remaining constants are offsets in pixels that keep labels clear of the lines they name, and `NICE_STEPS` is the list 1, 2, 5, 10 used to round axis maximums up to round numbers.

**Lines 34 to 38: colors**

```java
  // Two colors that stay apart for the common kinds of color blindness.
  private static final Map<ExecutorMode, String> COLORS =
      Map.of(ExecutorMode.PLATFORM, "#d55e00", ExecutorMode.VIRTUAL, "#0072b2");

  private LoadChartSvg() {}
```

- `COLORS` is an unchangeable `Map` of thread model to a color in hex code. `#d55e00` is a vermilion orange and `#0072b2` a blue. The comment says they stay apart for the common kinds of color blindness. The class has a private constructor, so it is used only through `render`.

**Lines 39 to 57: scaling the data**

```java
  /**
   * Draws the chart.
   *
   * @param title the chart title; it is escaped
   * @param results the steps of every mode
   * @param metric the value to plot
   * @param referenceLine a horizontal line to draw, such as the p99 target; 0 for none
   * @return the SVG document
   */
  public static String render(
      String title, List<StepResult> results, ChartMetric metric, double referenceLine) {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(results, "results");
    Objects.requireNonNull(metric, "metric");
    double maxRate = results.stream().mapToInt(StepResult::offeredRps).max().orElse(1);
    double maxValue =
        Math.max(referenceLine, results.stream().mapToDouble(metric::read).max().orElse(1));
    Scale scale = new Scale(niceCeiling(maxRate), niceCeiling(maxValue));
```

- `render(title, results, metric, referenceLine)` returns the whole SVG document as text. `referenceLine` is a horizontal line, such as the p99 target, or 0 for none.
- `maxRate` is the largest offered rate. `maxValue` is the larger of the reference line and the largest value of the metric. `results.stream().mapToInt(StepResult::offeredRps).max().orElse(1)` runs through the steps and takes the maximum. A **stream** is a pipeline over a collection. `orElse(1)` gives 1 if there are no steps.
- `new Scale(niceCeiling(maxRate), niceCeiling(maxValue))` makes the object that turns data values into pixels. The maximums are rounded up to round numbers so the axis ticks read 0, 200, 400.

**Lines 58 to 68: the opening tag**

```java
    StringBuilder svg = new StringBuilder();
    svg.append(
        String.format(
            Locale.ROOT,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\""
                + " font-family=\"sans-serif\" font-size=\"12\">\n",
            WIDTH,
            HEIGHT,
            WIDTH,
            HEIGHT));
    svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
```

- The first `svg.append(String.format(...))` writes the `<svg ...>` tag: `xmlns` is the namespace that says "this is SVG", `width` and `height` are the size, `viewBox="0 0 720 420"` is the coordinate system, and `font-family` and `font-size` are inherited by every text. `\"` is a double quote inside a Java string.
- The `<rect width="100%" height="100%" fill="#ffffff"/>` is a white background, so the chart is readable on a dark page too.

**Lines 69 to 70: title and axes**

```java
    text(svg, WIDTH / HALF, TOP / HALF, "middle", escape(title));
    axes(svg, scale, metric);
```

- `text(...)` writes the title, centered at the top, with `escape(title)` so a `<` in a title cannot break the document. `axes(svg, scale, metric)` draws the two axes and the numbers.

**Lines 71 to 88: the reference line**

```java
    if (referenceLine > 0) {
      double lineY = scale.y(referenceLine);
      svg.append(
          String.format(
              Locale.ROOT,
              "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#555555\""
                  + " stroke-dasharray=\"6 4\"/>\n",
              LEFT,
              lineY,
              WIDTH - RIGHT,
              lineY));
      text(
          svg,
          WIDTH - RIGHT + BASELINE_NUDGE,
          lineY + BASELINE_NUDGE,
          "start",
          "target " + number(referenceLine));
    }
```

- If `referenceLine > 0`: `scale.y(referenceLine)` is the pixel height of the line. A `<line>` tag with a dashed stroke (`stroke-dasharray="6 4"`: 6 on, 4 off) runs from the left edge to the right edge of the plot. A `<text>` tag labels it `target 300`.

**Lines 89 to 92: the lines and the end**

```java
    lines(svg, scale, results, metric);
    svg.append("</svg>\n");
    return svg.toString();
  }
```

- `lines(...)` draws the points and the polylines. The last append closes the document with `</svg>`.

**Lines 93 to 108: the two axis lines**

```java
  private static void axes(StringBuilder svg, Scale scale, ChartMetric metric) {
    int plotBottom = HEIGHT - BOTTOM;
    svg.append(
        String.format(
            Locale.ROOT,
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#000000\"/>\n"
                + "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#000000\"/>\n",
            LEFT,
            plotBottom,
            WIDTH - RIGHT,
            plotBottom,
            LEFT,
            TOP,
            LEFT,
            plotBottom));
```

- `plotBottom = HEIGHT - BOTTOM` is the pixel row of the horizontal axis. Two `<line>` tags draw the horizontal and the vertical axis in black. Note that in SVG the vertical position counts **downward** from the top, so a bigger y is lower on the screen.

**Lines 109 to 120: ticks and the horizontal title**

```java
    for (int tickIndex = 0; tickIndex <= TICKS; tickIndex++) {
      double rate = scale.maxRate() * tickIndex / TICKS;
      double value = scale.maxValue() * tickIndex / TICKS;
      text(svg, scale.x(rate), plotBottom + AXIS_TITLE_GAP, "middle", number(rate));
      text(svg, LEFT - TICK_LABEL_GAP, scale.y(value) + BASELINE_NUDGE, "end", number(value));
    }
    text(
        svg,
        (LEFT + WIDTH - RIGHT) / HALF,
        HEIGHT - AXIS_TITLE_GAP,
        "middle",
        "offered holds per second");
```

- The loop writes six tick labels on each axis (`tickIndex` 0 to 5): `rate = maxRate x tickIndex / 5` along the bottom, `value = maxValue x tickIndex / 5` up the side. `text-anchor` `middle` centers the text on its x, `end` ends it there. `number(...)` formats the number with commas.
- The last `text(...)` writes the horizontal axis title, `offered holds per second`, centered under the plot.

**Lines 121 to 129: the vertical title**

```java
    svg.append(
        String.format(
            Locale.ROOT,
            "<text x=\"18\" y=\"%.1f\" text-anchor=\"middle\" transform=\"rotate(-90 18 %.1f)\">"
                + "%s</text>\n",
            (TOP + plotBottom) / HALF,
            (TOP + plotBottom) / HALF,
            escape(metric.axisLabel())));
  }
```

- The vertical axis title is rotated: `transform="rotate(-90 18 y)"` turns the text a quarter turn counter-clockwise around the point (18, y), so it reads upward along the left edge. `escape(metric.axisLabel())` makes the label safe.

**Lines 130 to 148: the points**

```java
  private static void lines(
      StringBuilder svg, Scale scale, List<StepResult> results, ChartMetric metric) {
    Map<ExecutorMode, StringBuilder> points = new TreeMap<>();
    for (StepResult step : results) {
      double pointX = scale.x(step.offeredRps());
      double pointY = scale.y(metric.read(step));
      points
          .computeIfAbsent(step.mode(), unused -> new StringBuilder())
          .append(String.format(Locale.ROOT, "%.1f,%.1f ", pointX, pointY));
      svg.append(
          String.format(
              Locale.ROOT,
              "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"%s\"/>\n",
              pointX,
              pointY,
              POINT_RADIUS,
              COLORS.get(step.mode())));
    }
```

- `Map<ExecutorMode, StringBuilder> points = new TreeMap<>()` collects one list of coordinates for each thread model, sorted by mode.
- For each step: `pointX = scale.x(offeredRps)` and `pointY = scale.y(metric.read(step))` are its pixel position. `computeIfAbsent(step.mode(), unused -> new StringBuilder())` gets the model's list or makes it, and `.append("x,y ")` adds the point. A `<circle>` tag marks the point, `r` is the radius and `fill` the model's color.

**Lines 149 to 174: the polylines and the legend**

```java
    int legendIndex = 0;
    for (Map.Entry<ExecutorMode, StringBuilder> line : points.entrySet()) {
      String color = COLORS.get(line.getKey());
      svg.append(
          String.format(
              Locale.ROOT,
              "<polyline fill=\"none\" stroke=\"%s\" stroke-width=\"2\" points=\"%s\"/>\n",
              color,
              line.getValue().toString().strip()));
      double legendY = TOP + LEGEND_LINE_HEIGHT * (legendIndex + 1.0);
      svg.append(
          String.format(
              Locale.ROOT,
              "<rect x=\"%d\" y=\"%.1f\" width=\"14\" height=\"4\" fill=\"%s\"/>\n",
              WIDTH - RIGHT + LEGEND_SWATCH_GAP,
              legendY - BASELINE_NUDGE,
              color));
      text(
          svg,
          WIDTH - RIGHT + LEGEND_TEXT_GAP,
          legendY,
          "start",
          line.getKey().name().toLowerCase(Locale.ROOT) + " threads");
      legendIndex++;
    }
  }
```

- For each model: a `<polyline>` tag joins its points with a line (`fill="none"`, so no area is filled). `line.getValue().toString().strip()` is the list of points without the trailing space.
- `legendY` is the row of this legend entry. A small `<rect>` is the colored swatch and a `<text>` names the model: `platform threads` or `virtual threads`. `legendIndex++` moves the next entry down.

**Lines 175 to 186: one text tag**

```java
  private static void text(
      StringBuilder svg, double textX, double textY, String anchor, String body) {
    svg.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\">%s</text>\n",
            textX,
            textY,
            anchor,
            body));
  }
```

- `text` writes one `<text>` tag at a position, with an anchor and a body. Every label goes through this one method.

**Lines 187 to 200: round axis maximums**

```java
  // Rounds up to 1, 2 or 5 times a power of ten, so the axis ticks are round numbers.
  private static double niceCeiling(double value) {
    if (value <= 0) {
      return 1;
    }
    double power = Math.pow(DECIMAL_BASE, Math.floor(Math.log10(value)));
    for (double step : NICE_STEPS) {
      if (step * power >= value) {
        return step * power;
      }
    }
    return DECIMAL_BASE * power;
  }
```

- The comment: rounds up to 1, 2 or 5 times a power of ten, so the ticks are round numbers. For a value of 709.8: `Math.log10(709.8)` is 2.85, `Math.floor` gives 2, and `Math.pow(10, 2)` is 100. Then the loop tries 1 x 100, 2 x 100, 5 x 100, 10 x 100 and returns the first that is at least the value: 1,000. A value of 0 or less returns 1.

**Lines 201 to 206: formatting a number**

```java
  private static String number(double value) {
    return value == Math.rint(value)
        ? String.format(Locale.ROOT, "%,d", (long) value)
        : String.format(Locale.ROOT, "%,.1f", value);
  }
```

- `value == Math.rint(value)` tests whether the number is whole. A whole number prints as `%,d`, with commas as thousands separators: `1,200`. Otherwise it prints with one decimal: `%,.1f`.

**Lines 207 to 210: escaping text**

```java
  private static String escape(String raw) {
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
```

- Replaces `&`, `<` and `>` with `&amp;`, `&lt;` and `&gt;`, the three characters that would be read as markup. `&` goes first, or it would damage the others.

**Lines 211 to 221: turning data into pixels**

```java
  private record Scale(double maxRate, double maxValue) {
    double x(double rate) {
      return LEFT + (WIDTH - LEFT - RIGHT) * rate / maxRate;
    }

    double y(double value) {
      return HEIGHT - BOTTOM - (HEIGHT - TOP - BOTTOM) * value / maxValue;
    }
  }
}
```

- `Scale` is a record with two methods. `x(rate)` is `LEFT + plotWidth x rate / maxRate`: the left margin plus the share of the plot width. `y(value)` is `HEIGHT - BOTTOM - plotHeight x value / maxValue`: because y counts downward, a bigger value is a smaller y.

### `BenchmarkReport.java`

Renders the step results as the plain-text table, chart and summary the benchmark prints. Stateless: every method is static.

**Lines 1 to 8: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
```

- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `Duration`: a length of time.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Objects`: helpers such as `requireNonNull`.
- `Optional`: a box that holds a value or nothing.

**Lines 9 to 18: class and constants**

```java
/**
 * Renders step results as the plain-text table, chart and summary the benchmark prints. Stateless.
 */
public final class BenchmarkReport {

  private static final int BAR_WIDTH = 50;
  private static final char BAR = '#';

  private BenchmarkReport() {}
```

- `BAR_WIDTH = 50` is the width of the longest bar in the text chart. `BAR = '#'` is the character a bar is drawn with.

**Lines 19 to 55: the table**

```java
  /**
   * Renders the table of every step.
   *
   * @param results the steps of every mode, in order
   * @return the table, one line per step
   */
  public static String table(List<StepResult> results) {
    Objects.requireNonNull(results, "results");
    StringBuilder table =
        new StringBuilder(
            "mode      offered achieved  p50 ms   p99 ms  errors%  503q  503p  late  threads"
                + "  platform  waiters  waitp99  busy%  cpu%\n");
    for (StepResult step : results) {
      Saturation saturation = step.saturation();
      table.append(
          String.format(
              Locale.ROOT,
              "%-8s %8d %8.1f %7.1f %8.1f %8.2f %5d %5d %5d %8d %9d %8d %8.1f %6.1f %5.1f%n",
              step.mode().name().toLowerCase(Locale.ROOT),
              step.offeredRps(),
              step.achievedRps(),
              step.latency().p50Millis(),
              step.latency().p99Millis(),
              step.errorPercent(),
              step.refusedQueue(),
              step.refusedPool(),
              step.clientTimeouts(),
              saturation.maxThreadsAlive(),
              saturation.maxPlatformThreads(),
              saturation.maxPoolWaiters(),
              step.poolWait().p99Millis(),
              saturation.meanPoolBusyPercent(),
              step.cpuPercent()));
    }
    return table.toString();
  }
```

- The table starts with a header line. `%n` in a format string is the system's line end.
- The format `"%-8s %8d %8.1f %7.1f %8.1f %8.2f %5d %5d %5d %8d %9d %8d %8.1f %6.1f %5.1f%n"` has fifteen places. A number between `%` and the letter is a minimum width: `%8d` pads a whole number to 8 characters from the right, and `%-8s` pads text to 8 from the left. `%8.1f` is a decimal with one digit after the point in 8 characters. This is what makes the columns line up.
- The columns, in order: `mode`, `offered` (the rate sent), `achieved` (good replies per second), `p50 ms`, `p99 ms`, `errors%`, `503q` (queue refusals), `503p` (pool timeouts), `late` (client timeouts), `threads`, `platform` (every platform thread in the JVM), `waiters` (most borrowers waiting), `waitp99` (p99 of the wait for a connection), `busy%` (the pool's average utilization) and `cpu%`.

**Lines 56 to 89: the text chart**

```java
  /**
   * Draws p99 against the offered rate as rows of bars, one block per mode.
   *
   * @param results the steps of every mode
   * @return the chart
   */
  public static String textChart(List<StepResult> results) {
    Objects.requireNonNull(results, "results");
    double longest =
        results.stream().mapToDouble(step -> step.latency().p99Millis()).max().orElse(1);
    StringBuilder chart = new StringBuilder();
    ExecutorMode current = null;
    for (StepResult step : results) {
      if (step.mode() != current) {
        current = step.mode();
        chart.append(
            String.format(
                Locale.ROOT,
                "p99 against offered load, %s threads%n",
                current.name().toLowerCase(Locale.ROOT)));
      }
      int barLength = (int) Math.round(BAR_WIDTH * step.latency().p99Millis() / longest);
      chart.append(
          String.format(
              Locale.ROOT,
              "%6d holds/s | %-" + BAR_WIDTH + "s | p99 %8.1f ms, errors %6.2f%%%n",
              step.offeredRps(),
              String.valueOf(BAR).repeat(barLength),
              step.latency().p99Millis(),
              step.errorPercent()));
    }
    return chart.toString();
  }
```

- `longest` is the largest p99 of all steps, so the longest bar is 50 characters.
- `current` remembers the mode of the previous row. When the mode changes, a title line is written: `p99 against offered load, platform threads`.
- `barLength = Math.round(BAR_WIDTH * p99 / longest)` is the bar's length, scaled. `String.valueOf(BAR).repeat(barLength)` makes that many `#` characters (`repeat` is Java 11).
- The format `"%6d holds/s | %-" + BAR_WIDTH + "s | p99 %8.1f ms, errors %6.2f%%%n"` builds the template by joining text and the number 50, so the width is a named constant. `%%` prints one percent sign.

**Lines 90 to 116: the last healthy step**

```java
  /**
   * Finds the highest offered rate up to which every step met the target.
   *
   * @param results the steps of one mode, in increasing rate
   * @param targetP99 the p99 a healthy step must stay under
   * @param maxErrorPercent the error share a healthy step may not exceed
   * @return the rate, or empty if even the first step missed the target
   */
  public static Optional<Integer> lastHealthyRate(
      List<StepResult> results, Duration targetP99, double maxErrorPercent) {
    Objects.requireNonNull(results, "results");
    Objects.requireNonNull(targetP99, "targetP99");
    double targetMillis = targetP99.toNanos() / (double) Duration.ofMillis(1).toNanos();
    Optional<Integer> lastHealthy = Optional.empty();
    for (StepResult step : results) {
      boolean isHealthy =
          step.latency().hasSamples()
              && step.latency().p99Millis() <= targetMillis
              && step.errorPercent() <= maxErrorPercent;
      if (!isHealthy) {
        return lastHealthy;
      }
      lastHealthy = Optional.of(step.offeredRps());
    }
    return lastHealthy;
  }
```

- `lastHealthyRate` finds the highest offered rate up to which every step met the target. It returns an `Optional<Integer>`: a rate, or empty if even the first step missed.
- `targetMillis` converts the target to milliseconds.
- For each step in rising order, a step is healthy if it has samples, its p99 is at most the target and its error share is at most the allowed share. The first unhealthy step ends the search: `return lastHealthy` gives the rate of the step before it. Stopping at the first failure means a later lucky step cannot make a saturated node look healthy.

**Lines 117 to 139: the census**

```java
  /**
   * Renders a thread census.
   *
   * @param lines the census lines
   * @return one line per group, state and place
   */
  public static String census(List<CensusLine> lines) {
    Objects.requireNonNull(lines, "lines");
    StringBuilder census = new StringBuilder();
    for (CensusLine line : lines) {
      census.append(
          String.format(
              Locale.ROOT,
              "  %5d  %-18s %-14s in %s%n",
              line.threads(),
              line.group(),
              line.state(),
              line.waitingIn()));
    }
    return census.toString();
  }
}
```

- One line for each `CensusLine`: the count, the group, the state and the place. `%-18s` and `%-14s` pad the text to a fixed width so the columns line up.

### `BenchmarkRun.java`

One benchmark run: the same load against a fresh node per thread model, and everything the run prints and writes. Stateless; the command-line class only prints what this returns.

**Lines 1 to 16: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.NodeSettings;
import io.plainround.seatwave.concurrency.node.SeatWaveNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
```

- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `NodeSettings`: the record of every limit of one node.
- `SeatWaveNode`: the class that wires one running node.
- `IOException`: the exception for a failed read, write or socket call.
- `StandardCharsets`: the standard character encodings, such as UTF-8.
- `Files`: static helpers to read and write files.
- `Path`: a file path.
- `SQLException`: the checked exception for a database failure.
- `Clock`: a source of the current time that a test can replace.
- `ArrayList`: a growable list.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Objects`: helpers such as `requireNonNull`.
- `Optional`: a box that holds a value or nothing.

**Lines 17 to 24: class and private constructor**

```java
/**
 * One benchmark run: the same load against a fresh node per thread model, and everything the run
 * prints and writes. Stateless; the command-line class only prints what this returns.
 */
public final class BenchmarkRun {

  private BenchmarkRun() {}
```

- Static methods only, so the constructor is private.

**Lines 25 to 49: running every mode**

```java
  /**
   * Runs every mode in order, each on a fresh node with its own in-memory database.
   *
   * @param settings the run's settings
   * @return every step of every mode, in order
   * @throws SQLException if a node cannot start
   * @throws InterruptedException if interrupted
   */
  public static List<StepResult> runEveryMode(BenchmarkSettings settings)
      throws SQLException, InterruptedException {
    Objects.requireNonNull(settings, "settings");
    List<StepResult> all = new ArrayList<>();
    OpenModelLoad load = new OpenModelLoad(settings);
    for (ExecutorMode mode : settings.modes()) {
      String modeName = mode.name().toLowerCase(Locale.ROOT);
      // Each mode gets a fresh database, so the second run does not find the first run's holds.
      NodeSettings node =
          settings.node().withMode(mode).withDatabaseUrl(settings.node().databaseUrl() + modeName);
      try (SeatWaveNode running = SeatWaveNode.start(node, Clock.systemUTC())) {
        all.addAll(load.runAll(running));
      }
    }
    return List.copyOf(all);
  }
```

- `OpenModelLoad load = new OpenModelLoad(settings)` is made once and used for every mode.
- For each mode, the node settings are copied with `withMode(mode)` and with a database URL that has the mode name added: `settings.node().databaseUrl() + modeName`. The comment says each mode gets a fresh database, so the second run does not find the first run's holds.
- `try (SeatWaveNode running = SeatWaveNode.start(node, Clock.systemUTC()))` starts the node and closes it at the end of the block, even on an exception. `Clock.systemUTC()` is the real clock.
- `all.addAll(load.runAll(running))` adds every step of this mode. The modes run one after the other, never at the same time, so they do not compete for the CPU.

**Lines 50 to 67: the machine line**

```java
  /**
   * Describes the machine, the node and the load, so every printed number carries its setting.
   *
   * @param settings the run's settings
   * @return three lines
   */
  public static String header(BenchmarkSettings settings) {
    Objects.requireNonNull(settings, "settings");
    NodeSettings node = settings.node();
    return String.format(
            Locale.ROOT,
            "Machine: %s %s on %s, %d logical processors, Java %s%n",
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"))
```

- `header` returns three lines that say where and how the numbers were made, so every printed number carries its setting. The first uses `System.getProperty("os.name")`, `"os.version"`, `"os.arch"`, the number of logical processors and `"java.version"`. A **logical processor** is a core, or a hardware thread of a core, that the operating system schedules.
- The three `String.format` calls are joined with `+`, so `header` returns one text of three lines.

**Lines 68 to 78: the node line**

```java
        + String.format(
            Locale.ROOT,
            "Node: %d platform workers, queue bound %d, virtual in-flight bound %d, %d connections,"
                + " borrow limit %d ms, queue wait limit %d ms, database wait %d ms%n",
            node.platformWorkers(),
            node.executorQueueBound(),
            node.virtualMaxInFlight(),
            node.poolConnections(),
            node.poolBorrowLimit().toMillis(),
            node.queueWaitLimit().toMillis(),
            node.databaseWait().toMillis())
```

- The second line prints the node's limits: platform workers, the queue bound, the virtual in-flight bound, the connections, the borrow limit, the queue wait limit and the database wait, each `toMillis()`.

**Lines 79 to 88: the load line**

```java
        + String.format(
            Locale.ROOT,
            "Load: open model, Poisson arrivals, seed %d, warm-up %d ms, steps of %d ms at %s"
                + " holds/s, client timeout %d ms%n",
            settings.randomSeed(),
            settings.warmup().toMillis(),
            settings.stepDuration().toMillis(),
            settings.stepRates(),
            settings.clientTimeout().toMillis());
  }
```

- The third line prints the load: the seed, the warm-up, the step length, the list of rates (`settings.stepRates()` prints as `[200, 320, ...]`) and the client timeout.

**Lines 89 to 111: the table and the censuses**

```java
  /**
   * Renders the table, each thread census, the text chart and the last healthy step of each mode.
   *
   * @param settings the run's settings
   * @param all every step of every mode
   * @return the summary
   */
  public static String summary(BenchmarkSettings settings, List<StepResult> all) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(all, "all");
    StringBuilder summary = new StringBuilder(BenchmarkReport.table(all));
    for (StepResult step : all) {
      if (!step.census().isEmpty()) {
        summary.append(
            String.format(
                Locale.ROOT,
                "%nThread census at %d holds/s, %s mode (platform threads only):%n",
                step.offeredRps(),
                step.mode().name().toLowerCase(Locale.ROOT)));
        summary.append(BenchmarkReport.census(step.census()));
      }
    }
```

- `summary` starts with the table. For every step that has a census, it adds a heading `Thread census at 440 holds/s, platform mode (platform threads only):` and the census lines. `%n` at the start of the template adds a blank line before.

**Lines 112 to 128: the chart and the healthy rates**

```java
    summary.append(System.lineSeparator()).append(BenchmarkReport.textChart(all));
    summary.append(System.lineSeparator());
    for (ExecutorMode mode : settings.modes()) {
      List<StepResult> ofMode = all.stream().filter(step -> step.mode() == mode).toList();
      Optional<Integer> healthy =
          BenchmarkReport.lastHealthyRate(ofMode, settings.targetP99(), settings.maxErrorPercent());
      summary.append(
          String.format(
              Locale.ROOT,
              "%s: last step inside the target (p99 at most %d ms, errors at most %.2f%%): %s%n",
              mode.name().toLowerCase(Locale.ROOT),
              settings.targetP99().toMillis(),
              settings.maxErrorPercent(),
              healthy.map(rate -> rate + " holds/s").orElse("none")));
    }
    return summary.toString();
  }
```

- It appends a blank line and the text chart. Then for each mode: the steps of that mode are picked with `all.stream().filter(step -> step.mode() == mode).toList()` (`Stream.toList()` is Java 16), `lastHealthyRate` finds the last healthy rate, and one line is written: `platform: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 360 holds/s`. `healthy.map(rate -> rate + " holds/s").orElse("none")` turns the `Optional` into text or the word `none`.

**Lines 129 to 148: the file names**

```java
  /**
   * Writes the CSV and the two SVG charts.
   *
   * @param folder the folder; created if missing
   * @param name the run's name, used in every file name
   * @param all every step of every mode
   * @param settings the run's settings, for the target line
   * @return the paths written, in order: CSV, p99 chart, error chart
   * @throws IOException if a file cannot be written
   */
  public static List<Path> writeFiles(
      Path folder, String name, List<StepResult> all, BenchmarkSettings settings)
      throws IOException {
    Objects.requireNonNull(folder, "folder");
    Objects.requireNonNull(name, "name");
    Files.createDirectories(folder);
    Path csv = folder.resolve(name + ".csv");
    Path p99Chart = folder.resolve(name + "-p99.svg");
    Path errorChart = folder.resolve(name + "-errors.svg");
```

- `writeFiles` writes the CSV and the two charts. `Files.createDirectories(folder)` creates the folder and any missing parents, and does nothing if it exists. The three paths are `name.csv`, `name-p99.svg` and `name-errors.svg`, made with `folder.resolve(...)`.

**Lines 149 to 165: writing the files**

```java
    Files.writeString(csv, BenchmarkCsv.render(all), StandardCharsets.UTF_8);
    Files.writeString(
        p99Chart,
        LoadChartSvg.render(
            name + ": hold p99 against load",
            all,
            ChartMetric.P99_MILLIS,
            settings.targetP99().toMillis()),
        StandardCharsets.UTF_8);
    Files.writeString(
        errorChart,
        LoadChartSvg.render(
            name + ": holds without a good reply", all, ChartMetric.ERROR_PERCENT, 0),
        StandardCharsets.UTF_8);
    return List.of(csv, p99Chart, errorChart);
  }
}
```

- `Files.writeString(path, text, StandardCharsets.UTF_8)` writes the whole text to the file in UTF-8, replacing any old file.
- The p99 chart is titled `name + ": hold p99 against load"`, plots `P99_MILLIS` and draws the p99 target as the reference line. The error chart is titled `name + ": holds without a good reply"`, plots `ERROR_PERCENT` and has no reference line (0).
- It returns the three paths in order so the command can print them.

### `BenchmarkCli.java`

The command-line entry point of the benchmark. It is the only class of the package that prints and exits.

**Lines 1 to 9: package and imports**

```java
package io.plainround.seatwave.concurrency.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
```

- `Result`: the result4j type that is either a success value or an error value (see `result4j.md`).
- `SettingsReader`: the class that reads typed values from a settings file.
- `IOException`: the exception for a failed read, write or socket call.
- `Path`: a file path.
- `SQLException`: the checked exception for a database failure.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.

**Lines 10 to 23: constants**

```java
/**
 * Command-line entry point of the benchmark. It runs the same open-model load once per thread
 * model, prints the table, the thread census and a text chart, and writes the CSV and two SVG
 * charts. Exit status 2 means bad usage or settings; 1 means a run failed.
 */
public final class BenchmarkCli {

  private static final int EXIT_RUN_FAILED = 1;
  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final String PROPERTIES_SUFFIX = ".properties";

  private BenchmarkCli() {}
```

- The exit statuses. 0 is success. 1 means a run failed. 2 is the shell convention for "the command was used incorrectly". `PROPERTIES_SUFFIX` is the `.properties` ending that `runName` removes.

**Lines 24 to 35: checking the arguments**

```java
  /**
   * Runs the benchmark.
   *
   * @param arguments the settings file, then the folder for the CSV and SVG files
   */
  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      System.err.println("usage: benchmark <settings.properties> <output folder>");
      System.exit(EXIT_BAD_USAGE);
    }
    Path settingsFile = Path.of(arguments[0]);
```

- `main` is the method the JVM calls to start the program. `String[] arguments` holds what follows the class name on the command line.
- `arguments.length != 2` means the wrong number of arguments, so it prints the usage to `System.err` (the error stream) and `System.exit(2)` ends the process with status 2.
- `Path.of(arguments[0])` makes a path from the first argument, the settings file.

**Lines 36 to 46: reading the settings and choosing**

```java
    try {
      Result<BenchmarkSettings, List<String>> read =
          BenchmarkSettings.from(new SettingsReader(SettingsReader.load(settingsFile)));
      switch (read) {
        case Result.Success<BenchmarkSettings, List<String>>(BenchmarkSettings settings) ->
            run(settings, runName(settingsFile), Path.of(arguments[1]));
        case Result.Error<BenchmarkSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

- `SettingsReader.load(settingsFile)` reads the file into properties, `new SettingsReader(...)` wraps them, and `BenchmarkSettings.from(...)` returns a `Result`.
- `switch (read)` with two record patterns. On `Success`, `run(settings, runName(settingsFile), Path.of(arguments[1]))` does the work. On `Error`, each problem is printed as `invalid setting: ...` and the process exits with status 2. All problems are printed, not only the first.

**Lines 47 to 55: when something fails**

```java
    } catch (IOException | SQLException failed) {
      System.err.println("the benchmark failed: " + failed);
      System.exit(EXIT_RUN_FAILED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the benchmark was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }
```

- `catch (IOException | SQLException failed)`: a **multi-catch** handles two exception types the same way. It prints `the benchmark failed: ...` and exits with 1.
- `catch (InterruptedException interrupted)`: restores the interrupt flag, prints a message and exits with 1.

**Lines 56 to 68: running and printing**

```java
  private static void run(BenchmarkSettings settings, String name, Path folder)
      throws IOException, SQLException, InterruptedException {
    System.out.print(BenchmarkRun.header(settings));
    List<StepResult> all = BenchmarkRun.runEveryMode(settings);
    System.out.println();
    System.out.print(BenchmarkRun.summary(settings, all));
    List<Path> written = BenchmarkRun.writeFiles(folder, name, all, settings);
    System.out.printf(
        Locale.ROOT,
        "%nWrote %s%n",
        String.join(", ", written.stream().map(Path::toString).toList()));
  }
```

- `run` prints the header, runs every mode, prints a blank line and the summary, writes the files, and prints `Wrote` and the three paths. `String.join(", ", written.stream().map(Path::toString).toList())` turns the list of paths into one text separated by commas.

**Lines 69 to 76: the name of the run**

```java
  private static String runName(Path settingsFile) {
    String fileName = settingsFile.getFileName().toString();
    return fileName.endsWith(PROPERTIES_SUFFIX)
        ? fileName.substring(0, fileName.length() - PROPERTIES_SUFFIX.length())
        : fileName;
  }
}
```

- `runName` takes the file name of the settings file and removes `.properties`: `simple.properties` gives `simple`. That is the name used in the three output files.

## Try it yourself

All commands run from the repository folder. Benchmark numbers differ on every run, because the machine, its other work and the timing differ. Compare the shape, not the digits.

**1. Run the shortest benchmark.** `config/unbounded.properties` runs one mode (platform) at two rates (440 and 600 holds per second) and takes about 30 seconds.

```text
./gradlew -q benchmark --args='config/unbounded.properties build/bench'
```

My run printed these three header lines (the machine, the node and the load):

```text
Machine: Mac OS X 27.2 on aarch64, 12 logical processors, Java 25.0.2
Node: 64 platform workers, queue bound 100000, virtual in-flight bound 184, 16 connections, borrow limit 60000 ms, queue wait limit 60000 ms, database wait 40 ms
Load: open model, Poisson arrivals, seed 20261110, warm-up 3000 ms, steps of 10000 ms at [440, 600] holds/s, client timeout 2000 ms
```

Then the table, with `p99 ms` 1967.7 and 1980.6, `errors%` 0.80 and 69.20, and `503q` and `503p` both 0 (nothing refused). Then the thread census, the text chart and this last line:

```text
platform: last step inside the target (p99 at most 300 ms, errors at most 0.05%): none
```

`none` means even the first step missed the target. The files land in `build/bench/`.

**2. A bad settings file.** I copied `config/simple.properties`, wrote `abc` in the step list (`load.steps.rps=200,abc`) and deleted the `pool.connections` line. The benchmark printed every problem, not only the first, and exited with status 2 before it started anything:

```text
invalid setting: load.steps.rps must list whole numbers, but one was abc
invalid setting: pool.connections is missing
```

**3. Wrong usage.** `./gradlew -q benchmark` with no arguments printed `usage: benchmark <settings.properties> <output folder>`. Gradle then reports that the Java process finished with a non-zero exit value (2).

**4. The longer runs.** The three runs in the repository `README.md` (`simple`, `wide-pool`, `production`) take two to three minutes each. They are the ones that show the p99 against the load for both thread models.

## Mistakes people make

- **A closed load generator.** It slows down when the server does, so it never shows the overload that real fans create. `OpenModelLoad` plans the arrival moments first and sends at them.
- **Measuring from the actual send time.** That hides a late send. Here the latency starts at the planned time.
- **No warm-up.** The first seconds run before the JIT compiler has compiled the hot path. `runAll` runs and discards a warm-up step.
- **Reporting an average.** An average hides the slow end. The report prints p50 and p99, and the target is a p99.
- **One run called the answer.** Each result here is one run per mode, as the repository `README.md` says. A capacity plan repeats runs and reads the spread.
- **Ignoring the benchmark machine.** The generator and the node share one JVM, so the `cpu%` column includes the generator.
- **Believing numbers from a node that hung.** `awaitDrain` stops the run if any hold outlives the longest legal time.

## Check yourself

1. **Why is a step's count of holds fixed at rate x duration, while the moments are random?** The count is fixed so that every step offers exactly its rate and two steps can be compared. The moments are random so the holds bunch up as real fans do.
2. **A hold is planned for second 4.000 but the generator sends it at 4.020 because the machine was busy. Where does the 20 ms go?** Into the latency, because the latency is measured from the planned time. The node is charged for the delay. This is the cure for coordinated omission.
3. **How is the p99 of 4,000 durations computed?** Sort them. The rank is ceiling(0.99 x 4000) = 3,960. The answer is the 3,960th smallest.
4. **What does `StepResult` refuse, and why?** Buckets that do not add up to the holds sent: every hold must end in exactly one bucket, so a lost or double-counted hold stops the run.
5. **Why does the census find virtual-thread work only as `ForkJoinPool-` threads?** `Thread.getAllStackTraces()` does not list virtual threads. It lists the platform carrier threads, which are named `ForkJoinPool-...`.

## Further reading

Official documentation only, for JDK 25:

- `Thread`, including `getAllStackTraces`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html
- The `java.util.concurrent` package (`CompletableFuture`, `ScheduledExecutorService`, `LongAdder`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html
- Virtual threads, JEP 444: https://openjdk.org/jeps/444
