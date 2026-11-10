# Java 25 (the language and the standard library)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`. A program called the **compiler** (`javac`) turns them into **bytecode**, a compact instruction format stored in `.class` files. A second program, the **Java Virtual Machine** (JVM), reads the bytecode and runs it. The JVM is the same on a Mac, a Windows PC and a Linux server, so the same bytecode runs on all of them.

Java is **statically typed**. Every variable, parameter and return value has a type, such as `int` (a whole number) or `String` (text). The compiler checks the types before the program runs. A mistake such as passing text where a number is expected stops the build instead of crashing later.

The **JDK** (Java Development Kit) is the download that has the compiler, the JVM and the **standard library**: ready-made classes such as `String`, `List`, `Duration` and `Semaphore`. This repository uses **JDK 25**, a long-term-support release. The machine used for these notes had Eclipse Temurin 25.0.2, and `java -version` printed `openjdk version "25.0.2" 2026-01-20 LTS`.

## Why this repository uses it

The repository is the hands-on project of Module 4 of a system design course. It builds one node of a ticket-booking service called SeatWave, in two thread models, and measures them. Java 25 gives what that work needs:

- **Threads in the language and the library.** Platform threads, virtual threads (a JDK 21 feature), executors, queues, semaphores and latches are all in the standard library. No framework is needed. `java-concurrency.md` explains them.
- **Records and sealed interfaces** make small immutable data types short, and let the compiler prove that every `switch` handles every case.
- **A database driver interface (JDBC)** is in the JDK, so the node talks to an embedded database with no extra layer (`h2-and-jdbc.md`).
- **Sockets and selectors (`java.nio`)** are in the JDK, so the event-loop lab needs no library.
- **The standard library covers the rest**, so the project has one runtime library apart from the database (`result4j.md`).

## Where it appears

| Path | What it does with Java |
|---|---|
| `src/main/java/io/plainround/seatwave/concurrency/node/` | 13 files: one SeatWave node. See `java-node-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/concurrency/bench/` | 13 files: the open-model benchmark, the CSV and the SVG chart. See `java-bench-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/concurrency/eventloop/` | 5 files: the event-loop lab. See `java-eventloop-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/concurrency/sizing/`, `threads/`, `config/` | 9 files: Little's Law, thread cost, carrier pinning, the settings reader. See `java-sizing-threads-config-walkthrough.md`. |
| `src/test/java/io/plainround/seatwave/concurrency/` | The tests, also Java. |
| `build.gradle.kts` | Tells Gradle to compile with Java 25 and to treat every warning as an error (`-Xlint:all -Werror`). |

In all there are 40 source files under `src/main/java`.

## How it works underneath

**From text to a running program.** You write `HoldRequest.java`. The compiler reads it and writes `HoldRequest.class`. Gradle does this for you: `./gradlew -q compileJava` printed nothing (success) and left 58 class files under `build/classes/java/main/`. That is more than 40 because a nested class, a record or an enum inside a file gets its own class file. To start a program, a JVM process starts, loads the class you name, and calls its `main` method.

**A process** is one running copy of a program, with its own memory. The JVM is one process. When `main` returns, the process ends with **exit status** 0, which means success. A program may call `System.exit(n)` to end at once with another number. By convention a non-zero status means failure. This repository uses 1 for "a run failed" and 2 for "the command was used incorrectly".

**Memory.** Two areas matter. The **stack** holds the method calls in progress with their local variables: each call adds a frame and each return removes one. Every thread has its own stack. The **heap** holds objects: `new HoldRequest(...)` makes an object on the heap. A variable holds a **reference**, like an address that points to the object. The JVM frees objects that no variable can reach any more. This is **garbage collection**. You never free memory by hand.

**The JIT compiler.** The JVM first interprets bytecode. It counts how often each method runs, and the **JIT compiler** (just-in-time compiler) turns the busy methods into native machine code while the program runs. A loop is slow in its first runs and fast after. That is why the benchmark warms up first and throws that step away.

**The class path** is the list of places where the JVM looks for compiled classes and libraries (jar files, which are zip files of classes). Gradle builds the right class path for each task.

**Threads.** A **thread** is one line of execution inside a process. A program may have several, and they run at the same time on different processor cores. `java-concurrency.md` is the file about them.

## Walkthrough of this repository's use

### The basic words

**Class.** A class is a blueprint for objects. It has **fields** (the data) and **methods** (the code). Everything in Java lives in a class. Records, enums and interfaces are special kinds of class.

**Package and import.** A **package** is a named group of classes, written with dots: `io.plainround.seatwave.concurrency.node`. The name matches the folder path under `src/main/java`. The first line of a file is `package ...;`. After it come the **imports**: each `import java.util.List;` lets the file write `List` instead of the full name `java.util.List`. Classes of the same package need no import.

```java
package io.plainround.seatwave.concurrency.sizing;

import java.time.Duration;
import java.util.Objects;
```

(These are the first lines of `LittlesLaw.java`.)

**Method.** A method is a named piece of code, with parameters and a return type. `public` means any class may call it. `private` means only this class. `static` means it belongs to the class, not to an object: you call it as `LittlesLaw.inFlight(...)`, with no object. Here is a whole class of static methods:

```java
public final class LittlesLaw {

  private static final double NANOS_PER_SECOND = 1e9;

  private LittlesLaw() {}

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


- `public final class LittlesLaw`: `final` on a class means no other class may extend it.
- `private static final double NANOS_PER_SECOND = 1e9;` is a **constant**: `static` (one copy), `final` (never reassigned), `double` (a number with a decimal part). The name is in capitals by habit.
- `private LittlesLaw() {}` is a **constructor** with no body. A constructor is the code that runs when `new LittlesLaw()` makes an object. Making it `private` means nobody can make one, so the class is only a place for functions.
- `public static double inFlight(double ratePerSecond, Duration timeInside)`: the return type `double` comes before the name. Two parameters, each with a type.
- `if (...) { throw new IllegalArgumentException("..."); }` ends the method with an exception. The `return` line gives the answer: `rate x time` as a `double`.

**Final.** On a variable or a field, `final` means it is assigned once and never changed. Most fields in this repository are `private final`, which is what makes the objects safe to share between threads: nothing changes after the constructor.

**Types.** `int` is a 32-bit whole number (up to 2,147,483,647). `long` is a 64-bit whole number; the nanosecond clock returns one. `double` is a 64-bit decimal number. `boolean` is true or false. `char` is one character. `String` is text. An underscore inside a number is only for reading: `100_000` is 100000.

```java
  /** The largest thread or connection count a settings file may ask for. */
  public static final int MAX_COUNT = 100_000;
```

(`NodeSettings`, line 36 to 37.) **Arithmetic** on mixed types widens the smaller: `7 / 2` is 3 (whole-number division), `7 / 2.0` is 3.5. I ran both. A **cast** like `(int) Math.ceil(20.0 / 0.80)` converts a decimal to a whole number: it gave 25. In `SeatStore.seatLabel` a letter plus a number is also arithmetic:

```java
    char section = (char) (FIRST_SECTION + seatIndex / SEATS_PER_SECTION);
    return String.format(Locale.ROOT, "%c-%d", section, seatIndex % SEATS_PER_SECTION + 1);
```


`'A'` is a `char`. Adding the section number `seatIndex / 1000` gives an `int`, and `(char)` converts it back to a letter. For position 19,999 the section is `'A' + 19` = `T` and the number is `19999 % 1000 + 1` = 1000. I ran this and got `T 1000`.

**Exceptions.** An **exception** is an object that says "something went wrong" and makes the method stop. `throw new IllegalArgumentException("...")` throws one. The caller can catch it with `try { ... } catch (SomeException name) { ... }`, or let it pass up. There are two kinds. **Unchecked** exceptions (such as `IllegalArgumentException` and `NullPointerException`) mean a bug: the code was used wrongly. **Checked** exceptions (such as `IOException`, `SQLException` and `InterruptedException`) mean something outside the code failed and the caller must decide: a method that may throw one must say so with `throws`, and the compiler makes every caller catch it or pass it on. This repository follows one rule: **if a person can cause it by typing, it is a value (a `Result`, see `result4j.md`); if only a bug can cause it, it is an exception.**

```java
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
```


`Integer.parseInt(text)` throws `NumberFormatException` for text that is not a number. A typo in a settings file is an expected failure, so the code catches it and records a problem line (the comment says so). The variable name `notANumber` says what the exception means here.

**Main.** A program starts at `public static void main(String[] arguments)`. `arguments` is what follows the class name on the command line.

```java
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: sizing <sizing.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```


`System.err.println` prints to the **error stream**, a second output next to the normal one (`System.out`), so error text can be kept apart. `System.exit(2)` ends the process with status 2.

### The features this code uses

**Records and compact constructors** (Java 16 or newer). A **record** is a short class that only carries values. The header lists the **components**. The compiler writes the fields, the constructor, an accessor for each component, `equals`, `hashCode` and `toString`. A **compact constructor** has no parentheses; it runs before the fields are set and is the place to refuse bad values.

```java
public record HoldRequest(long eventId, String seatLabel, String customer) {

  // The same allow-lists as SeatWave version 0, so a request valid there is valid here.
  private static final Pattern SEAT_LABEL_PATTERN = Pattern.compile("[A-T]-[0-9]{1,4}");
  private static final Pattern CUSTOMER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /**
   * Creates the request.
   *
   * @throws IllegalArgumentException if a field breaks its allow-list
   */
  public HoldRequest {
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(customer, "customer");
    if (eventId < 1) {
      throw new IllegalArgumentException("eventId must be 1 or more: " + eventId);
    }
    if (!SEAT_LABEL_PATTERN.matcher(seatLabel).matches()) {
      throw new IllegalArgumentException("invalid seat label");
    }
    if (!CUSTOMER_PATTERN.matcher(customer).matches()) {
      throw new IllegalArgumentException("invalid customer");
    }
  }
```


`javap` (a tool that lists a compiled class) shows what the compiler wrote for this record: the three private final fields, the constructor, `toString`, `hashCode`, `equals`, and the accessors `eventId()`, `seatLabel()` and `customer()`. `HoldRequest` is `final` and `extends java.lang.Record`. I ran `javap -p -cp build/classes/java/main io.plainround.seatwave.concurrency.node.HoldRequest` and saw them.

Records are **immutable**: no setter exists. To "change" one you build a new one, as `NodeSettings.withMode` does. A record's `toString` prints `Point[x=1, y=2]` and two records with equal components are equal (I ran `new Point(1, 2).equals(new Point(1, 2))` and got `true`).

**Sealed interfaces** (Java 17 or newer). An **interface** is a list of methods that classes promise to provide. `sealed ... permits A, B` means only the named classes may implement it.

```java
public sealed interface Workers extends AutoCloseable permits PlatformWorkers, VirtualWorkers {
```


The compiler knows the whole list, so a `switch` over a `Workers` value could be checked for completeness. `javap -v` shows the list in the class file under `PermittedSubclasses`: `PlatformWorkers` and `VirtualWorkers`. Here the two classes are `final`, which a sealed interface requires of its implementations (or `sealed` or `non-sealed`).

**Enums** (Java 5 or newer). An enum is a type with a fixed list of named values. Each may carry data.

```java
public enum HoldOutcome {
  /** The seat is now held for this fan. */
  CREATED(201),
  /** Another fan holds or owns the seat. A correct, expected answer. */
  SEAT_TAKEN(409),
  /** The executor's bound was reached: refused at the door, before any work. */
  QUEUE_FULL(503),
  /** The hold waited longer than the queue wait limit before a worker started it. */
  QUEUE_WAIT_EXCEEDED(503),
  /** No database connection became free within the borrow limit. */
  POOL_TIMEOUT(503),
  /** The database failed or timed out; the seat state is unknown, so the hold is refused. */
  DATABASE_FAILED(503),
  /** The worker was interrupted, which happens only while the node shuts down. */
  INTERRUPTED(503);

  private final int httpStatus;

  HoldOutcome(int httpStatus) {
    this.httpStatus = httpStatus;
  }
```


`CREATED(201)` calls the private constructor with 201. `HoldOutcome.valueOf("CREATED")` finds a value by name, and `values()` lists them all (`javap` shows both). Enum values are compared with `==`.

**`switch` with arrows and patterns** (switch expressions Java 14, pattern matching and record patterns Java 21). A **switch expression** produces a value, and each branch is written `case X -> result`. For an enum, the compiler requires every value to be covered, so no `default` is needed, and adding a value to the enum stops the build until the switch handles it.

```java
    Workers workers =
        switch (settings.mode()) {
          case PLATFORM ->
              new PlatformWorkers(settings.platformWorkers(), settings.executorQueueBound());
          case VIRTUAL -> new VirtualWorkers(settings.virtualMaxInFlight());
        };
```


A **pattern** matches the shape of a value and names its parts. `Result` (from result4j) is a sealed interface with two record cases, so a switch over it uses **record patterns**:

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
```


- `case Result.Error<...>(List<String> problems) ->` matches an error and gives its content the name `problems`.
- `yield value;` gives the value of a block in a switch expression (Java 14 or newer).
- A `switch` **statement** with arrows, as in `OpenModelLoad.Tally.count`, does not produce a value; the compiler does not force it to list every enum value:

```java
      switch (reply.outcome()) {
        case CREATED -> created++;
        case SEAT_TAKEN -> seatTaken++;
        case QUEUE_FULL, QUEUE_WAIT_EXCEEDED -> refusedQueue++;
        case POOL_TIMEOUT -> refusedPool++;
        case DATABASE_FAILED, INTERRUPTED -> otherFailures++;
      }
```


`case QUEUE_FULL, QUEUE_WAIT_EXCEEDED ->` lists two values for one branch.

**Pattern matching for `instanceof`** (Java 16 or newer). The test and the cast are one step:

```java
  private static long processCpuNanos() {
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
      return operatingSystem.getProcessCpuTime();
    }
    throw new IllegalStateException("this JVM does not report process CPU time");
  }
```


If the object is that type, `operatingSystem` is the same object with the new type. I ran a small example, `o2 instanceof String str && str.length() > 3`, and it printed 5 for `"hello"`.

**`Optional`** (Java 8; `isEmpty` Java 11). An `Optional<T>` is a box that holds a value or nothing. It is the honest return type for "there may be no answer", and it avoids returning `null`.

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


`Optional.of(x)` boxes a value, `Optional.empty()` is the empty box. The caller asks `borrowed.isEmpty()` before `get()`. `BenchmarkReport.lastHealthyRate` returns an `Optional<Integer>` for "the highest rate that met the target, or none", and `healthy.map(rate -> rate + " holds/s").orElse("none")` turns it into text. I ran `Optional.of(3).map(x -> x + 1).orElse(0)` and got 4, and the same on an empty box gave 0.

**Lambdas and method references** (Java 8). A **lambda** is a short function written in place: `step -> step.latency().p99Millis()` means "given a step, return its p99". A **method reference** names an existing method: `StepResult::errorPercent`. They are used wherever a function is expected.

```java
public enum ChartMetric {
  /** The p99 of the good replies, in milliseconds. */
  P99_MILLIS("p99 latency of 201 and 409 replies (ms)", step -> step.latency().p99Millis()),
  /** The share of holds without a good reply in time, in percent. */
  ERROR_PERCENT("holds without a good reply in time (%)", StepResult::errorPercent);

  private final String axisLabel;
  private final ToDoubleFunction<StepResult> reading;

  ChartMetric(String axisLabel, ToDoubleFunction<StepResult> reading) {
    this.axisLabel = axisLabel;
    this.reading = reading;
  }
```


`ToDoubleFunction<StepResult>` is a type for "a function from a `StepResult` to a `double`". The `<...>` is a **generic** type argument: it says what the type works on. `List<String>` is a list of text.

**Streams** (Java 8). A **stream** is a pipeline over a collection: take the items, transform them, gather a result.

```java
    double maxRate = results.stream().mapToInt(StepResult::offeredRps).max().orElse(1);
    double maxValue =
        Math.max(referenceLine, results.stream().mapToDouble(metric::read).max().orElse(1));
```


`results.stream().mapToInt(StepResult::offeredRps).max().orElse(1)` takes each step's rate, finds the largest, and uses 1 if there are no steps. In `BenchmarkRun.summary`, `all.stream().filter(step -> step.mode() == mode).toList()` keeps the steps of one mode (`toList` is Java 16). In `ConnectionPool.close`, `failures.stream().skip(1).forEach(first::addSuppressed)` skips the first item and calls a method on each of the rest.

**Immutable collections** (`List.of` Java 9, `List.copyOf` Java 10, `Map.of` Java 9). These make lists and maps that cannot be changed. A caller that tries to add gets `UnsupportedOperationException`. They make shared data safe: no thread can change it.

```java
    this.allConnections = List.copyOf(connections);
    // Fair: a borrower that has waited longest gets the next connection, so no hold starves.
    this.idleConnections = new ArrayBlockingQueue<>(allConnections.size(), true, allConnections);
```

```java
  private static final Map<ExecutorMode, String> COLORS =
      Map.of(ExecutorMode.PLATFORM, "#d55e00", ExecutorMode.VIRTUAL, "#0072b2");
```


`List.copyOf(connections)` is a copy that nobody can change, even if the caller later changes the original. I ran a small example: after `c = List.copyOf(l)` and `l.add(3)`, `c` was still `[1, 2]` and `c.add(9)` threw `UnsupportedOperationException`. `List.getFirst()` and `getLast()` (Java 21) return the first and last element; `failures.getFirst()` is in `ConnectionPool.close` and `modes.getFirst()` in `BenchmarkSettings`.

**`Duration`** (Java 8). A `Duration` is a length of time. `Duration.ofSeconds(300)` and `Duration.ofMillis(40)` make one; `toMillis()` and `toNanos()` read it. `Duration.ofMillis(40)` prints as `PT0.04S`, the standard text form (P for period, T for time, then seconds). `isNegative()`, `isZero()` and `plus(...)` are used in the checks. The repository uses a `Duration` for every time setting, so the unit is part of the type and cannot be confused.

**`Instant` and `Clock`** (Java 8). An `Instant` is one point on the time line. A **`Clock`** is a source of the current time. The repository does not use `Instant` directly, but it **injects a `Clock`**: `SeatStore` takes one in its constructor and reads `clock.millis()` whenever it needs "now", instead of calling the system clock itself.

```java
  public SeatStore(Clock clock, int statementTimeoutSeconds) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (statementTimeoutSeconds < 1) {
      throw new IllegalArgumentException("the statement timeout must be 1 second or more");
    }
    this.statementTimeoutSeconds = statementTimeoutSeconds;
  }
```


Production passes `Clock.systemUTC()` (the real time, in UTC) in `BenchmarkRun`. A test passes a **fixed** clock, so hold expiry can be tested without waiting five minutes. I ran `Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC)` and its `millis()` was 1794312000000 and its `instant()` was `2026-11-10T12:00:00Z`. Two other clocks appear for a different purpose: `System.nanoTime()` (a count of nanoseconds from an arbitrary start, never goes backward, good for measuring how long something took) and `HoldEndpoint`'s `LongSupplier nanoClock`, which is `System::nanoTime` in production and a fake in tests. The wall clock can be set by a person; `nanoTime` cannot go backward, so durations use it.

**`String.format` with `Locale.ROOT`.** `String.format(template, values...)` fills a template. `%d` is a whole number, `%.1f` a decimal with one digit after the point, `%s` text, `%c` a character, `%,d` a whole number with thousands separators, `%8d` a whole number padded to 8 characters, `%-8s` text padded on the right, `%n` the system's line end, and `%%` one percent sign. `Locale` is a language and region. Without one, the machine's locale decides how numbers print (some languages use a comma as the decimal point). `Locale.ROOT` means "no particular region", so the output is the same everywhere. Every format in the repository passes it.

```java
  public PoolSnapshot {
    if (size < 1 || busy < 0 || busy > size || waitingBorrowers < 0 || borrowTimeouts < 0) {
      throw new IllegalArgumentException(
          String.format(
              Locale.ROOT,
              "impossible pool counters: size %d, busy %d, waiting %d, timeouts %d",
              size,
              busy,
              waitingBorrowers,
              borrowTimeouts));
    }
  }
```


I ran `String.format(Locale.ROOT, "%,d|%.1f|%8d|%-8s|%c-%d|%%", 1200, 19.0, 42, "ab", 'A', 7)` and got `1,200|19.0|      42|ab      |A-7|%`.

**`Objects.requireNonNull`** (Java 7). It throws `NullPointerException` with a message if the argument is null, and otherwise returns the argument. It stops a null at the door of a method instead of letting it fail somewhere far away.

```java
  public HoldReply {
    Objects.requireNonNull(outcome, "outcome");
    if (queueWaitNanos < 0 || poolWaitNanos < 0 || workNanos < 0) {
      throw new IllegalArgumentException("a duration is never negative");
    }
  }
```


I ran `Objects.requireNonNull(null, "seatLabel")` and got `NullPointerException` with the message `seatLabel`.

**Try-with-resources** (Java 7). A **resource** is an object that must be closed (a file, a connection, a thread pool). If it implements `AutoCloseable`, a `try (...)` block closes it at the end, on every path, even after an exception. Several resources close in reverse order.

```java
      try (ConnectionPool.Lease lease = borrowed.get()) {
        HoldOutcome outcome = seatStore.hold(lease.connection(), request);
        waitForRemoteDatabase();
        long workNanos = nanoClock.getAsLong() - borrowedNanos;
        return new HoldReply(outcome, queueWaitNanos, poolWaitNanos, workNanos);
      }
```


`ConnectionPool.Lease` implements `AutoCloseable`, so closing the lease gives the connection back. The `ExecutorService` has been `AutoCloseable` since Java 19, so a pool of threads can be a resource too (`EventLoopLab`, lines 70 to 73, opens a server and an executor in one `try`).

**Text blocks** (Java 15). A string between `"""` and `"""` may span lines, and the compiler strips the indentation all lines share. A backslash at the end of a line joins it to the next without a newline. `SeatStore` uses them for SQL:

```java
  private static final String HOLD_SQL =
      """
      UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?
      WHERE event_id = ? AND seat_label = ?
        AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
      """;
```


**Enhanced `for`, multi-catch, varargs, ternary.** `for (String item : items(key))` walks a collection. `catch (IOException | SQLException failed)` handles two exception types the same way (`BenchmarkCli`). `Object... values` in `SizingReport.line` accepts any number of arguments. `a ? b : c` picks `b` if `a` is true, else `c`: `update.executeUpdate() == 1 ? HoldOutcome.CREATED : HoldOutcome.SEAT_TAKEN`.

**Nested and inner classes.** A class inside another is **nested**. A nested record like `HoldEndpoint.Limits` or `OpenModelLoad.Pending` is a small helper type. A non-static nested class, like `ConnectionPool.Lease`, belongs to one outer object and can reach its fields.

**Annotations.** `@Override` asks the compiler to check that a method really overrides one above it. `@FunctionalInterface` asks it to check that an interface has exactly one abstract method, so a lambda can stand for it (`BlockingWork`).

**Javadoc.** A comment that starts `/**` is **Javadoc**: the `javadoc` tool turns it into documentation. `@param` documents a parameter, `@return` the result, `@throws` an exception, `{@link ...}` a link, `{@code ...}` code, and `<p>` a new paragraph. The formatter and `-Xlint:all` in this repository expect it.

```java
  /**
   * Tells whether the answer is a correct one in Module 2's sense: 201, or 409 for a seat taken.
   *
   * @return true for {@link #CREATED} and {@link #SEAT_TAKEN}
   */
  public boolean isGood() {
    return this == CREATED || this == SEAT_TAKEN;
  }
```


**Arrays and `StringBuilder`.** An array is a fixed-size row of values of one type: `long[] offsetsNanos = new long[arrivals];` (`OpenModelLoad`, line 132). A `StringBuilder` builds a long text without copying it at each step: `new StringBuilder(HEADER).append('\n')` (`BenchmarkCsv`).

### The minimum Java version of each feature

| Feature | First Java version | Where in this repository |
|---|---|---|
| Lambdas, method references, streams, `Optional`, `Duration`, `Clock` | 8 | everywhere |
| Try-with-resources, `Objects.requireNonNull`, underscores in numbers | 7 | everywhere |
| `List.of`, `Map.of` | 9 | `SizingReport`, `LoadChartSvg` |
| `List.copyOf` | 10 | `ConnectionPool`, records |
| `String.strip`, `isBlank`, `repeat`, `Optional.isEmpty` | 11 | `SettingsReader`, `BenchmarkReport`, `HoldEndpoint` |
| Switch expressions, `yield` | 14 | `SeatWaveNode`, `BenchmarkSettings` |
| Text blocks | 15 | `SeatStore` |
| Records, compact constructors, `instanceof` patterns, `Stream.toList` | 16 | every record, `OpenModelLoad`, `BenchmarkRun` |
| Sealed interfaces | 17 | `Workers` |
| `ExecutorService.close()`, `Thread.sleep(Duration)`, `Thread.join(Duration)` | 19 | `PlatformWorkers`, `HoldEndpoint`, `ThreadCost` |
| Virtual threads, `Thread.ofVirtual`, `Thread.ofPlatform`, `Executors.newThreadPerTaskExecutor`, `newVirtualThreadPerTaskExecutor` | 21 | `VirtualWorkers`, `PlatformWorkers`, `CarrierPinning` |
| Pattern-matching `switch`, record patterns | 21 | `BenchmarkCli`, `SizingCli`, `BenchmarkSettings` |
| `List.getFirst`, `getLast` | 21 | `ConnectionPool`, `BenchmarkSettings`, `SeatWaveNode` |
| Virtual threads no longer pinned by `synchronized` | 24 | `CarrierPinning` (the comment) |

The build sets `options.release = 25`, so the code may use any of them, and `-Xlint:all -Werror` turns every compiler warning into an error. See `gradle.md`.

## Try it yourself

Run these from the repository folder.

**1. Check the Java version.** `java -version` printed `openjdk version "25.0.2" 2026-01-20 LTS`. The build needs 25: the line `languageVersion = JavaLanguageVersion.of(25)` in `build.gradle.kts` asks Gradle for it.

**2. Compile and count.** `./gradlew -q compileJava` printed nothing, exit 0. Then `find build/classes/java/main -name '*.class' | wc -l` printed `58`, from 40 source files, because nested classes, records and enums get their own class files.

**3. See what a record is.** `javap -p -cp build/classes/java/main io.plainround.seatwave.concurrency.node.HoldRequest` printed the three fields, the constructor, `toString`, `hashCode`, `equals` and three accessors, and the header `public final class ... extends java.lang.Record`.

**4. See the sealed list.** `javap -v -cp build/classes/java/main io.plainround.seatwave.concurrency.node.Workers | grep -A3 PermittedSubclasses` printed:

```text
PermittedSubclasses:
  io/plainround/seatwave/concurrency/node/PlatformWorkers
  io/plainround/seatwave/concurrency/node/VirtualWorkers
```

**5. Run a command.** `./gradlew -q sizing --args='config/sizing.properties'` prints the first line `HOLDS`, then `In flight (Little's Law): 500 holds/s x 0.040 s = 20.0 holds`. Change the number in a copy of the file to see how the program responds (`java-sizing-threads-config-walkthrough.md`).

## Mistakes people make

- **Comparing text with `==`.** For objects, `==` compares identity, not content. Use `.equals`. Enums are the exception: `this == CREATED` is correct.
- **Integer division.** `7 / 2` is 3. Use a `double` on one side to get 3.5. `percent / (double) HUNDRED` in `LatencySummary.nearestRank` does this on purpose.
- **Catching an exception and doing nothing.** It hides the failure. Every `catch` here either records a problem, returns a refusal, restores the interrupt flag, or rethrows.
- **A bare `Locale`-dependent format.** `String.format("%.1f", x)` can print `1,5` in some locales. Every format here has `Locale.ROOT`.
- **Returning `null` for "nothing".** The caller forgets to check. `ConnectionPool.borrow` returns an `Optional`.
- **Mutable shared data.** A list changed by two threads breaks. The repository copies with `List.copyOf` and uses records.
- **Forgetting to close a resource.** Try-with-resources closes it on every path.

## Check yourself

1. **What is the difference between a checked and an unchecked exception, and which does `IllegalArgumentException` belong to?** A checked exception must be declared with `throws` and caught or passed on; it means something outside the code failed. An unchecked one needs neither and means a bug. `IllegalArgumentException` is unchecked.
2. **What does the compact constructor of a record do?** It runs before the fields are set and checks or adjusts the arguments. A value it does not throw about is stored.
3. **Why does `SeatWaveNode.start` have no `default` in its `switch`?** A switch expression over an enum must cover every value, so the compiler checks it; adding a mode to `ExecutorMode` makes the build fail until the switch is updated.
4. **Why does `SeatStore` take a `Clock` instead of calling the system clock?** So a test can pass a fixed clock and check hold expiry without waiting.
5. **What does `try (ConnectionPool.Lease lease = borrowed.get())` guarantee?** The lease is closed, and the connection goes back to the pool, on every path out of the block, including an exception.

## Further reading

Official documentation only, for JDK 25:

- The Java Language Specification and the Java SE 25 API documentation: https://docs.oracle.com/en/java/javase/25/language/ and https://docs.oracle.com/en/java/javase/25/docs/api/
- Records, JEP 395: https://openjdk.org/jeps/395
- Sealed classes, JEP 409: https://openjdk.org/jeps/409
- Pattern matching for `switch`, JEP 441, and record patterns, JEP 440: https://openjdk.org/jeps/441 and https://openjdk.org/jeps/440
- Text blocks, JEP 378: https://openjdk.org/jeps/378
- Virtual threads, JEP 444: https://openjdk.org/jeps/444
