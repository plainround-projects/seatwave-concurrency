# Java walkthrough: the `sizing`, `threads` and `config` packages

## What it is

This file reads, one source file at a time, three small packages of `io.plainround.seatwave.concurrency`:

- `config` (one file): `SettingsReader`, which reads typed values from a settings file.
- `sizing` (four files): Little's Law as code, the inputs it needs, a report that shows every step of the arithmetic, and the command that runs it.
- `threads` (four files): a measurement of what many waiting threads cost, a demo of when a virtual thread blocks its carrier, and the two commands that run them.

Read `java.md` and `java-concurrency.md` first. The `java-properties.md` file lists the keys of the settings files these programs read.

## Why this repository uses it

Module 4 asks how to size a node's pools and what a thread costs. These packages answer with numbers you produce yourself, on your own machine.

- **Little's Law** says: the average number of requests inside a system equals their arrival rate times the average time each spends inside. `500 holds per second x 0.040 seconds = 20 holds in flight`. That one multiplication sizes a connection pool, a platform thread pool and a CPU budget. `sizing` shows each step of it.
- **The utilization curve** says waiting does not grow in a straight line: it is small until a pool is mostly busy, then it explodes. The report prints it as the factor `u / (1 - u)` at 50, 80, 90, 95 and 99 percent.
- **What a thread costs.** A platform thread has its own stack and is scheduled by the operating system. A virtual thread is a small object managed by the JVM. `threadCost` starts thousands of each and measures the time and the heap.
- **Carrier pinning.** A virtual thread runs on top of a platform thread called its **carrier**. When it blocks, the JVM normally unmounts it so the carrier can run another. In a few cases the carrier stays stuck, which is called **pinning**. `pinning` shows one case on JDK 25.
- **No defaults.** The programs read their numbers from files. `SettingsReader` refuses a missing key, so two runs can never differ by an unseen default.

## Where it appears

| File | What it does |
|---|---|
| `config/SettingsReader.java` | Loads a `.properties` file as UTF-8 and reads typed values, collecting every problem. |
| `sizing/LittlesLaw.java` | Four formulas: requests in flight, pool size, capacity, waiting factor. |
| `sizing/SizingInputs.java` | The eight numbers a node is sized from, read from `config/sizing.properties`. |
| `sizing/SizingReport.java` | Prints every step of the sizing with the arithmetic visible. |
| `sizing/SizingCli.java` | The `sizing` command. |
| `threads/ThreadCost.java` | Starts many waiting threads and measures time and heap. |
| `threads/ThreadCostCli.java` | The `threadCost` command. |
| `threads/CarrierPinning.java` | The pinning demo. |
| `threads/PinningCli.java` | The `pinning` command. |

## How it works underneath

**Little's Law.** Picture a queue at a counter. If 10 people arrive every minute and each spends 3 minutes inside (waiting plus being served), then on average 30 people are inside. In symbols, `L = lambda x W`: `L` is the number inside, `lambda` the arrival rate, `W` the average time inside. It holds for any system in a steady state, whatever the order of service and the shape of the arrivals. For SeatWave: 500 holds per second, each keeping a connection for 40 ms, means 500 x 0.040 = 20 connections in use on average.

**Sizing with a ceiling.** If the pool had exactly 20 connections, it would be 100 percent busy on average, and the waiting would explode (see below). So the pool is sized so its average use stays at or under a **ceiling**, here 80 percent: `20 / 0.80 = 25` connections. The capacity of 25 connections is `25 / 0.040 s = 625` holds per second, and at 500 holds per second the pool is 500 / 625 = 80 percent busy.

**Why waiting explodes.** For a single server with random arrivals, the average wait is about `u / (1 - u)` times the service time, where `u` (utilization) is the share of capacity in use. At 50 percent the factor is 1. At 80 percent it is 4. At 90 percent, 9. At 95 percent, 19. At 99 percent, 99. The curve is flat, then vertical. This is the "cliff" the benchmark shows in the p99 column.

**What the JVM keeps for a thread.** A platform thread has a stack that the operating system reserves. On this machine the default is 2,048 KiB per thread (`java -XX:+PrintFlagsFinal -version` printed `ThreadStackSize = 2048`). Only the touched part becomes real memory, and it lives outside the Java heap. A virtual thread keeps its stack on the heap, as ordinary objects, and grows it only as needed.

**Platform and virtual threads, carriers.** The JVM runs virtual threads on a small pool of platform threads called **carriers**, by default as many as there are processors. When a virtual thread blocks (in `Thread.sleep`, a lock or a socket read), the JVM saves its stack to the heap and frees the carrier for another virtual thread. This is **unmounting**. When the wait ends, the virtual thread is mounted on some carrier again.

**Pinning on JDK 25.** A virtual thread is **pinned** when it blocks but cannot unmount: its carrier is stuck too. Before JDK 24, blocking inside a `synchronized` block pinned the carrier. Since JDK 24 (JEP 491) it no longer does. I checked on JDK 25.0.2: 12 virtual threads each slept 500 ms inside `synchronized`, then I started an unrelated virtual task, and it started after 0.6 ms, so the carriers were free. Some cases still pin; the one `CarrierPinning` uses is blocking inside a **class initializer**, the code that runs once when a class is first used. In the real run below, the unrelated task waited about 500 ms, the whole block time.

**Where the numbers come from.** All numbers in the examples below are from one Apple-silicon Mac with 12 logical processors and Eclipse Temurin 25.0.2. Yours will differ.

## Walkthrough of this repository's use

### `SettingsReader.java`

Reads typed values from a settings file and collects one problem line per missing or invalid key, so a learner sees every mistake in one run.

**Lines 1 to 13: package and imports**

```java
package io.plainround.seatwave.concurrency.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
```

- `IOException`: the exception for a failed read, write or socket call.
- `Reader`: the general source of characters.
- `StandardCharsets`: the standard character encodings, such as UTF-8.
- `Files`: static helpers to read and write files.
- `Path`: a file path.
- `Duration`: a length of time.
- `ArrayList`: a growable list.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Objects`: helpers such as `requireNonNull`.
- `Properties`: a map of text to text that can load a `.properties` file.

**Lines 14 to 27: the class comment and fields**

```java
/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a learner sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
public final class SettingsReader {

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();
```

- The class comment says three things. There are no defaults: an absent key is a problem, never a silent fallback. When a key is invalid, the method records the problem and returns a **harmless stand-in** value, and the caller must check `problems()` before using any value. It is not thread-safe: one reader is used by one thread while it builds one settings record.
- `Properties properties` is the loaded file, a map from text to text. `List<String> problems` is the problem lines found so far, in order.

**Lines 28 to 36: the constructor**

```java
  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }
```

- Wraps properties that are already loaded. A null is refused with `requireNonNull`. A reader that takes the `Properties` and not a path is easy to test with values made in memory.

**Lines 37 to 52: loading a file**

```java
  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file cannot be read
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
```

- `load` is a static method: no reader is needed to load a file. It makes an empty `Properties`.
- `try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))` opens the file for reading as UTF-8 text and closes it at the end of the block. `Files.newBufferedReader` returns a reader that collects characters in a buffer.
- `properties.load(reader)` reads the `key=value` lines. Passing a `Reader` makes the characters UTF-8. Passing a raw byte stream would decode the file as an older encoding. See `java-properties.md`.
- `throws IOException`: the file may not exist or may not be readable. The command classes catch it and print `cannot read <file>`.

**Lines 53 to 82: a whole number in a range**

```java
  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }
```

- `text(key)` gets the trimmed text. If the key is missing or blank, `text` has recorded `<key> is missing` and returned an empty string, so `intBetween` returns the minimum without adding a second problem.
- `Integer.parseInt(text)` converts text to an `int`. It throws `NumberFormatException` for `2,000`, for `abc` and for a number too large for an `int`. A value outside the range records `<key> must be from <min> to <max>, but was <value>`, with `String.format(Locale.ROOT, ...)`, and returns the minimum.
- `catch (NumberFormatException notANumber)` records `<key> must be a whole number, but was <text>`. The comment: a typo is an expected failure of a settings file, so it becomes a problem line, not a crash.

**Lines 83 to 110: a decimal number in a range**

```java
  /**
   * Reads a decimal number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public double decimalBetween(String key, double minimum, double maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      double value = Double.parseDouble(text);
      if (!(value >= minimum && value <= maximum)) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %s to %s, but was %s", key, minimum, maximum, text));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      problems.add(key + " must be a decimal number, but was " + text);
      return minimum;
    }
  }
```

- The same shape with `Double.parseDouble`. The test is written `!(value >= minimum && value <= maximum)` and not `value < minimum || value > maximum`, so that **NaN** (not a number) is also refused: every comparison with NaN is false, so the first form is true for it. The message prints the original text so a reader sees exactly what was in the file. `%s` is a place for text.

**Lines 111 to 143: a list of whole numbers**

```java
  /**
   * Reads a comma-separated list of whole numbers, each inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the values in file order, or an empty list if the key is missing or invalid
   */
  public List<Integer> intListBetween(String key, int minimum, int maximum) {
    List<Integer> values = new ArrayList<>();
    for (String item : items(key)) {
      try {
        int value = Integer.parseInt(item);
        if (value < minimum || value > maximum) {
          problems.add(
              String.format(
                  Locale.ROOT,
                  "every value of %s must be from %d to %d, but one was %d",
                  key,
                  minimum,
                  maximum,
                  value));
          return List.of();
        }
        values.add(value);
      } catch (NumberFormatException notANumber) {
        problems.add(key + " must list whole numbers, but one was " + item);
        return List.of();
      }
    }
    return List.copyOf(values);
  }
```

- `items(key)` splits the value at commas into trimmed items (below). Each item is parsed and range-checked. On any bad item, the problem is recorded and the method returns an empty list at once. `List.of()` is an empty unchangeable list.
- The format `"every value of %s must be from %d to %d, but one was %d"` names the key and the bad number. At the end, `List.copyOf(values)` returns an unchangeable list in file order.

**Lines 144 to 155: a duration in milliseconds**

```java
  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code .millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }
```

- `Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis))` reuses `intBetween` and wraps the number as a `Duration`. By convention the key ends in `.millis`, so the unit is in the name.

**Lines 156 to 177: a list of enum constants**

```java
  /**
   * Reads a comma-separated list of enum constants, written in lower case.
   *
   * @param key the key
   * @param type the enum class
   * @param <E> the enum type
   * @return the constants in file order, or an empty list if the key is missing or invalid
   */
  public <E extends Enum<E>> List<E> enumList(String key, Class<E> type) {
    Objects.requireNonNull(type, "type");
    List<E> values = new ArrayList<>();
    for (String item : items(key)) {
      try {
        values.add(Enum.valueOf(type, item.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException unknown) {
        problems.add(key + " has an unknown value: " + item);
        return List.of();
      }
    }
    return List.copyOf(values);
  }
```

- `<E extends Enum<E>> List<E> enumList(String key, Class<E> type)` is a **generic method**: it works for any enum type `E`. The caller passes the enum class, for example `ExecutorMode.class`, and gets a `List<ExecutorMode>`.
- `Enum.valueOf(type, item.toUpperCase(Locale.ROOT))` finds the constant by name. The file writes `platform`, the enum has `PLATFORM`, so the text is upper-cased first. `Locale.ROOT` makes the case change independent of the machine's language: in a Turkish locale, `i` upper-cases to a dotted `I`, which would break names.
- An unknown name throws `IllegalArgumentException`, which is caught and recorded as `<key> has an unknown value: <item>`.

**Lines 178 to 193: a text value**

```java
  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }
```

- `properties.getProperty(key)` returns the value, or `null` if the key is absent. A null or blank value records `<key> is missing` and returns an empty string. Otherwise `value.strip()` removes the spaces at both ends, because a trailing space in a properties file is kept as part of the value.

**Lines 194 to 202: the problems**

```java
  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
```

- Returns an unchangeable copy of the list, so the caller cannot change the reader's record.

**Lines 203 to 219: splitting a list**

```java
  private List<String> items(String key) {
    String text = text(key);
    if (text.isEmpty()) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : text.split(",", -1)) {
      if (item.isBlank()) {
        problems.add(key + " has an empty item");
        return List.of();
      }
      items.add(item.strip());
    }
    return items;
  }
}
```

- `text.split(",", -1)` cuts at every comma. The `-1` keeps empty trailing pieces, so a value such as `1,2,` gives three items and the empty one is reported. A blank item records `<key> has an empty item` and the method returns an empty list.
- Each good item is stripped of spaces, so `200, 320` works.

### `LittlesLaw.java`

Little's Law and the sizing rules built on it. Four static methods; no object is ever made.

**Lines 1 to 4: package and imports**

```java
package io.plainround.seatwave.concurrency.sizing;

import java.time.Duration;
import java.util.Objects;
```

- `Duration`: a length of time.
- `Objects`: helpers such as `requireNonNull`.

**Lines 5 to 17: the class comment, constant and private constructor**

```java
/**
 * Little's Law and the sizing rules built on it. Stateless and thread-safe.
 *
 * <p>Little's Law: the average number of requests in flight equals the arrival rate times the
 * average time each one spends inside. It holds for any queue in a steady state, whatever the order
 * of service or the shape of the arrivals.
 */
public final class LittlesLaw {

  private static final double NANOS_PER_SECOND = 1e9;

  private LittlesLaw() {}
```

- The class comment states the law: the average number of requests in flight equals the arrival rate times the average time each spends inside. It holds in a steady state whatever the order of service or the shape of the arrivals.
- `NANOS_PER_SECOND = 1e9` is one billion, as a `double`.
- `private LittlesLaw() {}` is an empty private constructor, so no object can be made: the class is only a place for functions.

**Lines 18 to 32: requests in flight**

```java
  /**
   * Computes the requests in flight: concurrency = throughput x latency.
   *
   * @param ratePerSecond the arrival rate, above 0
   * @param timeInside the average time each request spends inside, not negative
   * @return the average requests in flight
   */
  public static double inFlight(double ratePerSecond, Duration timeInside) {
    Objects.requireNonNull(timeInside, "timeInside");
    if (!(ratePerSecond > 0) || timeInside.isNegative()) {
      throw new IllegalArgumentException("the rate must be positive and the time not negative");
    }
    return ratePerSecond * timeInside.toNanos() / NANOS_PER_SECOND;
  }
```

- `inFlight(rate, timeInside)` is `concurrency = throughput x latency`.
- `Objects.requireNonNull(timeInside, ...)` refuses null. The test `!(ratePerSecond > 0) || timeInside.isNegative()` refuses a rate that is zero, negative or NaN, and a negative time. Writing `!(x > 0)` and not `x <= 0` also catches NaN.
- `ratePerSecond * timeInside.toNanos() / NANOS_PER_SECOND` multiplies the rate by the time in nanoseconds and divides by a billion to get seconds. For 500 and 40 ms: 500 x 40,000,000 / 1,000,000,000 = 20.0.

**Lines 33 to 49: pool size**

```java
  /**
   * Sizes a pool so its average use stays at or under a ceiling: the in-flight count divided by the
   * ceiling, rounded up.
   *
   * @param ratePerSecond the arrival rate, above 0
   * @param timeHeld how long each request holds one member of the pool
   * @param utilizationCeiling the highest average share of the pool in use, above 0 and at most 1
   * @return the pool size, at least 1
   */
  public static int poolSize(double ratePerSecond, Duration timeHeld, double utilizationCeiling) {
    if (!(utilizationCeiling > 0 && utilizationCeiling <= 1)) {
      throw new IllegalArgumentException(
          "the ceiling is above 0 and at most 1: " + utilizationCeiling);
    }
    return Math.max(1, (int) Math.ceil(inFlight(ratePerSecond, timeHeld) / utilizationCeiling));
  }
```

- `poolSize(rate, timeHeld, ceiling)` is the in-flight count divided by the ceiling, rounded up. The ceiling is a share from above 0 up to 1 (`0.80` for 80 percent). `!(ceiling > 0 && ceiling <= 1)` refuses anything else.
- `Math.ceil(...)` rounds up to a whole number: 25.0 stays 25, and 20.5 would become 21. `(int)` converts the result to an `int`. `Math.max(1, ...)` makes sure a pool has at least one member.

**Lines 50 to 64: capacity**

```java
  /**
   * Computes the most a pool can serve: its size divided by the time each request holds a member.
   *
   * @param poolSize the members of the pool, at least 1
   * @param timeHeld how long each request holds one member, above 0
   * @return the capacity in requests per second
   */
  public static double capacityPerSecond(int poolSize, Duration timeHeld) {
    Objects.requireNonNull(timeHeld, "timeHeld");
    if (poolSize < 1 || timeHeld.isNegative() || timeHeld.isZero()) {
      throw new IllegalArgumentException("the pool needs 1 or more members and a positive time");
    }
    return poolSize * NANOS_PER_SECOND / timeHeld.toNanos();
  }
```

- `capacityPerSecond(poolSize, timeHeld)` is the most a pool can serve: its size divided by the time each request holds a member. `25 x 1e9 / 40,000,000 = 625` holds per second. A pool below 1, and a time of zero or less, are refused (a zero time would divide by zero).

**Lines 65 to 78: the waiting factor**

```java
  /**
   * Computes the queueing rule of thumb from Module 1: waiting grows like u / (1 - u).
   *
   * @param utilization the share of capacity in use, from 0 up to but not including 1
   * @return the waiting factor: the average wait as a multiple of the service time, for one server
   */
  public static double waitingFactor(double utilization) {
    if (!(utilization >= 0 && utilization < 1)) {
      throw new IllegalArgumentException("utilization is from 0 to below 1: " + utilization);
    }
    return utilization / (1 - utilization);
  }
}
```

- `waitingFactor(utilization)` is `u / (1 - u)`. `utilization` must be from 0 up to but not including 1, since at 1 the formula divides by zero: a server that is always busy has an unbounded wait.
- The Javadoc says it is the average wait as a multiple of the service time, for one server.

### `SizingInputs.java`

The numbers one node is sized from (Module 4, Section 7), read from a settings file with no defaults. A record.

**Lines 1 to 7: package and imports**

```java
package io.plainround.seatwave.concurrency.sizing;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
```

- `Result`: the result4j type that is either a success value or an error value (see `result4j.md`).
- `SettingsReader`: the class that reads typed values from a settings file.
- `Duration`: a length of time.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.

**Lines 8 to 30: the header**

```java
/**
 * The numbers one node is sized from (Module 4, Section 7). Read from a settings file with no
 * defaults.
 *
 * @param holdsPerSecond the hold rate at the busiest moment
 * @param holdTime how long each hold keeps a thread and a connection
 * @param databaseMaxConnections the connections the database allows this node
 * @param seatMapsPerSecond the seat-map rate at the busiest moment
 * @param seatMapCpu the CPU time one seat map costs
 * @param vcpus the node's vCPUs
 * @param poolCeilingPercent the highest average use of a pool before it is too close to its limit
 * @param cpuTargetPercent the planned share of CPU at peak (Modules 2 and 3)
 */
public record SizingInputs(
    int holdsPerSecond,
    Duration holdTime,
    int databaseMaxConnections,
    int seatMapsPerSecond,
    Duration seatMapCpu,
    int vcpus,
    int poolCeilingPercent,
    int cpuTargetPercent) {
```

- Eight components: `holdsPerSecond` (the hold rate at the busiest moment), `holdTime` (how long each hold keeps a thread and a connection), `databaseMaxConnections` (what the database allows this node), `seatMapsPerSecond` and `seatMapCpu` (the seat-map rate and the CPU time one seat map costs), `vcpus` (the node's processors), `poolCeilingPercent` (the highest average use of a pool) and `cpuTargetPercent` (the planned CPU share at peak).
- A **vCPU** is a virtual CPU: one hardware thread that a cloud machine gives you.

**Lines 31 to 35: the limits**

```java
  private static final int MAX_RATE = 1_000_000;
  private static final int MAX_MILLIS = 60_000;
  private static final int MAX_COUNT = 100_000;
  private static final int MAX_PERCENT = 100;
```

- Caps for the settings: at most 1,000,000 per second, 60,000 ms, 100,000 for a count, and 100 for a percent.

**Lines 36 to 60: the compact constructor**

```java
  /**
   * Creates the inputs.
   *
   * @throws IllegalArgumentException if a value is outside its range
   */
  public SizingInputs {
    Objects.requireNonNull(holdTime, "holdTime");
    Objects.requireNonNull(seatMapCpu, "seatMapCpu");
    if (holdsPerSecond < 1 || seatMapsPerSecond < 1 || databaseMaxConnections < 1 || vcpus < 1) {
      throw new IllegalArgumentException("rates and counts must be 1 or more");
    }
    if (holdTime.isZero()
        || holdTime.isNegative()
        || seatMapCpu.isZero()
        || seatMapCpu.isNegative()) {
      throw new IllegalArgumentException("times must be positive");
    }
    if (poolCeilingPercent < 1 || poolCeilingPercent > MAX_PERCENT) {
      throw new IllegalArgumentException("the pool ceiling is 1 to 100 percent");
    }
    if (cpuTargetPercent < 1 || cpuTargetPercent > MAX_PERCENT) {
      throw new IllegalArgumentException("the CPU target is 1 to 100 percent");
    }
  }
```

- Two null checks. Then rates and counts below 1 are refused, times of zero or less are refused, and each percent must be 1 to 100. `||` means "or"; the long `if` on lines 48 to 51 is written one condition per line.
- A zero or negative time would make the formulas divide by zero or go negative.

**Lines 61 to 77: reading the keys**

```java
  /**
   * Reads the inputs.
   *
   * @param reader the settings reader
   * @return the inputs, or one line per missing or invalid key
   */
  public static Result<SizingInputs, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int holds = reader.intBetween("hold.rate.per.second", 1, MAX_RATE);
    Duration holdTime = reader.millisBetween("hold.time.millis", 1, MAX_MILLIS);
    int connections = reader.intBetween("database.max.connections", 1, MAX_COUNT);
    int seatMaps = reader.intBetween("seatmap.rate.per.second", 1, MAX_RATE);
    Duration seatMapCpu = reader.millisBetween("seatmap.cpu.millis", 1, MAX_MILLIS);
    int vcpus = reader.intBetween("node.vcpus", 1, MAX_COUNT);
    int poolCeiling = reader.intBetween("pool.utilization.ceiling.percent", 1, MAX_PERCENT);
    int cpuTarget = reader.intBetween("cpu.utilization.target.percent", 1, MAX_PERCENT);
```

- `from(reader)` returns a `Result<SizingInputs, List<String>>`. The eight keys are read with `intBetween` and `millisBetween`. `millisBetween("hold.time.millis", 1, MAX_MILLIS)` returns a `Duration`.

**Lines 78 to 85: building the record**

```java
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new SizingInputs(
            holds, holdTime, connections, seatMaps, seatMapCpu, vcpus, poolCeiling, cpuTarget));
  }
}
```

- If the reader has any problem, return them all as an error. Otherwise `Result.success(new SizingInputs(...))` builds the record. Because every problem is checked first, the constructor never sees a stand-in value.

### `SizingReport.java`

Prints every step of sizing one node, with the arithmetic visible. Stateless.

**Lines 1 to 6: package and imports**

```java
package io.plainround.seatwave.concurrency.sizing;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
```

- `Duration`: a length of time.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Objects`: helpers such as `requireNonNull`.

**Lines 7 to 16: constants**

```java
/** Prints every step of sizing one node, with the arithmetic visible. Stateless. */
public final class SizingReport {

  private static final double HUNDRED_PERCENT = 100.0;
  private static final double MILLIS_PER_SECOND = 1000.0;
  // The utilizations of Figure 2 in the module.
  private static final List<Integer> CLIFF_POINTS_PERCENT = List.of(50, 80, 90, 95, 99);

  private SizingReport() {}
```

- `HUNDRED_PERCENT` and `MILLIS_PER_SECOND` avoid bare numbers. `CLIFF_POINTS_PERCENT` is the list 50, 80, 90, 95, 99 (the utilizations of Figure 2 in the module).

**Lines 17 to 39: rendering**

```java
  /**
   * Renders the sizing.
   *
   * @param inputs the node's numbers
   * @return the report, one step per line
   */
  public static String render(SizingInputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    StringBuilder report = new StringBuilder();
    holds(report, inputs);
    seatMaps(report, inputs);
    report.append("WAITING FACTOR u / (1 - u), one server\n ");
    for (int percent : CLIFF_POINTS_PERCENT) {
      report.append(
          String.format(
              Locale.ROOT,
              "  %d %%: %.1f",
              percent,
              LittlesLaw.waitingFactor(percent / HUNDRED_PERCENT)));
    }
    return report.append('\n').toString();
  }
```

- `render(inputs)` makes a `StringBuilder`, calls `holds` and `seatMaps` to add their lines, and then adds the waiting-factor line.
- `report.append("WAITING FACTOR u / (1 - u), one server\n ")` writes the heading and a newline and a space. The loop then appends `"  %d %%: %.1f"` for each of the five utilizations: two spaces, the percent (`%%` prints a percent sign), a colon and the factor with one decimal. The result is one line: `   50 %: 1.0  80 %: 4.0  90 %: 9.0  95 %: 19.0  99 %: 99.0`.
- `return report.append('\n').toString()` ends the line and returns the text.

**Lines 40 to 46: the holds section: setup**

```java
  private static void holds(StringBuilder report, SizingInputs inputs) {
    double ceiling = inputs.poolCeilingPercent() / HUNDRED_PERCENT;
    double inFlight = LittlesLaw.inFlight(inputs.holdsPerSecond(), inputs.holdTime());
    int pool = LittlesLaw.poolSize(inputs.holdsPerSecond(), inputs.holdTime(), ceiling);
    double capacity = LittlesLaw.capacityPerSecond(pool, inputs.holdTime());
    report.append("HOLDS\n");
```

- `ceiling` is the percent as a share (80 becomes 0.80). `inFlight` is `LittlesLaw.inFlight(500, 40 ms)` = 20.0. `pool` is the pool size, 25. `capacity` is the pool's capacity, 625 per second. `"HOLDS\n"` starts the section.

**Lines 47 to 52: the holds section: in flight**

```java
    line(
        report,
        "In flight (Little's Law): %,d holds/s x %.3f s = %.1f holds",
        inputs.holdsPerSecond(),
        seconds(inputs.holdTime()),
        inFlight);
```

- `line(report, "In flight (Little's Law): %,d holds/s x %.3f s = %.1f holds", ...)` writes a line with `%,d` (a whole number with thousands separators), `%.3f` (three decimals) and `%.1f`. The values are the rate, the hold time in seconds, and the result. The printed line is `In flight (Little's Law): 500 holds/s x 0.040 s = 20.0 holds`.

**Lines 53 to 62: the holds section: the connection pool**

```java
    line(
        report,
        "Connection pool, ceiling %d %%: ceiling of %.1f / %.2f = %d connections;"
            + " the database allows %d: %s",
        inputs.poolCeilingPercent(),
        inFlight,
        ceiling,
        pool,
        inputs.databaseMaxConnections(),
        pool <= inputs.databaseMaxConnections() ? "fits" : "DOES NOT FIT");
```

- The format is written as two strings joined by `+` to keep each source line short. It prints the ceiling, the in-flight count divided by the ceiling, the pool size, and what the database allows.
- `pool <= inputs.databaseMaxConnections() ? "fits" : "DOES NOT FIT"` chooses the last word. The conditional operator picks one of two values.

**Lines 63 to 71: the holds section: capacity**

```java
    line(
        report,
        "Capacity of %d connections: %d / %.3f s = %,.0f holds/s; at %,d holds/s the pool is %.0f %% busy",
        pool,
        pool,
        seconds(inputs.holdTime()),
        capacity,
        inputs.holdsPerSecond(),
        inputs.holdsPerSecond() * HUNDRED_PERCENT / capacity);
```

- Prints the capacity of the pool as `pool / time = capacity`, and the pool's busy share at the planned rate: `holdsPerSecond x 100 / capacity` = 80 percent. `%,.0f` is a decimal with no digits after the point and thousands separators. `%.0f %%` ends the line with a percent sign.

**Lines 72 to 76: the holds section: platform threads**

```java
    line(
        report,
        "Platform worker pool, same rule: %d threads, each blocked for the %.0f ms of every hold",
        pool,
        inputs.holdTime().toNanos() / (double) Duration.ofMillis(1).toNanos());
```

- The same size applies to a platform pool: 25 threads, each blocked for the 40 ms of every hold. `inputs.holdTime().toNanos() / (double) Duration.ofMillis(1).toNanos()` turns the duration into milliseconds as a decimal.

**Lines 77 to 82: the holds section: virtual threads**

```java
    line(
        report,
        "Virtual threads: one per hold, %.1f alive on average; the pool, not the threads, sets the"
            + " limit",
        inFlight);
  }
```

- Virtual threads: one per hold, 20.0 alive on average. The line ends with "the pool, not the threads, sets the limit": a virtual thread is so cheap that the connection pool, not the thread count, is what limits the node.

**Lines 83 to 88: the seat-map section: setup**

```java
  private static void seatMaps(StringBuilder report, SizingInputs inputs) {
    double busyCores = LittlesLaw.inFlight(inputs.seatMapsPerSecond(), inputs.seatMapCpu());
    double knee = LittlesLaw.capacityPerSecond(inputs.vcpus(), inputs.seatMapCpu());
    double safe = knee * inputs.cpuTargetPercent() / HUNDRED_PERCENT;
    report.append("SEAT MAPS\n");
```

- `busyCores` is Little's Law applied to CPU: 2,000 seat maps per second x 0.005 s of CPU each = 10.0 cores busy. `knee` is the capacity of the cores: `vcpus / cpuTime` = 4 / 0.005 s = 800 seat maps per second. `safe` is the knee times the CPU target (70 percent) = 560.

**Lines 89 to 95: the seat-map section: CPU busy**

```java
    line(
        report,
        "CPU busy (Little's Law on cores): %,d seat maps/s x %.3f s = %.1f vCPUs busy; the node has %d",
        inputs.seatMapsPerSecond(),
        seconds(inputs.seatMapCpu()),
        busyCores,
        inputs.vcpus());
```

- Prints `2,000 seat maps/s x 0.005 s = 10.0 vCPUs busy; the node has 4`.

**Lines 96 to 104: the seat-map section: utilization**

```java
    line(
        report,
        "Utilization: %.1f / %d = %.0f %%: %s",
        busyCores,
        inputs.vcpus(),
        busyCores * HUNDRED_PERCENT / inputs.vcpus(),
        busyCores <= inputs.vcpus()
            ? "fits on one node"
            : "one node cannot serve this; more threads add no CPU");
```

- The utilization is busy cores divided by cores: 10.0 / 4 = 250 percent. The last word depends on whether it fits: `fits on one node`, or `one node cannot serve this; more threads add no CPU`. This is the point of the section: a thread waiting for the CPU does not make the CPU bigger.

**Lines 105 to 112: the seat-map section: the knee**

```java
    line(
        report,
        "One node's CPU knee: %d / %.3f s = %,.0f seat maps/s; safe at %d %%: %,.0f seat maps/s",
        inputs.vcpus(),
        seconds(inputs.seatMapCpu()),
        knee,
        inputs.cpuTargetPercent(),
        safe);
```

- Prints the knee, 800 seat maps per second, and the safe rate at the CPU target, 560.

**Lines 113 to 119: the seat-map section: nodes**

```java
    line(
        report,
        "Nodes for seat maps: ceiling of %,d / %,.0f = %d nodes",
        inputs.seatMapsPerSecond(),
        safe,
        (int) Math.ceil(inputs.seatMapsPerSecond() / safe));
  }
```

- `(int) Math.ceil(seatMapsPerSecond / safe)` is the number of nodes: 2,000 / 560 = 3.57, rounded up to 4.

**Lines 120 to 123: seconds**

```java
  private static double seconds(Duration duration) {
    return duration.toNanos() / (MILLIS_PER_SECOND * Duration.ofMillis(1).toNanos());
  }
```

- Converts a `Duration` to seconds as a decimal: nanoseconds divided by `1000 x 1,000,000`.

**Lines 124 to 128: one printed line**

```java
  private static void line(StringBuilder report, String format, Object... values) {
    report.append("  ").append(String.format(Locale.ROOT, format, values)).append('\n');
  }
}
```

- `line(report, format, values...)` is a helper. `Object... values` is a **varargs** parameter: the caller may pass any number of values, and inside the method they are an array. `String.format(Locale.ROOT, format, values)` fills the template. The line starts with two spaces and ends with a newline.

### `SizingCli.java`

The command-line entry point of the sizing step. Exit status 2 means bad usage or settings.

**Lines 1 to 7: package and imports**

```java
package io.plainround.seatwave.concurrency.sizing;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
```

- `Result`: the result4j type that is either a success value or an error value (see `result4j.md`).
- `SettingsReader`: the class that reads typed values from a settings file.
- `IOException`: the exception for a failed read, write or socket call.
- `Path`: a file path.
- `List`: an ordered collection.

**Lines 8 to 18: constants**

```java
/**
 * Command-line entry point of the sizing step: reads the node's numbers and prints the Little's Law
 * size of each pool. Exit status 2 means bad usage or settings.
 */
public final class SizingCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;

  private SizingCli() {}
```

- `EXIT_BAD_USAGE = 2`. The comment: exit status 2 is the shell convention for "the command was used incorrectly".

**Lines 19 to 29: checking the arguments**

```java
  /**
   * Prints the sizing.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: sizing <sizing.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```

- One argument, the settings file. Otherwise `usage: sizing <sizing.properties>` goes to `System.err` and the process exits with 2. I ran `./gradlew -q sizing` with no argument and got that line.

**Lines 30 to 38: reading and printing**

```java
    try {
      switch (SizingInputs.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<SizingInputs, List<String>>(SizingInputs inputs) ->
            System.out.print(SizingReport.render(inputs));
        case Result.Error<SizingInputs, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

- `switch (SizingInputs.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0])))))` loads the file, wraps it in a reader and reads the inputs. The result is switched with two record patterns. On `Success`, `System.out.print(SizingReport.render(inputs))` prints the report. On `Error`, every problem is printed as `invalid setting: ...` and the process exits with 2.

**Lines 39 to 44: an unreadable file**

```java
    } catch (IOException unreadable) {
      System.err.println("cannot read " + arguments[0] + ": " + unreadable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }
}
```

- `catch (IOException unreadable)` prints `cannot read <file>: <reason>` and exits with 2.

### `ThreadCost.java`

Measures what it costs to have many threads waiting at once: the time to start them, the heap they add, and the platform threads the JVM then has.

**Lines 1 to 11: package and imports**

```java
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
```

- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `ManagementFactory`: the entry to the JVM's own measurements: memory, threads, operating system.
- `MemoryMXBean`: the object that reports heap use.
- `Duration`: a length of time.
- `ArrayList`: a growable list.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.
- `CountDownLatch`: a gate that opens when a count reaches zero.
- `TimeUnit`: a unit of time for waits, such as nanoseconds.

**Lines 12 to 19: the class**

```java
/**
 * Measures what it costs to have many threads waiting at once: the time to start them, the heap
 * they add, and the platform threads the JVM then has. Stateless.
 */
public final class ThreadCost {

  private ThreadCost() {}
```

- Stateless, with a private constructor. The imports bring `ManagementFactory` and `MemoryMXBean`, the JVM's own measurement objects.

**Lines 20 to 43: one measurement**

```java
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
```

- `Result` is a record nested in the class, with five components: `kind` (platform or virtual), `threads`, `startTime`, `heapGrowthBytes` and `platformThreadsInJvm`. The comment says virtual threads keep their stacks on the heap, while platform threads keep theirs outside it, so heap growth is much larger for virtual threads. This `Result` is not the result4j `Result`: it is a different type with the same name, kept inside `ThreadCost`.

**Lines 44 to 60: checking the arguments**

```java
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
```

- `measure(kind, threads, waitLimit)` throws `InterruptedException` if interrupted while waiting. The checks refuse nulls and fewer than 1 thread.

**Lines 61 to 66: choosing the thread builder**

```java
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    Thread.Builder builder =
        switch (kind) {
          case PLATFORM -> Thread.ofPlatform().name("seatwave-cost-", 0);
          case VIRTUAL -> Thread.ofVirtual().name("seatwave-cost-", 0);
        };
```

- `MemoryMXBean memory = ManagementFactory.getMemoryMXBean()` reports heap use.
- A `Thread.Builder` makes threads of one kind. A `switch` expression picks `Thread.ofPlatform().name("seatwave-cost-", 0)` or `Thread.ofVirtual().name("seatwave-cost-", 0)`. Names count from 0.

**Lines 67 to 76: starting the threads**

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
```

- Two **latches**. `waiting` starts at the number of threads; each thread counts it down once it is waiting. `release` starts at 1 and is opened by the main thread to let every thread end.
- The comment says `System.gc()` is a hint, not a guarantee: it asks the JVM to collect garbage so the heap reading before and after is mostly live objects. `heapBefore` is the heap used now.
- `startNanos` is the clock before the first start. `started` is the list of threads. The loop calls `builder.start(() -> waitForRelease(waiting, release, waitLimit))` for each, which creates and starts a thread that runs the lambda, and keeps the `Thread` object.

**Lines 77 to 80: waiting until all are waiting**

```java
    if (!waiting.await(waitLimit.toNanos(), TimeUnit.NANOSECONDS)) {
      release.countDown();
      throw new IllegalStateException("not every thread started within " + waitLimit);
    }
```

- `waiting.await(waitLimit, NANOSECONDS)` waits until every thread has counted down. If time runs out, it opens the release gate so the threads can end, and throws `IllegalStateException`. A measurement that cannot complete is an error, not a number.

**Lines 81 to 85: measuring**

```java
    Duration startTime = Duration.ofNanos(System.nanoTime() - startNanos);
    int platformThreads = ManagementFactory.getThreadMXBean().getThreadCount();
    System.gc();
    long heapGrowth = memory.getHeapMemoryUsage().getUsed() - heapBefore;
    release.countDown();
```

- `startTime` is the time from the first start until every thread was waiting. `ManagementFactory.getThreadMXBean().getThreadCount()` is the number of platform threads in the JVM now. After another `System.gc()`, `heapGrowth` is the heap now minus the heap before. Then `release.countDown()` lets the threads end.

**Lines 86 to 92: cleaning up**

```java
    for (Thread thread : started) {
      if (!thread.join(waitLimit)) {
        throw new IllegalStateException("a thread did not finish within " + waitLimit);
      }
    }
    return new Result(kind, threads, startTime, heapGrowth, platformThreads);
  }
```

- `thread.join(waitLimit)` waits for each thread to end, up to the limit, and returns `false` if it did not (a `Duration` overload, Java 19). A thread that does not finish is an error. The method returns the result record.

**Lines 93 to 106: what each thread does**

```java
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
```

- `waiting.countDown()` says "I am here". Then `release.await(waitLimit, NANOSECONDS)` waits for the release, which is the cost being measured: a thread that waits, as a hold waits on I/O. If the release does not come in time, the thread throws.
- `catch (InterruptedException interrupted)` restores the interrupt flag and returns, so the thread ends.

### `ThreadCostCli.java`

The command-line entry point of the thread-cost measurement. Exit status 2 means bad usage or settings; 1 means a run failed.

**Lines 1 to 9: package and imports**

```java
package io.plainround.seatwave.concurrency.threads;

import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
```

- `SettingsReader`: the class that reads typed values from a settings file.
- `ExecutorMode`: the enum `PLATFORM` or `VIRTUAL`.
- `IOException`: the exception for a failed read, write or socket call.
- `Path`: a file path.
- `Duration`: a length of time.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.

**Lines 10 to 27: constants**

```java
/**
 * Command-line entry point of the thread-cost measurement. Exit status 2 means bad usage or
 * settings; 1 means a run failed.
 */
public final class ThreadCostCli {

  private static final int EXIT_RUN_FAILED = 1;
  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  // macOS on the verification machine allows 8,192 threads per process (kern.num_taskthreads).
  private static final int MAX_PLATFORM_THREADS = 6000;
  private static final int MAX_VIRTUAL_THREADS = 1_000_000;
  private static final int MAX_MILLIS = 120_000;
  private static final double BYTES_PER_KIB = 1024.0;
  private static final double NANOS_PER_MILLI = 1e6;

  private ThreadCostCli() {}
```

- `MAX_PLATFORM_THREADS = 6000`: the comment says macOS on the verification machine allows 8,192 threads per process (`kern.num_taskthreads`), and I read that setting as `kern.num_taskthreads: 8192`. 6,000 stays well under it. `MAX_VIRTUAL_THREADS = 1_000_000`. `BYTES_PER_KIB` and `NANOS_PER_MILLI` are unit conversions.

**Lines 28 to 38: checking the arguments**

```java
  /**
   * Measures each configured count of each kind and prints one line per measurement.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: threadCost <thread-cost.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```

- One argument, the settings file; otherwise usage and exit 2.

**Lines 39 to 45: reading the settings**

```java
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      List<Integer> platformCounts =
          reader.intListBetween("platform.thread.counts", 1, MAX_PLATFORM_THREADS);
      List<Integer> virtualCounts =
          reader.intListBetween("virtual.thread.counts", 1, MAX_VIRTUAL_THREADS);
      Duration limit = reader.millisBetween("wait.limit.millis", 1, MAX_MILLIS);
```

- `intListBetween("platform.thread.counts", 1, 6000)` reads a comma-separated list such as `1000,4000`. `virtual.thread.counts` is the same up to a million. `wait.limit.millis` is the longest any start, wait or join may take.

**Lines 46 to 49: bad settings**

```java
      if (!reader.problems().isEmpty()) {
        reader.problems().forEach(problem -> System.err.println("invalid setting: " + problem));
        System.exit(EXIT_BAD_USAGE);
      }
```

- Every problem is printed and the process exits with 2.

**Lines 50 to 57: the heading**

```java
      System.out.printf(
          Locale.ROOT,
          "Java %s, %d logical processors, heap limit %d MiB%n",
          System.getProperty("java.version"),
          Runtime.getRuntime().availableProcessors(),
          Runtime.getRuntime().maxMemory() >> 20);
      System.out.println(
          "kind      threads  start ms  heap growth KiB  heap per thread bytes  platform threads");
```

- `System.out.printf(Locale.ROOT, "Java %s, %d logical processors, heap limit %d MiB%n", ...)` prints the Java version, the number of processors and the heap limit. `Runtime.getRuntime().maxMemory() >> 20` shifts the number of bytes right by 20 bits, which divides by 2 to the 20th, 1,048,576: bytes become MiB. The next line prints the column headings.

**Lines 58 to 63: measuring each count**

```java
      for (int count : platformCounts) {
        print(ThreadCost.measure(ExecutorMode.PLATFORM, count, limit));
      }
      for (int count : virtualCounts) {
        print(ThreadCost.measure(ExecutorMode.VIRTUAL, count, limit));
      }
```

- For each platform count and then each virtual count, `ThreadCost.measure(kind, count, limit)` runs and `print` prints one line.

**Lines 64 to 72: failures**

```java
    } catch (IOException unreadable) {
      System.err.println("cannot read " + arguments[0] + ": " + unreadable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the measurement was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }
```

- `IOException` (an unreadable file) exits with 2. `InterruptedException` restores the flag, prints a message and exits with 1.

**Lines 73 to 85: printing one line**

```java
  private static void print(ThreadCost.Result result) {
    System.out.printf(
        Locale.ROOT,
        "%-8s %8d %9.1f %16.0f %22d %17d%n",
        result.kind().name().toLowerCase(Locale.ROOT),
        result.threads(),
        result.startTime().toNanos() / NANOS_PER_MILLI,
        result.heapGrowthBytes() / BYTES_PER_KIB,
        result.heapGrowthBytes() / result.threads(),
        result.platformThreadsInJvm());
  }
}
```

- The template `"%-8s %8d %9.1f %16.0f %22d %17d%n"` has six columns with fixed widths. The values: the kind in lower case, the thread count, the start time in milliseconds, the heap growth in KiB with no decimals, the heap growth per thread in bytes (`heapGrowthBytes / threads`, a whole-number division), and the platform threads in the JVM.

### `CarrierPinning.java`

Shows when a blocked virtual thread frees its carrier and when it pins it, on JDK 25.

**Lines 1 to 12: package and imports**

```java
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
```

- `Duration`: a length of time.
- `Objects`: helpers such as `requireNonNull`.
- `Callable`: a task that returns a value and may throw.
- `CountDownLatch`: a gate that opens when a count reaches zero.
- `ExecutionException`: the exception that wraps a failure inside a task.
- `ExecutorService`: something that runs tasks on threads and can be closed.
- `Executors`: factory methods that build executors.
- `Future`: a handle on a result that is not ready yet.
- `TimeUnit`: a unit of time for waits, such as nanoseconds.
- `TimeoutException`: thrown when a wait ran out of time.

**Lines 13 to 29: the class comment and constant**

```java
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
```

- The comment states the rule on JDK 25: since JDK 24, a virtual thread that blocks inside `synchronized` no longer pins its carrier. A few cases remain; one is blocking inside a class initializer, and another is waiting for a class that another thread is initializing. Each demo starts one virtual thread per carrier, lets each block for the same time, and then asks an unrelated virtual thread to run. The class initializer runs once per JVM, so the pinning demo shows its effect only on its first call.
- `BLOCKS_FOR = Duration.ofMillis(500)` is how long each blocker blocks: long enough to read, short enough for a demo. It has no `private`, so `PinningCli` in the same package can print it.

**Lines 30 to 49: the sleeping blockers**

```java
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
```

- `delayWhileSleeping(waitLimit)` calls `unrelatedTaskDelay` with a blocker written as a lambda: `() -> { Thread.sleep(BLOCKS_FOR); return 1; }`. It is a `Callable<Integer>`: a task that returns a value and may throw. `Thread.sleep` unmounts a virtual thread, so the carrier is free.
- It declares three checked exceptions in `throws`: `InterruptedException`, `ExecutionException` and `TimeoutException`.

**Lines 50 to 64: the pinning blockers**

```java
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
```

- `delayWhilePinned(waitLimit)` uses `() -> SlowToInitialize.VALUE` as the blocker. Reading `SlowToInitialize.VALUE` for the first time makes the JVM run the class initializer of `SlowToInitialize`, which sleeps (below). A virtual thread blocked there cannot unmount.

**Lines 65 to 72: measuring: the setup**

```java
  private static Duration unrelatedTaskDelay(Callable<Integer> blocker, Duration waitLimit)
      throws InterruptedException, ExecutionException, TimeoutException {
    Objects.requireNonNull(waitLimit, "waitLimit");
    // The scheduler's parallelism defaults to the number of processors: one blocker per carrier.
    // Once every blocker has counted down, each one is running on its own carrier.
    int blockers = Runtime.getRuntime().availableProcessors();
    CountDownLatch blockersStarted = new CountDownLatch(blockers);
```

- The comment: the scheduler's parallelism defaults to the number of processors, so one blocker per carrier fills every carrier. Once every blocker has counted down, each one is running on its own carrier. `blockers = availableProcessors()` is 12 on the measuring machine.
- `CountDownLatch blockersStarted` counts the blockers that have started.

**Lines 73 to 79: measuring: the blockers**

```java
    try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int blockerIndex = 0; blockerIndex < blockers; blockerIndex++) {
        virtualThreads.submit(() -> {
          blockersStarted.countDown();
          return blocker.call();
        });
      }
```

- `try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor())` makes an executor with one virtual thread per task, closed at the end of the block (`close` waits for the tasks).
- Each blocker is submitted as a lambda that first does `blockersStarted.countDown()` and then `blocker.call()`.

**Lines 80 to 88: measuring: the unrelated task**

```java
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

- `blockersStarted.await(...)` waits until every blocker is running. If not, `TimeoutException` is thrown.
- `submittedNanos` is the moment the unrelated task is submitted. `virtualThreads.submit(System::nanoTime)` submits a task that returns the time at which it ran. `unrelated.get(waitLimit, NANOSECONDS)` waits for it. The difference, `ranAtNanos - submittedNanos`, is **how long the unrelated task waited to start**. If the carriers are free it is about a millisecond. If every carrier is pinned, the task waits for one to free up: about the whole block time.

**Lines 89 to 103: a class that blocks while it starts**

```java
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
```

- `SlowToInitialize` is a private nested class. `static final int VALUE = initialize()` is set by a **static initializer**: code that runs once, when the class is first used. `initialize()` sleeps for 500 ms, as a slow static setup that calls the network would, and returns 1.
- The `catch (InterruptedException interrupted)` restores the interrupt flag.
- Because the initializer runs once per JVM, the pinning demo only works the first time it runs in a process.

### `PinningCli.java`

The command-line entry point of the carrier-pinning demo. It takes no settings: the delay it shows comes from the blockers, which are part of the demo.

**Lines 1 to 6: package and imports**

```java
package io.plainround.seatwave.concurrency.threads;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
```

- `Duration`: a length of time.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `ExecutionException`: the exception that wraps a failure inside a task.
- `TimeoutException`: thrown when a wait ran out of time.

**Lines 7 to 19: constants**

```java
/**
 * Command-line entry point of the carrier-pinning demo. It takes no settings: the delay it shows
 * comes from the blockers, which are part of the demo, not a tunable. Exit status 1 means the demo
 * failed.
 */
public final class PinningCli {

  private static final int EXIT_RUN_FAILED = 1;
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  private static final double NANOS_PER_MILLI = 1e6;

  private PinningCli() {}
```

- `EXIT_RUN_FAILED = 1`. `WAIT_LIMIT = Duration.ofSeconds(10)` is the longest any wait in the demo may take.

**Lines 20 to 37: running the demo**

```java
  /**
   * Runs the demo: first with blockers that unmount, then with blockers that pin.
   *
   * @param arguments none
   */
  public static void main(String[] arguments) {
    try {
      int carriers = Runtime.getRuntime().availableProcessors();
      System.out.printf(
          Locale.ROOT,
          "%d carriers; %d virtual threads each block for %d ms, then an unrelated virtual task"
              + " is started.%n",
          carriers,
          carriers,
          CarrierPinning.BLOCKS_FOR.toMillis());
      print("blocked in Thread.sleep (unmounts)", CarrierPinning.delayWhileSleeping(WAIT_LIMIT));
      print("blocked in a class initializer (pins)", CarrierPinning.delayWhilePinned(WAIT_LIMIT));
```

- It prints how many carriers there are and what will happen: `12 carriers; 12 virtual threads each block for 500 ms, then an unrelated virtual task is started.` `CarrierPinning.BLOCKS_FOR.toMillis()` gives 500.
- `print("blocked in Thread.sleep (unmounts)", delayWhileSleeping(WAIT_LIMIT))` runs the first demo, then `delayWhilePinned` the second.

**Lines 38 to 46: failures**

```java
    } catch (ExecutionException | TimeoutException failed) {
      System.err.println("the demo failed: " + failed);
      System.exit(EXIT_RUN_FAILED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the demo was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }
```

- `catch (ExecutionException | TimeoutException failed)` handles two types the same way: print `the demo failed: ...` and exit with 1. `InterruptedException` restores the flag and exits with 1.

**Lines 47 to 55: printing one result**

```java
  private static void print(String blockers, Duration delay) {
    System.out.printf(
        Locale.ROOT,
        "%-38s the unrelated task waited %6.1f ms to start%n",
        blockers + ":",
        delay.toNanos() / NANOS_PER_MILLI);
  }
}
```

- `"%-38s the unrelated task waited %6.1f ms to start%n"` pads the label to 38 characters and the delay to 6 characters with one decimal, so the two lines line up.

## Try it yourself

All commands run from the repository folder. Numbers from the measuring programs differ on every run and on every machine.

**1. Size the node with Little's Law.**

```text
./gradlew -q sizing --args='config/sizing.properties'
```

It printed, among other lines:

```text
HOLDS
  In flight (Little's Law): 500 holds/s x 0.040 s = 20.0 holds
  Connection pool, ceiling 80 %: ceiling of 20.0 / 0.80 = 25 connections; the database allows 32: fits
  Capacity of 25 connections: 25 / 0.040 s = 625 holds/s; at 500 holds/s the pool is 80 % busy
SEAT MAPS
  CPU busy (Little's Law on cores): 2,000 seat maps/s x 0.005 s = 10.0 vCPUs busy; the node has 4
  Utilization: 10.0 / 4 = 250 %: one node cannot serve this; more threads add no CPU
  Nodes for seat maps: ceiling of 2,000 / 560 = 4 nodes
WAITING FACTOR u / (1 - u), one server
   50 %: 1.0  80 %: 4.0  90 %: 9.0  95 %: 19.0  99 %: 99.0
```

Check the first line by hand: 500 x 0.040 = 20. Then I changed `hold.time.millis` to 80 in a copy of the file and ran the same command with that path. The holds section became:

```text
HOLDS
  In flight (Little's Law): 500 holds/s x 0.080 s = 40.0 holds
  Connection pool, ceiling 80 %: ceiling of 40.0 / 0.80 = 50 connections; the database allows 32: DOES NOT FIT
  Capacity of 50 connections: 50 / 0.080 s = 625 holds/s; at 500 holds/s the pool is 80 % busy
```

A database that is twice as slow doubles the connections you need, and 50 does not fit in the 32 the database allows.

**2. Measure what a thread costs.**

```text
./gradlew -q threadCost --args='config/thread-cost.properties'
```

It took about 2 seconds and printed:

```text
Java 25.0.2, 12 logical processors, heap limit 8192 MiB
kind      threads  start ms  heap growth KiB  heap per thread bytes  platform threads
platform     1000      35.5              298                    305              1006
platform     4000     225.2             1146                    293              4006
virtual      1000      55.9             1859                   1904                20
virtual      4000      11.8             8563                   2192                20
virtual     10000      20.7            14800                   1515                20
virtual    100000     134.0           114771                   1175                20
```

Read the last column. 4,000 platform threads mean 4,006 platform threads in the JVM. 100,000 virtual threads mean 20, the carriers and the JVM's own helpers. The heap column is the other way round, because a virtual thread's stack is on the heap: about 1 to 2 KiB each.

**3. Watch carrier pinning.**

```text
./gradlew -q pinning
```

```text
12 carriers; 12 virtual threads each block for 500 ms, then an unrelated virtual task is started.
blocked in Thread.sleep (unmounts):    the unrelated task waited    0.8 ms to start
blocked in a class initializer (pins): the unrelated task waited  501.6 ms to start
```

**4. Bad usage.** `./gradlew -q sizing` with no argument printed `usage: sizing <sizing.properties>` and Gradle reported that the Java process exited with status 2.

## Mistakes people make

- **Sizing a pool with no ceiling.** At exactly the in-flight count the pool is 100 percent busy and the wait grows without bound. The report sizes to a ceiling.
- **Believing more threads give more CPU.** For CPU-bound work, the cores are the limit. The seat-map section shows 250 percent utilization: more threads would only queue for the same cores.
- **Using an average as the latency.** Little's Law is about averages. The p99 is a different number, and the benchmark measures it.
- **Reading a hidden default.** A program that silently falls back to a number can run a different experiment on a different machine. `SettingsReader` makes a missing key a problem line.
- **Stopping at the first error.** A settings file with three typos should show three lines in one run. The reader collects all.
- **Parsing a decimal with the machine's language.** `String.format` without a `Locale` can print a comma as the decimal point. Every format here passes `Locale.ROOT`.
- **Believing `synchronized` always pins.** That was true before JDK 24. On JDK 25 it does not; I measured 0.6 ms. Blocking in a class initializer still pins.

## Check yourself

1. **A node receives 800 requests per second and each spends 25 ms inside. How many are in flight on average?** 800 x 0.025 = 20.
2. **At what pool size would you stop with an 80 percent ceiling?** 20 / 0.80 = 25.
3. **What is the waiting factor at 90 percent utilization?** 0.9 / (1 - 0.9) = 9, so the average wait is nine times the service time.
4. **Why does `SettingsReader` return a stand-in value for a bad key instead of throwing?** So the code can read every key and collect all the problems in one pass. The caller checks `problems()` before using any value.
5. **Why does `threadCost` show 20 platform threads for 100,000 virtual threads?** Virtual threads are not platform threads. They run on a few carrier threads, and the rest of the 20 are the JVM's own threads.

## Further reading

Official documentation only, for JDK 25:

- `java.util.Properties`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- `Thread`, the builders and virtual threads: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html
- Virtual threads, JEP 444: https://openjdk.org/jeps/444
- Synchronizing virtual threads without pinning, JEP 491: https://openjdk.org/jeps/491
- The `java.lang.management` package: https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/package-summary.html
