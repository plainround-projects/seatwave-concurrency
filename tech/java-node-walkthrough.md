# Java walkthrough: the `node` package

## What it is

This file reads, one source file at a time, the Java package `io.plainround.seatwave.concurrency.node`. A **package** is a named folder of related Java files. This package is one SeatWave node: the part of the service that accepts a request to hold a seat, queues it, runs it on a worker thread, borrows a database connection, runs one SQL statement and answers with a status code. It has thirteen files. There is no web server in it. The benchmark calls the node's `submit` method directly, in the same process, so that the network (which a later module measures) is outside the numbers, and every queue and pool inside the node is still real.

Read `java.md` first: it explains the Java words used here (class, record, enum, sealed interface, `Optional`, try-with-resources) from zero. Read `java-concurrency.md` second: it explains threads, executors, semaphores and the interrupt flag. This file quotes every line of the package and says what each line does in this program.

## Why this repository uses it

Module 4 of the course asks what limits the throughput of one node. The answer in this package is visible in the code: a node has a number of worker threads, a number of database connections, and a bound on every place where a request can wait. A request that finds a bound full is refused at once with status 503 ("service unavailable"). It is never left hanging. The package builds the same node in two thread models, one setting apart, so the benchmark can compare 64 platform threads with one virtual thread per hold on the same load.

## Where it appears

| File | What it does |
|---|---|
| `ExecutorMode.java` | The setting that picks the thread model: `PLATFORM` or `VIRTUAL`. |
| `HoldRequest.java` | One fan's request, with its allow-list checks. |
| `HoldOutcome.java` | Every way a hold can end, each with its HTTP status. |
| `HoldReply.java` | The answer: an outcome and where the time went. |
| `PoolSnapshot.java` | The connection pool's counters at one moment. |
| `NodeSettings.java` | Every limit of a node, read from a settings file with no defaults. |
| `Workers.java` | The sealed interface that runs a hold on a worker or refuses it. |
| `PlatformWorkers.java` | A fixed pool of platform threads with a bounded queue. |
| `VirtualWorkers.java` | One virtual thread per hold, bounded by a semaphore. |
| `ConnectionPool.java` | A hand-written pool of database connections with a bounded wait and counters. |
| `SeatStore.java` | The seats table and the one conditional `UPDATE` that makes a hold atomic. |
| `HoldEndpoint.java` | The hold operation: the door, the queue wait limit, the borrow, the statement. |
| `SeatWaveNode.java` | Wires the workers, the pool and the database from one `NodeSettings`. |

## How it works underneath

**Everything runs in one process.** The benchmark process starts a node with `SeatWaveNode.start`. That opens an in-memory H2 database (see `h2-and-jdbc.md`), opens N connections to it, builds a `ConnectionPool` around them, and builds a `Workers` object for the chosen thread model. Nothing is written to disk and nothing leaves the machine.

**The path of one hold.** The calling thread plays the accept loop of a web server: it calls `HoldEndpoint.submit(request)` and does not wait for the answer.

```text
 caller (accept loop)            worker thread                         database
 --------------------            -------------                         --------
 submit(request)
   read arrival time
   workers.tryExecute(task) --> [executor queue or semaphore]
     full?  --> reply QUEUE_FULL (503) at once
                                 task starts: serve()
                                   queue wait too long?  --> QUEUE_WAIT_EXCEEDED (503)
                                   pool.borrow(limit)  waits for a free connection
                                     none in time?       --> POOL_TIMEOUT (503)
                                   SeatStore.hold(...)  ------------>  one conditional UPDATE
                                     SQL failure?        --> DATABASE_FAILED (503)
                                   sleep databaseWait (connection still held)
                                   lease.close()  connection goes back to the pool
                                 reply.complete(HoldReply)   (201 or 409)
```

**Where each limit refuses with 503, and why.** There are four limits and one more outcome.

1. The **door** (the executor's bound). In platform mode the bound is the capacity of an `ArrayBlockingQueue` in front of the pool: when all workers are busy and the queue is full, the next hold is rejected. In virtual mode the bound is a semaphore with a fixed number of permits: when none is free, the hold is rejected. The answer is `QUEUE_FULL`. Why: if the node accepted every hold, the queue would grow until memory ran out or every hold waited longer than the fan would. A refusal at once lets the fan retry or give up while it still has time.
2. The **queue wait limit**. When a worker finally starts a hold, it checks how long the hold has waited since it arrived. Past `queue.wait.limit.millis` the answer is `QUEUE_WAIT_EXCEEDED`, with no database work. Why: a fan whose own timeout has nearly passed does not need an answer, and doing the work anyway would waste a connection on a reply nobody reads.
3. The **borrow limit**. `ConnectionPool.borrow` waits at most `pool.borrow.limit.millis` for a free connection. Nothing in time gives `POOL_TIMEOUT`. Why: the pool is a queue too, and an unbounded wait for a connection is the most common way a node hangs.
4. The **statement timeout**. The `UPDATE` has a query timeout in seconds. If the database cannot finish it, JDBC throws `SQLException` and the answer is `DATABASE_FAILED`. Why: the seat's state is then unknown, so the only safe answer is a refusal that the fan can retry.
5. An **interrupt** gives `INTERRUPTED`. It happens only when the node shuts down.

All five answers are 503. The only good answers are 201 (the seat is held) and 409 (another fan has it). `HoldOutcome.isGood()` is true for exactly those two.

**Why the limits matter, with a real run.** `config/unbounded.properties` opens every limit wide: a queue of 100,000 and waits of a minute. I ran `./gradlew -q benchmark --args='config/unbounded.properties build/bench'`. At 600 holds per second, which is more than 16 connections can serve (400 per second), the node refused nothing (`503q` and `503p` both 0) and instead let the queue grow to 2,262 holds. The p99 was 1,980 ms and 69.2 percent of the holds got no good reply within the client's 2-second patience. With the limits of `config/simple.properties` the same overload ends in 503 answers in a few milliseconds, which a fan can act on. An unbounded queue turns overload into waiting. A bounded one turns it into a clear refusal.

**Why the pool, not the threads, is the queue.** A hold keeps its connection for the database wait (40 ms) after the statement. Sixteen connections, each held 40 ms, can serve 16 / 0.040 = 400 holds per second. That is the capacity of the node whatever the thread model: platform threads or virtual threads only decide where the waiting holds sit.

## Walkthrough of this repository's use

Each section below quotes a file in order, in fenced blocks copied from the source, then explains it. The order is: the small data types first, then the settings, the two thread models, the pool, the SQL, and last the endpoint and the node that join them.

### `ExecutorMode.java`

The setting that says how a node runs its holds. It is an **enum**: a type with a fixed list of named values.

**Lines 1 to 1: package and imports**

```java
package io.plainround.seatwave.concurrency.node;
```


**Lines 2 to 12: the enum**

```java
/**
 * How a SeatWave node runs each hold: on a fixed pool of platform threads, or on its own virtual
 * thread.
 */
public enum ExecutorMode {
  /** A fixed pool of operating-system threads with a bounded queue in front of it. */
  PLATFORM,
  /** One new virtual thread per hold, with a bound on how many may be in flight. */
  VIRTUAL
}
```

- `public enum ExecutorMode` declares the type. An enum has exactly the values listed, here `PLATFORM` and `VIRTUAL`. A variable of this type can hold nothing else, so a misspelled mode cannot exist. `NodeSettings` stores one, `SeatWaveNode.start` switches on it, and `ThreadCost` and `LoadChartSvg` use it as a label and as a map key.
- The text between `/**` and `*/` is **Javadoc**, a comment that the `javadoc` tool turns into documentation. The `/** ... */` above each value documents that value.
- `PLATFORM` is a fixed pool of operating-system threads with a bounded queue in front. `VIRTUAL` is one new virtual thread per hold, with a cap on how many may be in flight. Enums have been in Java since version 5.

### `HoldRequest.java`

One fan's request to hold one seat. It is a **record**: a short class that only carries values (Java 16 or newer).

**Lines 1 to 4: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.util.Objects;
import java.util.regex.Pattern;
```

- `Objects`: helpers such as `requireNonNull`.
- `Pattern`: a compiled regular expression.

**Lines 5 to 13: the Javadoc and the record header**

```java
/**
 * One fan's request to hold one seat: the body of {@code POST /holds} from Module 1.
 *
 * @param eventId the event, 1 or more
 * @param seatLabel the seat, such as {@code A-7}: a section letter from A to T and a number
 * @param customer the fan's identifier: letters, digits, underscore and hyphen, at most 64
 */
public record HoldRequest(long eventId, String seatLabel, String customer) {
```

- `@param` lines document the record's **components**. A record header `record HoldRequest(long eventId, String seatLabel, String customer)` declares three components. The compiler writes the fields, the constructor, the accessor methods `eventId()`, `seatLabel()` and `customer()`, and `equals`, `hashCode` and `toString`.
- `long` is a 64-bit whole number. `String` is text.
- `POST /holds` is the request of SeatWave version 0 (Module 1). This repository has no HTTP layer, but the record keeps the same body.

**Lines 14 to 17: two allow-lists**

```java
  // The same allow-lists as SeatWave version 0, so a request valid there is valid here.
  private static final Pattern SEAT_LABEL_PATTERN = Pattern.compile("[A-T]-[0-9]{1,4}");
  private static final Pattern CUSTOMER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
```

- A line that starts with `//` is a comment. The compiler ignores it. This one says the patterns copy version 0's, so a request that was valid there is valid here.
- `private static final` means: visible only inside this record, one copy for the whole class, and never reassigned. `Pattern` is a compiled **regular expression**, a small language for describing the shape of a text.
- `"[A-T]-[0-9]{1,4}"`: one letter from A to T, a hyphen, then one to four digits. `A-7` and `T-1000` match. `U-1` and `A-12345` do not.
- `"[A-Za-z0-9_-]{1,64}"`: one to 64 characters, each a letter, a digit, an underscore or a hyphen. A name with a space, a quote or a semicolon does not match. An **allow-list** names what is allowed and refuses the rest, which is safer than a list of forbidden characters.

**Lines 18 to 37: the compact constructor**

```java
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
}
```

- `public HoldRequest { ... }` with no parentheses is a **compact constructor** (records only). It runs first, before the compiler stores the arguments in the fields. Its job is to refuse bad values. Whatever it does not throw about is accepted.
- `Objects.requireNonNull(seatLabel, "seatLabel")` throws `NullPointerException` with the message `seatLabel` if the value is null. Null is a programmer error here, so an exception is the right answer.
- `eventId < 1` throws `IllegalArgumentException`: the event number must be 1 or more.
- `SEAT_LABEL_PATTERN.matcher(seatLabel).matches()` is true only if the **whole** text fits the pattern. `find()` would accept a match anywhere inside, which would let `A-7; DROP` through. The `!` flips it: not matching means refuse.
- The messages `invalid seat label` and `invalid customer` do not repeat the bad value. A message that echoes input can leak or inject text into a log.
- Because the constructor refuses bad values, every `HoldRequest` that exists is valid. Code that receives one never checks again.

### `HoldOutcome.java`

Every way a hold can end, with the HTTP status SeatWave answers with. An enum whose values carry a number.

**Lines 1 to 1: package and imports**

```java
package io.plainround.seatwave.concurrency.node;
```


**Lines 2 to 18: the seven outcomes**

```java
/** Every way a hold can end, each with the HTTP status SeatWave answers with. */
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
```

- `CREATED(201)` is written as a name followed by a value in parentheses. The value goes to the constructor below. 201 means "created": the seat is now held.
- `SEAT_TAKEN(409)` is 409, "conflict": another fan holds or owns the seat. It is a correct and expected answer, not a failure.
- `QUEUE_FULL(503)` is the door refusing: the executor's bound was reached. 503 means "service unavailable, try again".
- `QUEUE_WAIT_EXCEEDED(503)` is the queue wait limit. `POOL_TIMEOUT(503)` is the borrow limit. `DATABASE_FAILED(503)` is a database failure or a statement timeout. `INTERRUPTED(503)` is a worker interrupted at shutdown.
- The list ends with `;` because the enum has more members after it.

**Lines 19 to 24: the field and the constructor**

```java
  private final int httpStatus;

  HoldOutcome(int httpStatus) {
    this.httpStatus = httpStatus;
  }
```

- `private final int httpStatus` is a field that is set once. `final` means it cannot change after the constructor.
- `HoldOutcome(int httpStatus)` has no `public`: the constructor of an enum is always private, because only the listed values may exist. `this.httpStatus = httpStatus` stores the argument in the field. `this.` tells the compiler that the left name is the field and the right name is the parameter.

**Lines 25 to 33: the status accessor**

```java
  /**
   * Returns the HTTP status for this outcome.
   *
   * @return 201, 409 or 503
   */
  public int httpStatus() {
    return httpStatus;
  }
```

- `public int httpStatus()` returns the number. The Javadoc says "201, 409 or 503", which are all the numbers used.

**Lines 34 to 43: the good-answer test**

```java
  /**
   * Tells whether the answer is a correct one in Module 2's sense: 201, or 409 for a seat taken.
   *
   * @return true for {@link #CREATED} and {@link #SEAT_TAKEN}
   */
  public boolean isGood() {
    return this == CREATED || this == SEAT_TAKEN;
  }
}
```

- `isGood()` is true for `CREATED` and `SEAT_TAKEN`. This is Module 2's meaning of a correct answer: 201, or 409 for a seat that is taken.
- `this == CREATED` compares by identity. For an enum that is correct and safe, because only one object exists for each name. `||` means "or".
- The benchmark counts `errors%` as the holds that did not get a good answer in time, so this method decides what an error is.

### `HoldReply.java`

The answer to one hold, with where its time went inside the node. A record.

**Lines 1 to 3: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.util.Objects;
```

- `Objects`: helpers such as `requireNonNull`.

**Lines 4 to 14: the header**

```java
/**
 * The answer to one hold, with where its time went inside the node.
 *
 * @param outcome how the hold ended
 * @param queueWaitNanos time from arrival to the start of the work: the executor queue
 * @param poolWaitNanos time spent waiting for a database connection; 0 if it never borrowed
 * @param workNanos time holding the connection: the statement and the database's own wait
 */
public record HoldReply(
    HoldOutcome outcome, long queueWaitNanos, long poolWaitNanos, long workNanos) {
```

- The four components are the outcome and three times in **nanoseconds** (a nanosecond is one billionth of a second). `queueWaitNanos` is the time from arrival to the start of the work. `poolWaitNanos` is the time spent waiting for a connection. `workNanos` is the time the connection was held: the statement plus the database wait.
- These three numbers are how the benchmark can say which queue a slow hold waited in. The times are `long`, a 64-bit number, because a nanosecond count of a few seconds is far larger than an `int` can hold.

**Lines 15 to 26: the compact constructor**

```java
  /**
   * Creates the reply.
   *
   * @throws IllegalArgumentException if a duration is negative
   */
  public HoldReply {
    Objects.requireNonNull(outcome, "outcome");
    if (queueWaitNanos < 0 || poolWaitNanos < 0 || workNanos < 0) {
      throw new IllegalArgumentException("a duration is never negative");
    }
  }
```

- It refuses a null outcome with `Objects.requireNonNull`, and any negative duration. A time that has passed cannot be negative, so a negative one means a bug, not bad input, and so it is an exception.

**Lines 27 to 37: a reply that spent no time**

```java
  /**
   * Creates a refusal that spent no time in the node.
   *
   * @param outcome the refusal
   * @return the reply
   */
  public static HoldReply refusedAtTheDoor(HoldOutcome outcome) {
    return new HoldReply(outcome, 0, 0, 0);
  }
}
```

- `refusedAtTheDoor` is a **static factory method**: a method on the class that makes an instance. `static` means you call it on the type, `HoldReply.refusedAtTheDoor(...)`, with no object. It builds a reply with three zero times, for a hold that was refused before it entered the node. A name that says what it is for reads better than `new HoldReply(outcome, 0, 0, 0)`.

### `PoolSnapshot.java`

The connection pool's counters at one moment. A record that checks that the counters are possible.

**Lines 1 to 3: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.util.Locale;
```

- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.

**Lines 4 to 13: the header**

```java
/**
 * The saturation counters of a connection pool at one moment.
 *
 * @param size connections in the pool
 * @param busy connections lent out now
 * @param waitingBorrowers threads waiting for a connection now
 * @param borrowTimeouts borrows that gave up since the pool started
 */
public record PoolSnapshot(int size, int busy, int waitingBorrowers, long borrowTimeouts) {
```

- `size` is the connections the pool owns. `busy` is how many are lent out. `waitingBorrowers` is how many threads are waiting for one right now. `borrowTimeouts` is how many borrows gave up since the pool started.
- These are the **saturation counters**: how full the pool is. The sampler in the benchmark reads them every few milliseconds.
- `java.util.Locale` is imported for the message below.

**Lines 14 to 32: the compact constructor**

```java
  /**
   * Creates the snapshot.
   *
   * @throws IllegalArgumentException if a count is negative or more connections are busy than exist
   */
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
}
```

- The single `if` rejects every impossible combination: a pool smaller than 1, a negative count, or more busy connections than exist. `||` is "or" and `>` is "greater than".
- `String.format(Locale.ROOT, "...%d...", values)` builds text from a template. `%d` is a place for a whole number, filled by the next argument. `Locale.ROOT` means "no particular country", so a number prints the same on every machine. Without it, a machine set to a language that groups digits differently could print a different message. The message names every counter so the log shows which one was wrong.
- The values are read separately while threads are running, so a snapshot may be a moment out of step. `ConnectionPool.snapshot` says so in its Javadoc. The check here rejects only the impossible.

### `NodeSettings.java`

Every limit of one node, as one record. The values come from a settings file; nothing has a default in code.

**Lines 1 to 7: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

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

**Lines 8 to 24: the header**

```java
/**
 * Every limit of one SeatWave node. The values come from a settings file; there are no defaults in
 * code, so a missing key stops the program before it serves anything.
 *
 * @param mode the thread model
 * @param platformWorkers threads in the fixed pool (platform mode)
 * @param executorQueueBound holds that may wait for a free platform thread
 * @param virtualMaxInFlight holds that may be in flight at once (virtual mode)
 * @param queueWaitLimit the longest a hold may wait between arrival and the start of its work
 * @param poolConnections database connections in the pool
 * @param poolBorrowLimit the longest a hold may wait for a connection
 * @param databaseWait the time each hold keeps its connection, standing for a remote database
 * @param statementTimeoutSeconds the JDBC query timeout of the hold statement
 * @param databaseUrl the JDBC URL of the embedded database
 */
public record NodeSettings(
```

- The ten components are the whole configuration of a node. The record is **immutable**: once built, no value changes. A "change" means building a new record (see `withMode` below).
- `ExecutorMode mode` is the thread model. `platformWorkers` is the pool size and `executorQueueBound` the number of holds that may wait for it. `virtualMaxInFlight` is the cap in virtual mode. `queueWaitLimit` is a `Duration`, a length of time. `poolConnections`, `poolBorrowLimit` and `databaseWait` size the pool, bound the wait for a connection and set how long each hold keeps its connection. `statementTimeoutSeconds` is the JDBC query timeout. `databaseUrl` is where the database lives.
- The imports `Result` and `SettingsReader` are used by `from`, further down.

**Lines 25 to 37: the largest count**

```java
    ExecutorMode mode,
    int platformWorkers,
    int executorQueueBound,
    int virtualMaxInFlight,
    Duration queueWaitLimit,
    int poolConnections,
    Duration poolBorrowLimit,
    Duration databaseWait,
    int statementTimeoutSeconds,
    String databaseUrl) {

  /** The largest thread or connection count a settings file may ask for. */
  public static final int MAX_COUNT = 100_000;
```

- Lines 25 to 34 list the components, one per line.
- `MAX_COUNT = 100_000` is the largest thread or connection count a settings file may ask for. The underscore in a number is only for reading: `100_000` is 100000 (Java 7 or newer). `public static final` makes it a constant that other classes can read as `NodeSettings.MAX_COUNT`.

**Lines 38 to 41: two more limits**

```java
  // A minute is far above any budget in this module; a larger wait is a typo, not a design.
  private static final int MAX_WAIT_MILLIS = 60_000;
  private static final int MAX_STATEMENT_TIMEOUT_SECONDS = 60;
```

- The comment says why 60,000 ms is the maximum wait: a minute is far above any budget in this module, so a larger value is a typo, not a design.
- `MAX_STATEMENT_TIMEOUT_SECONDS = 60` caps the query timeout in seconds.

**Lines 42 to 76: the compact constructor**

```java
  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a count is below 1, a wait is negative, or the URL is not
   *     an H2 in-memory URL
   */
  public NodeSettings {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(queueWaitLimit, "queueWaitLimit");
    Objects.requireNonNull(poolBorrowLimit, "poolBorrowLimit");
    Objects.requireNonNull(databaseWait, "databaseWait");
    Objects.requireNonNull(databaseUrl, "databaseUrl");
    for (int count :
        new int[] {
          platformWorkers,
          executorQueueBound,
          virtualMaxInFlight,
          poolConnections,
          statementTimeoutSeconds
        }) {
      if (count < 1) {
        throw new IllegalArgumentException("every count and timeout must be 1 or more: " + count);
      }
    }
    for (Duration wait : new Duration[] {queueWaitLimit, poolBorrowLimit, databaseWait}) {
      if (wait.isNegative()) {
        throw new IllegalArgumentException("a wait is never negative: " + wait);
      }
    }
    // Only an in-memory database: the benchmark must never write files or reach another host.
    if (!databaseUrl.startsWith("jdbc:h2:mem:")) {
      throw new IllegalArgumentException("only jdbc:h2:mem: URLs are allowed: " + databaseUrl);
    }
  }
```

- The five `requireNonNull` calls refuse null for every object component. The `int` components cannot be null.
- `for (int count : new int[] {...})` is an enhanced `for` loop over a small array made in place. Each pass takes one of the five counts. `count < 1` throws `IllegalArgumentException` with the bad number in the message. The array is written one entry per line because the formatter puts one entry per line when a list does not fit.
- The second loop does the same for the three durations with `wait.isNegative()`. A wait of zero is allowed (no wait at all), a negative one is not.
- `databaseUrl.startsWith("jdbc:h2:mem:")` refuses any URL that is not an H2 **in-memory** database. The comment gives the reason: the benchmark must never write files or reach another host. `jdbc:h2:mem:name` lives only in this process's memory. A `jdbc:h2:file:` or `jdbc:postgresql:` URL would not pass.

**Lines 77 to 119: reading the settings**

```java
  /**
   * Reads the node keys from a settings file. The mode is not read here: the benchmark runs the
   * same settings once per mode in {@code executor.modes}.
   *
   * @param reader the settings reader, which collects every problem
   * @param mode the thread model to use
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<NodeSettings, List<String>> from(SettingsReader reader, ExecutorMode mode) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(mode, "mode");
    int workers = reader.intBetween("platform.workers", 1, MAX_COUNT);
    int queueBound = reader.intBetween("platform.queue.bound", 1, MAX_COUNT);
    int maxInFlight = reader.intBetween("virtual.max.in.flight", 1, MAX_COUNT);
    Duration queueWait = reader.millisBetween("queue.wait.limit.millis", 0, MAX_WAIT_MILLIS);
    int connections = reader.intBetween("pool.connections", 1, MAX_COUNT);
    Duration borrowLimit = reader.millisBetween("pool.borrow.limit.millis", 0, MAX_WAIT_MILLIS);
    Duration databaseWait = reader.millisBetween("database.wait.millis", 0, MAX_WAIT_MILLIS);
    int statementTimeout =
        reader.intBetween("database.statement.timeout.seconds", 1, MAX_STATEMENT_TIMEOUT_SECONDS);
    String url = reader.text("database.url");
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    try {
      return Result.success(
          new NodeSettings(
              mode,
              workers,
              queueBound,
              maxInFlight,
              queueWait,
              connections,
              borrowLimit,
              databaseWait,
              statementTimeout,
              url));
    } catch (IllegalArgumentException inconsistent) {
      // Each key was valid alone, but the URL breaks a rule the record enforces.
      return Result.error(List.of(inconsistent.getMessage()));
    }
  }
```

- `from` is a static method that returns `Result<NodeSettings, List<String>>`: either the settings, or a list of problem lines. See `result4j.md`. It does not throw for a bad settings file, because a typo in a file is an expected failure, not a bug.
- The `Javadoc` says the mode is not read from the file here: the benchmark runs the same settings once per mode in `executor.modes`, so it passes the mode in.
- Each `reader.intBetween("platform.workers", 1, MAX_COUNT)` reads one key as a whole number from 1 to `MAX_COUNT`. If the key is missing or out of range the reader records a problem line and returns a harmless stand-in, so the code carries on and **every** problem is collected in one pass. `millisBetween` does the same and returns a `Duration`. `reader.text` reads the URL. The keys are listed in `java-properties.md`.
- `if (!reader.problems().isEmpty()) return Result.error(reader.problems())` stops before building a record from stand-in values. This line is why the stand-ins are harmless.
- `Result.success(new NodeSettings(...))` builds the record. The long argument list is in the same order as the components.
- The `catch (IllegalArgumentException inconsistent)` handles the case where each key was valid alone but the record refuses the whole, such as a URL that is not `jdbc:h2:mem:`. The message becomes one problem line.

**Lines 120 to 139: a copy with another mode**

```java
  /**
   * Returns the same settings with another thread model.
   *
   * @param otherMode the thread model
   * @return a copy with the mode replaced
   */
  public NodeSettings withMode(ExecutorMode otherMode) {
    return new NodeSettings(
        otherMode,
        platformWorkers,
        executorQueueBound,
        virtualMaxInFlight,
        queueWaitLimit,
        poolConnections,
        poolBorrowLimit,
        databaseWait,
        statementTimeoutSeconds,
        databaseUrl);
  }
```

- `withMode` returns a new record equal to this one except for the mode. Records are immutable, so changing one field means copying the rest. The benchmark uses it to run the same limits in `PLATFORM` and then in `VIRTUAL`.

**Lines 140 to 161: a copy with another URL**

```java
  /**
   * Returns the same settings with another database URL, so two nodes in one JVM never share a
   * database.
   *
   * @param otherUrl the JDBC URL
   * @return a copy with the URL replaced
   */
  public NodeSettings withDatabaseUrl(String otherUrl) {
    return new NodeSettings(
        mode,
        platformWorkers,
        executorQueueBound,
        virtualMaxInFlight,
        queueWaitLimit,
        poolConnections,
        poolBorrowLimit,
        databaseWait,
        statementTimeoutSeconds,
        otherUrl);
  }
}
```

- `withDatabaseUrl` does the same for the URL. `BenchmarkRun` appends the mode name to the URL, so two nodes in one JVM never share a database: `jdbc:h2:mem:seatwave-simple-platform` and `jdbc:h2:mem:seatwave-simple-virtual` are two separate in-memory databases.

### `Workers.java`

The sealed interface that every thread model implements: run a hold on a worker, or refuse it at once.

**Lines 1 to 1: package and imports**

```java
package io.plainround.seatwave.concurrency.node;
```


**Lines 2 to 10: the Javadoc and the declaration**

```java
/**
 * Runs holds on worker threads and refuses a hold at once when the bound is reached.
 *
 * <p>The interface is sealed because the module compares exactly two thread models. Every
 * implementation is thread-safe: {@link #tryExecute} is called by the accept loop while workers
 * finish other holds.
 */
public sealed interface Workers extends AutoCloseable permits PlatformWorkers, VirtualWorkers {
```

- `public sealed interface Workers extends AutoCloseable permits PlatformWorkers, VirtualWorkers` says three things. `interface` is a list of methods without bodies that classes promise to provide. `sealed ... permits` means only the two named classes may implement it (Java 17 or newer), so the compiler knows the full list. The Javadoc gives the reason: the module compares exactly two thread models. `extends AutoCloseable` means it has a `close()` method and may be used in a `try (...)` block, which closes it automatically.
- "Every implementation is thread-safe" is the contract: `tryExecute` is called by the accept loop while workers finish other holds, which is two threads at once.

**Lines 11 to 18: refuse or run**

```java
  /**
   * Hands a task to a worker, or refuses it.
   *
   * @param task the work of one hold; it must finish in bounded time
   * @return true if the task was accepted, false if the bound was reached and nothing will run
   */
  boolean tryExecute(Runnable task);
```

- `boolean tryExecute(Runnable task)`: a `Runnable` is a piece of code with no input and no result. The method returns `true` if a worker will run it and `false` if the bound was reached. When it returns `false`, nothing will run the task, so the caller must answer the fan itself. This is how a refusal at the door is spelled.

**Lines 19 to 25: threads alive**

```java
  /**
   * Counts the threads that exist for SeatWave work now.
   *
   * @return the pool size in platform mode; the virtual threads in flight in virtual mode
   */
  int threadsAlive();
```

- `int threadsAlive()` counts the threads that exist for SeatWave work: the pool size in platform mode, the holds in flight in virtual mode. The sampler reads it for the `threads` column.

**Lines 26 to 33: queued**

```java
  /**
   * Counts the holds accepted but not yet started.
   *
   * @return the executor queue length in platform mode; 0 in virtual mode, where every accepted
   *     hold starts on its own thread at once
   */
  int queued();
```

- `int queued()` counts holds accepted but not yet started: the executor queue length in platform mode, and always 0 in virtual mode because every accepted hold starts at once on its own thread.

**Lines 34 to 41: close**

```java
  /**
   * Stops accepting work and waits for the accepted holds to finish. The wait is bounded because
   * every hold is bounded by the queue, pool and database limits.
   */
  @Override
  void close();
}
```

- `void close()` overrides `AutoCloseable.close` and drops its `throws Exception`, so a caller needs no `try`/`catch` for it. `@Override` asks the compiler to check that a method with this name exists above. The Javadoc says the wait is bounded, because every hold is bounded by the queue, pool and database limits.

### `PlatformWorkers.java`

SeatWave version 0's thread model with the queue bound that version 0 lacked: a fixed pool of platform threads and a bounded queue in front of it.

**Lines 1 to 6: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
```

- `ArrayBlockingQueue`: a bounded queue that threads can wait on.
- `RejectedExecutionException`: thrown when an executor refuses a task.
- `ThreadPoolExecutor`: the standard pool of platform threads.
- `TimeUnit`: a unit of time for waits, such as nanoseconds.

**Lines 7 to 20: constants and the field**

```java
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
```

- `final class` means no other class may extend it. `implements Workers` is the promise to provide the four methods.
- `THREAD_NAME_PREFIX` gives each worker thread a name that starts with `seatwave-worker-`. The thread census in the benchmark finds the workers by that prefix.
- The comment explains `UNUSED_KEEP_ALIVE`: core size and maximum size are equal, so the pool never shrinks and no thread ever times out. The value is required by the constructor but has no effect.
- `ThreadPoolExecutor executor` is the standard Java pool of platform threads. See `java-concurrency.md`.

**Lines 21 to 44: the constructor**

```java
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
```

- The check refuses a pool or a queue smaller than 1.
- `new ThreadPoolExecutor(core, max, keepAlive, unit, queue, factory, handler)` takes seven arguments. `workerThreads` twice: core and maximum size are the same, so the pool is **fixed**. `UNUSED_KEEP_ALIVE` and `TimeUnit.MILLISECONDS` are the keep-alive time that never matters. `new ArrayBlockingQueue<>(queueBound)` is the **bounded queue**: it holds at most `queueBound` waiting holds. `<>` lets the compiler infer the element type.
- `Thread.ofPlatform().name(THREAD_NAME_PREFIX, 1).factory()` builds a **thread factory**: the object the pool calls whenever it needs a new thread. `ofPlatform()` asks for platform (operating-system) threads, `name(prefix, 1)` numbers the threads from 1 (`seatwave-worker-1`, `seatwave-worker-2`, ...), and `factory()` gives the factory. This is the Java 21 `Thread.Builder` API.
- `new ThreadPoolExecutor.AbortPolicy()` is the **rejected-execution handler**. When the queue is full and all threads are busy, the policy throws `RejectedExecutionException`. The comment says why it is not the "caller runs" policy: running a hold on the accept loop would stall every other arrival behind it.
- `executor.prestartAllCoreThreads()` starts all the threads now. Without it, a pool starts a thread only when a task arrives, so the first holds would pay for thread creation.

**Lines 45 to 53: the name prefix**

```java
  /**
   * Returns the prefix of every worker thread's name, so a thread census can find them.
   *
   * @return the prefix
   */
  public static String threadNamePrefix() {
    return THREAD_NAME_PREFIX;
  }
```

- A static method that returns the prefix, so `OpenModelLoad` can build its census group list without repeating the string.

**Lines 54 to 64: trying to run a hold**

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

- `executor.execute(task)` hands the task to the pool. The pool's rule, in order: if fewer than the core number of threads exist, start one; otherwise put the task in the queue; if the queue is full, try to add a thread up to the maximum; if that is not possible either, call the handler. Here core and maximum are equal, so the last step goes straight to the handler.
- If the handler throws `RejectedExecutionException`, the `catch` returns `false`. The comment says this is the refusal the module teaches, not an error to log. The variable is named `queueFull` so the reader sees what the exception means here.

**Lines 65 to 69: threads alive**

```java
  @Override
  public int threadsAlive() {
    return executor.getPoolSize();
  }
```

- `getPoolSize()` is the number of threads now in the pool: the fixed size.

**Lines 70 to 74: queued**

```java
  @Override
  public int queued() {
    return executor.getQueue().size();
  }
```

- `executor.getQueue().size()` is how many tasks wait in the queue now.

**Lines 75 to 80: close**

```java
  @Override
  public void close() {
    executor.close();
  }
}
```

- `executor.close()` is a Java 19 method of `ExecutorService`. It stops accepting new tasks, waits for the accepted ones to finish, and if the calling thread is interrupted while it waits, it stops the running ones. This is why a `try (...)` block can own an executor. See `java-concurrency.md`.

### `VirtualWorkers.java`

The other thread model: one new virtual thread for every hold, with a semaphore that caps how many holds are in flight.

**Lines 1 to 6: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
```

- `ExecutorService`: something that runs tasks on threads and can be closed.
- `Executors`: factory methods that build executors.
- `RejectedExecutionException`: thrown when an executor refuses a task.
- `Semaphore`: a counter of permits that limits how many threads proceed.

**Lines 7 to 22: constants and fields**

```java
/**
 * One new virtual thread per hold, with a semaphore that caps how many holds are in flight.
 *
 * <p>Virtual threads are cheap, so they are never pooled; the semaphore, not a pool, is the bound,
 * as the virtual-thread JEP recommends. A hold that finds no permit is refused at once.
 *
 * <p>Thread-safe: the executor and the semaphore are.
 */
public final class VirtualWorkers implements Workers {

  private static final String THREAD_NAME_PREFIX = "seatwave-virtual-";

  private final int maxInFlight;
  private final Semaphore inFlightPermits;
  private final ExecutorService executor;
```

- The class comment gives the design rule: virtual threads are cheap, so they are **never pooled**. A pool would bring back the queue that virtual threads remove. A semaphore is the bound, as the virtual-thread proposal recommends.
- `maxInFlight` is the cap. `Semaphore inFlightPermits` is the counter of free slots. `ExecutorService executor` runs the tasks.

**Lines 23 to 38: the constructor**

```java
  /**
   * Creates the executor.
   *
   * @param maxInFlight the most holds that may run or wait at once
   */
  public VirtualWorkers(int maxInFlight) {
    if (maxInFlight < 1) {
      throw new IllegalArgumentException("maxInFlight must be 1 or more: " + maxInFlight);
    }
    this.maxInFlight = maxInFlight;
    this.inFlightPermits = new Semaphore(maxInFlight);
    this.executor =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name(THREAD_NAME_PREFIX, 1).factory());
  }
```

- The check refuses a cap below 1, and the message includes the bad value.
- `new Semaphore(maxInFlight)` starts with that many permits. Each hold takes one and gives it back when it ends.
- `Executors.newThreadPerTaskExecutor(factory)` (Java 21) makes an executor that starts a **new thread for every task** and does not queue. The factory comes from `Thread.ofVirtual().name(prefix, 1).factory()`, so each thread is virtual and named `seatwave-virtual-1`, `seatwave-virtual-2` and then up by one for each thread. Virtual threads cost so little that making one per hold is the normal use.

**Lines 39 to 60: trying to run a hold**

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

- `inFlightPermits.tryAcquire()` takes a permit if one is free and returns `false` at once if not. The comment: it never waits, because the accept loop must not block, so a full node answers at once. `!` turns "no permit" into `return false`, which means "refused".
- After a permit is taken, the code must give it back on every path. The task handed to the executor is a **lambda**, a short piece of code written in place: `() -> { ... }`. It runs `task.run()` inside `try`, and the `finally` block calls `release()` whether the task finished or threw. So a failing hold cannot leak a permit.
- `catch (RejectedExecutionException closed)` handles the executor already being closed. The code gives the permit back and returns `false`, as a full node would. Without this line a late call during shutdown would lose a permit forever.

**Lines 61 to 65: threads alive**

```java
  @Override
  public int threadsAlive() {
    return maxInFlight - inFlightPermits.availablePermits();
  }
```

- `maxInFlight - inFlightPermits.availablePermits()` is the permits in use, which equals the holds in flight, which equals the virtual threads alive for SeatWave work. No virtual thread is counted by asking the JVM, because the JVM does not list them (see `ThreadCensus`).

**Lines 66 to 70: queued**

```java
  @Override
  public int queued() {
    return 0;
  }
```

- Always 0: a hold that gets a permit starts on its own thread at once, so nothing waits in a queue.

**Lines 71 to 76: close**

```java
  @Override
  public void close() {
    executor.close();
  }
}
```

- `executor.close()` waits for the running holds to finish. A hold is bounded by the pool and database limits, so the wait is bounded.

### `ConnectionPool.java`

A fixed set of database connections that holds borrow, use and give back. It is hand-written on purpose: the pool is what the module teaches.

**Lines 1 to 14: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
```

- `Connection`: one open connection to a database (JDBC).
- `SQLException`: the checked exception for a database failure.
- `Duration`: a length of time.
- `ArrayList`: a growable list.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.
- `Optional`: a box that holds a value or nothing.
- `ArrayBlockingQueue`: a bounded queue that threads can wait on.
- `BlockingQueue`: the interface of a queue with waiting operations.
- `TimeUnit`: a unit of time for waits, such as nanoseconds.
- `AtomicInteger`: an integer that threads can update safely.
- `LongAdder`: a counter built for many threads adding at once.

Nothing new is needed beyond the list above.

**Lines 15 to 32: the class comment and the fields**

```java
/**
 * A fixed set of database connections that holds borrow, use and give back.
 *
 * <p>Every borrow waits at most its limit; a borrower that gets nothing in time receives an empty
 * result and the caller refuses the hold. The counters show saturation: how many borrowers are
 * waiting right now, how many connections are busy, how many borrows timed out.
 *
 * <p>Thread-safe: the idle connections live in a fair blocking queue, so waiting borrowers are
 * served in arrival order, and every counter is atomic. A connection is used by one borrower at a
 * time, between {@link #borrow} and {@link Lease#close}.
 */
public final class ConnectionPool implements AutoCloseable {

  private final List<Connection> allConnections;
  private final BlockingQueue<Connection> idleConnections;
  private final AtomicInteger waitingBorrowers = new AtomicInteger();
  private final LongAdder borrowTimeouts = new LongAdder();
```

- The Javadoc says what the pool promises. Every borrow waits at most its limit. A borrower that gets nothing receives an empty result and the caller refuses the hold. Idle connections live in a **fair** blocking queue, so waiting borrowers are served in arrival order, and every counter is atomic.
- `allConnections` is the list of every connection, for counting and closing. `idleConnections` is the queue of connections that nobody is using. A connection is either in that queue or lent out.
- `AtomicInteger waitingBorrowers` counts the threads waiting now. It goes up before a wait and down after, so it can go both ways. `LongAdder borrowTimeouts` counts the borrows that gave up. It only goes up, and a `LongAdder` is built for many threads adding at once.

**Lines 33 to 48: the constructor**

```java
  /**
   * Creates a pool around connections that are already open. The pool owns them from now on and
   * closes them in {@link #close}.
   *
   * @param connections the open connections; at least one
   */
  public ConnectionPool(List<Connection> connections) {
    Objects.requireNonNull(connections, "connections");
    if (connections.isEmpty()) {
      throw new IllegalArgumentException("a pool needs at least one connection");
    }
    this.allConnections = List.copyOf(connections);
    // Fair: a borrower that has waited longest gets the next connection, so no hold starves.
    this.idleConnections = new ArrayBlockingQueue<>(allConnections.size(), true, allConnections);
  }
```

- The pool takes **open** connections and owns them from now on. It refuses a null list and an empty list.
- `List.copyOf(connections)` makes an unchangeable copy, so the caller cannot add or remove a connection behind the pool's back.
- `new ArrayBlockingQueue<>(allConnections.size(), true, allConnections)` is the **fair** queue. Its three arguments: capacity (as many slots as connections), `true` for fairness, and the starting contents, which is every connection. The comment says why fairness matters: a borrower that has waited longest gets the next connection, so no hold starves.

**Lines 49 to 70: borrowing**

```java
  /**
   * Waits up to the limit for a free connection.
   *
   * @param limit the longest this call may wait
   * @return a lease on one connection, or empty if none became free in time
   * @throws InterruptedException if the waiting thread is interrupted
   */
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

- `Optional<Lease> borrow(Duration limit) throws InterruptedException` returns a `Lease` or an empty `Optional`. `throws InterruptedException` declares that the wait can be interrupted, and the caller must handle that.
- `waitingBorrowers.incrementAndGet()` adds 1 in one atomic step before the wait starts.
- `idleConnections.poll(limit.toNanos(), TimeUnit.NANOSECONDS)` waits up to the limit for a connection to appear. It returns the connection as soon as one is there, or `null` when the time runs out. This one call is the **borrow limit**: the wait is bounded by the clock, not by hope.
- `connection == null` means the wait ran out. The code counts the timeout with `borrowTimeouts.increment()` and returns `Optional.empty()`.
- Otherwise `Optional.of(new Lease(connection))` wraps the connection in a lease.
- The `finally` block runs on every path, including a return and an exception, so `decrementAndGet()` always matches the earlier increment. If the waiting thread is interrupted, `poll` throws `InterruptedException`, the `finally` still runs, and the counter is correct.

**Lines 71 to 81: snapshot**

```java
  /**
   * Returns the saturation counters at this moment.
   *
   * @return a snapshot; each field is read separately, so under load they may be a moment apart
   */
  public PoolSnapshot snapshot() {
    int size = allConnections.size();
    return new PoolSnapshot(
        size, size - idleConnections.size(), waitingBorrowers.get(), borrowTimeouts.longValue());
  }
```

- Reads the counters into a `PoolSnapshot`. `size - idleConnections.size()` is the number of connections lent out. The Javadoc warns that each field is read separately, so under load they may be a moment apart.

**Lines 82 to 103: close**

```java
  /**
   * Closes every connection, busy or idle. Call it only after every lease has been given back.
   *
   * @throws SQLException if a connection fails to close; the others are still closed
   */
  @Override
  public void close() throws SQLException {
    List<SQLException> failures = new ArrayList<>();
    for (Connection connection : allConnections) {
      try {
        connection.close();
      } catch (SQLException failed) {
        failures.add(failed);
      }
    }
    if (!failures.isEmpty()) {
      SQLException first = failures.getFirst();
      failures.stream().skip(1).forEach(first::addSuppressed);
      throw first;
    }
  }
```

- `close` is for the end of the run. It tries to close **every** connection, even if one fails, so one bad connection cannot leave the others open.
- `failures` collects the `SQLException`s. If any occurred, `failures.getFirst()` (Java 21) is the one to throw. `failures.stream().skip(1).forEach(first::addSuppressed)` attaches the others to it: `addSuppressed` records an extra exception on the first one so none is lost, and `first::addSuppressed` is a **method reference**, a short way to write `e -> first.addSuppressed(e)`.

**Lines 104 to 137: the lease**

```java
  /** One borrowed connection. Closing the lease gives the connection back to the pool. */
  public final class Lease implements AutoCloseable {

    private final Connection connection;
    private boolean isReturned;

    private Lease(Connection connection) {
      this.connection = connection;
    }

    /**
     * Returns the borrowed connection.
     *
     * @return the connection; valid until this lease is closed
     */
    public Connection connection() {
      return connection;
    }

    /**
     * Gives the connection back. A second call does nothing. Not thread-safe: one lease, one user.
     */
    @Override
    public void close() {
      if (isReturned) {
        return;
      }
      isReturned = true;
      // offer never fails here: the queue holds exactly as many slots as there are connections.
      idleConnections.offer(connection);
    }
  }
}
```

- `Lease` is a class **inside** `ConnectionPool` and not marked `static`, so each lease belongs to one pool and can reach its `idleConnections`. It implements `AutoCloseable`, so `try (ConnectionPool.Lease lease = ...)` gives the connection back at the end of the block, even after an exception. That is why no path in `HoldEndpoint` can forget to return a connection.
- `Lease(Connection connection)` is `private`: only the pool makes leases.
- `connection()` returns the connection to use while the lease is open.
- `close()` returns the connection. `isReturned` makes a second call do nothing, so closing twice cannot put the same connection in the queue twice. The Javadoc says a lease is not thread-safe: one lease, one user.
- `idleConnections.offer(connection)` puts the connection back. `offer` returns `false` if the queue is full, but the queue has exactly as many slots as there are connections, and a connection comes back only once, so it never fails. The comment states that.

### `SeatStore.java`

The seats table of SeatWave version 0 and its one conditional update. The rule "a seat is held by at most one fan" lives in the SQL, not in Java.

**Lines 1 to 11: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
```

- `Connection`: one open connection to a database (JDBC).
- `PreparedStatement`: an SQL statement with `?` holes that are filled with values.
- `SQLException`: the checked exception for a database failure.
- `Statement`: a plain SQL statement run as written.
- `Clock`: a source of the current time that a test can replace.
- `Duration`: a length of time.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Objects`: helpers such as `requireNonNull`.
- `UUID`: a random 128-bit identifier.

Nothing new is needed beyond the list above.

**Lines 12 to 34: constants**

```java
/**
 * The seats table of SeatWave version 0 and its one conditional update.
 *
 * <p>The rule "a seat is held by at most one fan" lives in the {@code WHERE} clause of one {@code
 * UPDATE}: the database checks and changes the row in one atomic step, so two racing holds can
 * never both see "1 row updated". Stateless apart from its settings; safe to share.
 */
public final class SeatStore {

  /** The demo event, as in SeatWave version 0. */
  public static final long DEMO_EVENT_ID = 1L;

  /** Sections A to T, as in SeatWave version 0. */
  public static final int SECTION_COUNT = 20;

  /** Seats in each section, as in SeatWave version 0: 20,000 seats in all. */
  public static final int SEATS_PER_SECTION = 1000;

  // A hold lasts 300 seconds (Module 1 and Module 2).
  private static final Duration HOLD_DURATION = Duration.ofSeconds(300);
  private static final int SEED_BATCH_SIZE = 1000;
  private static final char FIRST_SECTION = 'A';
```

- The class comment states the rule: the `WHERE` clause of one `UPDATE` checks and changes the row in one atomic step, so two racing holds can never both see "1 row updated". See `h2-and-jdbc.md` for what atomic means for a database.
- `DEMO_EVENT_ID`, `SECTION_COUNT` (20 sections, A to T) and `SEATS_PER_SECTION` (1,000) are public so the load generator can pick seats: 20 x 1,000 = 20,000 seats.
- `HOLD_DURATION` is `Duration.ofSeconds(300)`: a hold lasts five minutes (Modules 1 and 2). `SEED_BATCH_SIZE` is how many rows go in each batch when the table is filled. `FIRST_SECTION = 'A'` is a `char`, a single character.

**Lines 35 to 41: the hold statement**

```java
  private static final String HOLD_SQL =
      """
      UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?
      WHERE event_id = ? AND seat_label = ?
        AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
      """;
```

- `"""` starts a **text block** (Java 15 or newer): a string that may span lines. The compiler strips the indentation that all lines share.
- `UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?` changes three columns and the status. Each `?` is a hole that `PreparedStatement` fills with a value. Values never become part of the SQL text, which closes the door on SQL injection.
- `WHERE event_id = ? AND seat_label = ?` picks the one row for this seat.
- `AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))` is the rule: the update applies only if the seat is available, or it is held but the hold has expired (its expiry time is not after now). A seat that another fan holds, or has booked, does not match, so no row changes.
- The check and the change are one statement, so the database makes them one atomic step. That is the whole concurrency control of a hold.

**Lines 42 to 58: the fields and the constructor**

```java
  private final Clock clock;
  private final int statementTimeoutSeconds;

  /**
   * Creates the store.
   *
   * @param clock the source of "now" for hold expiry; a fixed clock in tests
   * @param statementTimeoutSeconds the JDBC query timeout of every statement
   */
  public SeatStore(Clock clock, int statementTimeoutSeconds) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (statementTimeoutSeconds < 1) {
      throw new IllegalArgumentException("the statement timeout must be 1 second or more");
    }
    this.statementTimeoutSeconds = statementTimeoutSeconds;
  }
```

- `Clock clock` is where "now" comes from. A test gives a fixed clock, so expiry can be tested without waiting. `statementTimeoutSeconds` is the JDBC query timeout.
- `Objects.requireNonNull(clock, "clock")` returns its argument, so it can be used in the assignment. A timeout below 1 second is refused.

**Lines 59 to 72: the label of a seat**

```java
  /**
   * Returns the label of one seat by its position, so a load generator can pick seats.
   *
   * @param seatIndex from 0 to 19,999
   * @return the label, such as {@code A-1} for 0 and {@code T-1000} for 19,999
   */
  public static String seatLabel(int seatIndex) {
    if (seatIndex < 0 || seatIndex >= SECTION_COUNT * SEATS_PER_SECTION) {
      throw new IllegalArgumentException("no such seat: " + seatIndex);
    }
    char section = (char) (FIRST_SECTION + seatIndex / SEATS_PER_SECTION);
    return String.format(Locale.ROOT, "%c-%d", section, seatIndex % SEATS_PER_SECTION + 1);
  }
```

- A seat is picked by position 0 to 19,999, and this method turns the position into a label. A position outside that range throws.
- `FIRST_SECTION + seatIndex / SEATS_PER_SECTION`: integer division gives the section number (0 for the first 1,000 seats), and adding it to `'A'` gives the letter. Adding a number to a `char` gives an `int`, so `(char)` converts it back.
- `String.format(Locale.ROOT, "%c-%d", section, seatIndex % SEATS_PER_SECTION + 1)`: `%c` is a character and `%d` a whole number. `%` is the remainder: position 0 gives `A-1` and position 19,999 gives `T-1000`.

**Lines 73 to 105: creating the tables**

```java
  /**
   * Creates the tables and the demo event with 20,000 available seats.
   *
   * @param connection an open connection to an empty database
   * @throws SQLException if a statement fails
   */
  public void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE events (event_id BIGINT PRIMARY KEY, name VARCHAR(120) NOT NULL,
            starts_at BIGINT NOT NULL)\
          """);
      // The status column is the single source of truth for a seat. Java never decides alone.
      statement.execute(
          """
          CREATE TABLE seats (event_id BIGINT NOT NULL, seat_label VARCHAR(16) NOT NULL,
            section VARCHAR(4) NOT NULL, status VARCHAR(12) NOT NULL, hold_id VARCHAR(40),
            hold_customer VARCHAR(64), hold_expires_at BIGINT, booking_id VARCHAR(40),
            PRIMARY KEY (event_id, seat_label))\
          """);
    }
    try (PreparedStatement event =
        connection.prepareStatement(
            "INSERT INTO events (event_id, name, starts_at) VALUES (?, ?, ?)")) {
      event.setLong(1, DEMO_EVENT_ID);
      event.setString(2, "Arena Night");
      event.setLong(3, clock.millis());
      event.executeUpdate();
    }
    seedSeats(connection);
  }
```

- `createSchema` builds the two tables. `throws SQLException` says a database call may fail and the caller must deal with it.
- `try (Statement statement = connection.createStatement())` opens a plain statement and closes it at the end of the block.
- The first text block creates `events` with an id, a name and a start time. The ending `)\` is a **line continuation**: a backslash at the end of a line in a text block joins it to the next, so no newline is added before the closing `"""`.
- The comment before the second table says the status column is the single source of truth for a seat. Java never decides alone. The second text block creates `seats` with one row for each seat: its event, label, section, status, the hold's id, customer and expiry, and the booking id. `PRIMARY KEY (event_id, seat_label)` makes the pair unique, and gives the database an index to find the row in the `WHERE` clause.
- `connection.prepareStatement("INSERT INTO events ... VALUES (?, ?, ?)")` prepares a statement with three holes. `setLong(1, ...)` fills hole 1, `setString(2, "Arena Night")` hole 2, and `setLong(3, clock.millis())` hole 3 with the clock's time in milliseconds. `executeUpdate()` runs it.
- `seedSeats(connection)` then fills the seats.

**Lines 106 to 128: holding one seat**

```java
  /**
   * Tries to hold one seat.
   *
   * @param connection a borrowed connection
   * @param request the validated request
   * @return {@link HoldOutcome#CREATED} or {@link HoldOutcome#SEAT_TAKEN}
   * @throws SQLException if the database fails or the statement times out
   */
  public HoldOutcome hold(Connection connection, HoldRequest request) throws SQLException {
    long nowMillis = clock.millis();
    try (PreparedStatement update = connection.prepareStatement(HOLD_SQL)) {
      // A hold that cannot get its row lock in time fails instead of waiting without limit.
      update.setQueryTimeout(statementTimeoutSeconds);
      update.setString(1, UUID.randomUUID().toString());
      update.setString(2, request.customer());
      update.setLong(3, nowMillis + HOLD_DURATION.toMillis());
      update.setLong(4, request.eventId());
      update.setString(5, request.seatLabel());
      update.setLong(6, nowMillis);
      return update.executeUpdate() == 1 ? HoldOutcome.CREATED : HoldOutcome.SEAT_TAKEN;
    }
  }
```

- `hold` takes a borrowed connection and a validated request, and returns `CREATED` or `SEAT_TAKEN`. It reads the clock once, so the expiry and the comparison use the same instant.
- `try (PreparedStatement update = connection.prepareStatement(HOLD_SQL))` prepares the statement and closes it after use.
- `update.setQueryTimeout(statementTimeoutSeconds)`: a hold that cannot get its row lock in time fails with `SQLException` instead of waiting without limit. This is the fourth limit.
- The six `set...` calls fill the six holes in order: a fresh random id from `UUID.randomUUID()`, the customer, the new expiry (now plus 300,000 ms), the event, the seat label, and now (for the expiry test in the `WHERE`).
- `update.executeUpdate() == 1 ? HoldOutcome.CREATED : HoldOutcome.SEAT_TAKEN`: `executeUpdate` returns the number of rows changed. 1 means this call changed the row, so this fan holds the seat. 0 means the `WHERE` did not match, so someone else has it. The `? :` is the conditional operator: "if this, then that, else the other".

**Lines 129 to 149: filling the table**

```java
  private static void seedSeats(Connection connection) throws SQLException {
    try (PreparedStatement seat =
        connection.prepareStatement(
            "INSERT INTO seats (event_id, seat_label, section, status) VALUES (?, ?, ?, 'AVAILABLE')")) {
      int seatCount = SECTION_COUNT * SEATS_PER_SECTION;
      for (int seatIndex = 0; seatIndex < seatCount; seatIndex++) {
        String label = seatLabel(seatIndex);
        seat.setLong(1, DEMO_EVENT_ID);
        seat.setString(2, label);
        seat.setString(3, label.substring(0, 1));
        seat.addBatch();
        // Batches keep the seeding to a few round trips without one huge statement in memory.
        if ((seatIndex + 1) % SEED_BATCH_SIZE == 0) {
          seat.executeBatch();
        }
      }
      seat.executeBatch();
    }
  }
}
```

- `seedSeats` inserts the 20,000 seats with one prepared statement and **batches**. `addBatch()` stores the filled statement; `executeBatch()` sends the stored ones together.
- The `for` loop runs `seatIndex` from 0 to 19,999. `seatLabel(seatIndex)` makes the label, `label.substring(0, 1)` is its first character, the section letter, and `'AVAILABLE'` is written in the SQL text because it never changes.
- The comment says why batches: they keep the seeding to a few round trips without one huge statement in memory. Every 1,000th row (`(seatIndex + 1) % SEED_BATCH_SIZE == 0`) sends a batch. The last `executeBatch()` sends any rows left, which is none here because 20,000 is a multiple of 1,000.

### `HoldEndpoint.java`

The hold operation of one node, without the HTTP layer: what runs after the request has been parsed and before the response is written. This is where the invariant of the module lives: no hold waits without bound.

**Lines 1 to 8: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
```

- `SQLException`: the checked exception for a database failure.
- `Duration`: a length of time.
- `Objects`: helpers such as `requireNonNull`.
- `Optional`: a box that holds a value or nothing.
- `CompletableFuture`: a future that some code completes by hand.
- `LongSupplier`: a function with no input that returns a `long`.

`LongSupplier` is a function that returns a `long` and takes nothing. It is how the endpoint reads a clock that a test can replace.

**Lines 9 to 29: the class comment and the fields**

```java
/**
 * The hold operation of one SeatWave node, without the HTTP layer: what runs after the request has
 * been parsed and before the response is written.
 *
 * <p>The invariant of this module lives here: no hold waits without bound. Every hold passes three
 * limits, and over any of them it is refused with 503 instead of being left waiting: the executor's
 * bound (checked at the door), the queue wait limit (checked when a worker starts it), and the
 * pool's borrow limit (checked while it waits for a connection). The statement has its own timeout.
 * Every returned future completes.
 *
 * <p>Thread-safe: it keeps no mutable state of its own; the workers, the pool and the store are
 * thread-safe.
 */
public final class HoldEndpoint {

  private final Workers workers;
  private final ConnectionPool pool;
  private final SeatStore seatStore;
  private final Limits limits;
  private final LongSupplier nanoClock;
```

- The Javadoc names the three limits every hold passes, and says every returned future completes. It also says the class is thread-safe because it keeps no mutable state: the parts it holds are thread-safe.
- Five fields, all `private final`: the thread model, the pool, the store, the limits and the nanosecond clock.

**Lines 30 to 57: the limits**

```java
  /**
   * The waits one hold may spend, taken from {@link NodeSettings}.
   *
   * @param queueWaitLimit the longest from arrival to the start of the work
   * @param poolBorrowLimit the longest wait for a connection
   * @param databaseWait how long the remote database takes, while the connection is held
   */
  public record Limits(Duration queueWaitLimit, Duration poolBorrowLimit, Duration databaseWait) {

    /** Creates the limits. */
    public Limits {
      Objects.requireNonNull(queueWaitLimit, "queueWaitLimit");
      Objects.requireNonNull(poolBorrowLimit, "poolBorrowLimit");
      Objects.requireNonNull(databaseWait, "databaseWait");
    }

    /**
     * Takes the limits from the node settings.
     *
     * @param settings the node settings
     * @return the limits
     */
    public static Limits of(NodeSettings settings) {
      return new Limits(
          settings.queueWaitLimit(), settings.poolBorrowLimit(), settings.databaseWait());
    }
  }
```

- `Limits` is a record **nested inside** the class. It groups the three waits one hold may spend. Its compact constructor refuses nulls.
- `Limits.of(settings)` takes the three waits from a `NodeSettings`. The static method keeps the endpoint independent of the settings record's other fields.

**Lines 58 to 79: the constructor**

```java
  /**
   * Creates the endpoint.
   *
   * @param workers the thread model
   * @param pool the connection pool
   * @param seatStore the seats table
   * @param limits the waits allowed
   * @param nanoClock a monotonic clock in nanoseconds: {@code System::nanoTime}, or a fake in tests
   */
  public HoldEndpoint(
      Workers workers,
      ConnectionPool pool,
      SeatStore seatStore,
      Limits limits,
      LongSupplier nanoClock) {
    this.workers = Objects.requireNonNull(workers, "workers");
    this.pool = Objects.requireNonNull(pool, "pool");
    this.seatStore = Objects.requireNonNull(seatStore, "seatStore");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
  }
```

- Five parameters, each checked with `requireNonNull` and stored. The last is `nanoClock`: `System::nanoTime` in production, a fake in tests. `System.nanoTime()` counts nanoseconds from an arbitrary start and never goes back, so it is right for measuring durations. See `java.md`.

**Lines 80 to 105: accepting a hold**

```java
  /**
   * Accepts a hold, or refuses it at once. Never blocks the caller, which plays the accept loop.
   *
   * @param request the validated request
   * @return a future that always completes with a reply, or exceptionally only on a bug
   */
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

- `submit` returns a `CompletableFuture<HoldReply>`: a handle that the caller can wait on. It never blocks the caller, which plays the accept loop.
- `arrivedNanos` is read first, so the queue wait includes everything after arrival.
- `new CompletableFuture<>()` is a future that nobody has completed yet. Whoever calls `reply.complete(value)` finishes it.
- `workers.tryExecute(() -> {...})` hands the work to the thread model. The lambda `() -> {...}` is the task. It calls `serve` and completes the future with the answer.
- `catch (RuntimeException bug)`: if `serve` throws, the code completes the future exceptionally and then rethrows, so a bug does not leave the caller waiting forever. The comment says: complete, then let it surface. An unfinished future would hang the benchmark.
- `if (!isAccepted) reply.complete(HoldReply.refusedAtTheDoor(HoldOutcome.QUEUE_FULL))` is the **door**: the bound was reached, so the future is completed at once with `QUEUE_FULL`. Every path completes the future.

**Lines 106 to 135: serving a hold on a worker**

```java
  private HoldReply serve(HoldRequest request, long arrivedNanos) {
    long startedNanos = nanoClock.getAsLong();
    long queueWaitNanos = startedNanos - arrivedNanos;
    // A hold that already used its whole budget in the queue is refused before it costs anything.
    if (queueWaitNanos > limits.queueWaitLimit().toNanos()) {
      return new HoldReply(HoldOutcome.QUEUE_WAIT_EXCEEDED, queueWaitNanos, 0, 0);
    }
    try {
      Optional<ConnectionPool.Lease> borrowed = pool.borrow(limits.poolBorrowLimit());
      long borrowedNanos = nanoClock.getAsLong();
      long poolWaitNanos = borrowedNanos - startedNanos;
      if (borrowed.isEmpty()) {
        return new HoldReply(HoldOutcome.POOL_TIMEOUT, queueWaitNanos, poolWaitNanos, 0);
      }
      try (ConnectionPool.Lease lease = borrowed.get()) {
        HoldOutcome outcome = seatStore.hold(lease.connection(), request);
        waitForRemoteDatabase();
        long workNanos = nanoClock.getAsLong() - borrowedNanos;
        return new HoldReply(outcome, queueWaitNanos, poolWaitNanos, workNanos);
      }
    } catch (SQLException databaseFailed) {
      // The seat's state is unknown, so the only safe answer is a refusal the fan can retry.
      return new HoldReply(HoldOutcome.DATABASE_FAILED, queueWaitNanos, 0, 0);
    } catch (InterruptedException interrupted) {
      // Only a shutdown interrupts a worker: keep the flag for the executor and stop this hold.
      Thread.currentThread().interrupt();
      return new HoldReply(HoldOutcome.INTERRUPTED, queueWaitNanos, 0, 0);
    }
  }
```

- `serve` runs on a worker thread. It reads the clock again; the difference from `arrivedNanos` is the time the hold spent in the executor queue.
- The comment says a hold that already used its budget in the queue is refused before it costs anything. `queueWaitNanos > limits.queueWaitLimit().toNanos()` returns `QUEUE_WAIT_EXCEEDED` with no database work. This is the second limit.
- `pool.borrow(limits.poolBorrowLimit())` waits up to the borrow limit. The time since the start of `serve` is the pool wait.
- `borrowed.isEmpty()` means no connection came in time: `POOL_TIMEOUT`, the third limit.
- `try (ConnectionPool.Lease lease = borrowed.get())` makes sure the connection goes back, whatever happens in the block. Inside, `seatStore.hold(lease.connection(), request)` runs the one `UPDATE`. `waitForRemoteDatabase()` then keeps the connection for the configured time. `workNanos` is the time since the borrow. The reply carries the outcome and the three times.
- `catch (SQLException databaseFailed)`: the statement failed or timed out. The comment says the seat's state is unknown, so the only safe answer is a refusal the fan can retry: `DATABASE_FAILED`. This is the fourth limit.
- `catch (InterruptedException interrupted)`: `Thread.currentThread().interrupt()` sets the thread's interrupt flag again. Catching the exception cleared it, and the executor needs the flag to know it is shutting down. The hold answers `INTERRUPTED`. See `java-concurrency.md`.

**Lines 136 to 145: the remote database**

```java
  // Embedded H2 answers in well under a millisecond. A real database on another machine adds a
  // network round trip, a disk flush and lock waits; this wait stands for them, with the
  // connection still borrowed, which is what makes the pool the queue.
  private void waitForRemoteDatabase() throws InterruptedException {
    if (!limits.databaseWait().isZero()) {
      Thread.sleep(limits.databaseWait());
    }
  }
}
```

- The comment explains the model: embedded H2 answers in well under a millisecond, while a real database on another machine adds a network round trip, a disk flush and lock waits. This wait stands for them, with the connection still borrowed, which is what makes the pool the queue.
- `Thread.sleep(limits.databaseWait())` (taking a `Duration`, Java 19 or newer) pauses the current thread. On a platform thread the thread stays blocked in the operating system. On a virtual thread it unmounts from its carrier, so the carrier is free for other holds. If the wait is zero, nothing sleeps.

### `SeatWaveNode.java`

One SeatWave process: the workers, the connection pool and the embedded database, wired from one `NodeSettings`.

**Lines 1 to 9: package and imports**

```java
package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
```

- `Connection`: one open connection to a database (JDBC).
- `DriverManager`: opens a connection from a JDBC URL.
- `SQLException`: the checked exception for a database failure.
- `Clock`: a source of the current time that a test can replace.
- `ArrayList`: a growable list.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.

`DriverManager` is the JDBC entry that opens a connection from a URL.

**Lines 10 to 35: the fields and the private constructor**

```java
/**
 * One SeatWave process: the workers, the connection pool and the embedded database, wired from one
 * {@link NodeSettings}. Create one, call {@link #endpoint()}, and close it when done.
 *
 * <p>Thread-safe once constructed: every part is.
 */
public final class SeatWaveNode implements AutoCloseable {

  private final NodeSettings settings;
  private final Workers workers;
  private final ConnectionPool pool;
  private final HoldEndpoint endpoint;

  private SeatWaveNode(NodeSettings settings, Workers workers, ConnectionPool pool, Clock clock) {
    this.settings = settings;
    this.workers = workers;
    this.pool = pool;
    this.endpoint =
        new HoldEndpoint(
            workers,
            pool,
            new SeatStore(clock, settings.statementTimeoutSeconds()),
            HoldEndpoint.Limits.of(settings),
            System::nanoTime);
  }
```

- Four fields. The constructor is `private`, so a node can only be made by `start`, which prepares the parts first.
- `new HoldEndpoint(workers, pool, new SeatStore(clock, ...), HoldEndpoint.Limits.of(settings), System::nanoTime)` builds the endpoint. `System::nanoTime` is a method reference used as a `LongSupplier`.

**Lines 36 to 68: starting the node**

```java
  /**
   * Opens the database, creates the seats, fills the pool and starts the workers.
   *
   * @param settings every limit of the node
   * @param clock the source of "now" for hold expiry
   * @return the running node
   * @throws SQLException if the database cannot be opened or seeded
   */
  public static SeatWaveNode start(NodeSettings settings, Clock clock) throws SQLException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(clock, "clock");
    List<Connection> connections = new ArrayList<>();
    try {
      for (int connectionIndex = 0;
          connectionIndex < settings.poolConnections();
          connectionIndex++) {
        connections.add(DriverManager.getConnection(settings.databaseUrl()));
      }
      new SeatStore(clock, settings.statementTimeoutSeconds()).createSchema(connections.getFirst());
    } catch (SQLException failed) {
      closeQuietlyAfter(failed, connections);
      throw failed;
    }
    ConnectionPool pool = new ConnectionPool(connections);
    Workers workers =
        switch (settings.mode()) {
          case PLATFORM ->
              new PlatformWorkers(settings.platformWorkers(), settings.executorQueueBound());
          case VIRTUAL -> new VirtualWorkers(settings.virtualMaxInFlight());
        };
    return new SeatWaveNode(settings, workers, pool, clock);
  }
```

- `start` is the one way in. It opens a list of connections: one `DriverManager.getConnection(settings.databaseUrl())` per connection in the pool. All of them name the same `jdbc:h2:mem:` URL, so they see **one** in-memory database. That database lives as long as at least one connection to it is open.
- `new SeatStore(...).createSchema(connections.getFirst())` creates the tables and seeds the seats once, through the first connection.
- `catch (SQLException failed)`: if anything fails while connecting, `closeQuietlyAfter` closes the connections already opened, and the original exception is rethrown. No connection leaks.
- `new ConnectionPool(connections)` hands the connections to the pool.
- `switch (settings.mode()) { case PLATFORM -> ...; case VIRTUAL -> ... }` is a **switch expression** (Java 14 or newer): it produces a value. There is no `default` branch. For an enum, the compiler checks that every value is covered, so adding a third mode to `ExecutorMode` stops the build here until it is handled. This is the sealed design working.

**Lines 69 to 104: getters**

```java
  /**
   * Returns the hold operation.
   *
   * @return the endpoint
   */
  public HoldEndpoint endpoint() {
    return endpoint;
  }

  /**
   * Returns the settings the node runs with.
   *
   * @return the settings
   */
  public NodeSettings settings() {
    return settings;
  }

  /**
   * Returns the workers, so a sampler can read their saturation.
   *
   * @return the workers
   */
  public Workers workers() {
    return workers;
  }

  /**
   * Returns the pool, so a sampler can read its saturation.
   *
   * @return the pool
   */
  public ConnectionPool pool() {
    return pool;
  }
```

- `endpoint()`, `settings()`, `workers()` and `pool()` return the parts. The benchmark's sampler reads the workers' and the pool's counters through them.

**Lines 105 to 116: closing**

```java
  /**
   * Waits for accepted holds to finish, then closes every connection. The in-memory database
   * disappears with its last connection.
   *
   * @throws SQLException if a connection fails to close
   */
  @Override
  public void close() throws SQLException {
    workers.close();
    pool.close();
  }
```

- `close` first closes the workers, which waits for the accepted holds to finish. Then it closes the pool and its connections. The order matters: a connection closed under a running hold would fail it. When the last connection to an in-memory database closes, the database disappears.

**Lines 117 to 128: closing after a failed start**

```java
  private static void closeQuietlyAfter(SQLException cause, List<Connection> connections) {
    for (Connection connection : connections) {
      try {
        connection.close();
      } catch (SQLException alsoFailed) {
        // Keep the first failure as the reason; the later one is attached, not lost.
        cause.addSuppressed(alsoFailed);
      }
    }
  }
}
```

- It tries to close every connection that was opened. If one close fails too, `cause.addSuppressed(alsoFailed)` attaches it to the first failure instead of losing it.

## Try it yourself

All commands run from the repository folder. Use `./gradlew` (not `gradle`); the first run downloads Gradle.

**1. Watch the invariant tests pass.**

```text
./gradlew test --tests '*HoldEndpointTest' --console=plain
```

The output lists six tests, each `PASSED`. Two to look for by name: `letsExactlyOneOfManyRacingHoldsWinOneSeat` (50 holds race for one seat and exactly one gets 201) and `refusesAtTheDoorWhenTheWorkerAndTheQueueAreTaken` (the door refuses with 503). I ran it and got `BUILD SUCCESSFUL in 2s`.

**2. See what an unbounded queue does under overload.**

```text
./gradlew -q benchmark --args='config/unbounded.properties build/bench'
```

It took 33 seconds on my machine. The numbers differ on every run, but the shape does not. My run printed:

```text
mode      offered achieved  p50 ms   p99 ms  errors%  503q  503p  late  threads  platform  waiters  waitp99  busy%  cpu%
platform      440    436.5   957.7   1967.7     0.80     0     0    35       64        71       52    385.9   99.3   1.1
platform      600    184.8   983.1   1980.6    69.20     0     0  4152       64        71       58    387.2   99.5   0.6
```

Read the `503q` and `503p` columns: both are 0, so nothing was refused. The `late` column is 4,152: those holds waited past the client's 2-second patience. Compare with `config/simple.properties`, where the same overload ends in 503 answers (`java-bench-walkthrough.md` shows the columns). The command also writes `build/bench/unbounded.csv` and two charts (see `csv-and-svg-output.md`). Delete `build/bench` afterwards if you like; it is ignored by git.

**3. Look at the thread census.** The same run prints, near the end:

```text
Thread census at 600 holds/s, platform mode (platform threads only):
     48  seatwave-worker-   TIMED_WAITING  in ConnectionPool.borrow
     16  seatwave-worker-   TIMED_WAITING  in HoldEndpoint.waitForRemoteDatabase
```

Sixteen workers hold a connection (waiting in `waitForRemoteDatabase`), and the other 48 wait in `ConnectionPool.borrow`. The pool of 16 connections, not the 64 threads, is the limit.

## Mistakes people make

- **An unbounded queue.** `Executors.newFixedThreadPool(64)` queues without limit. Here `PlatformWorkers` passes an `ArrayBlockingQueue` with a bound, and `tryExecute` turns a full queue into `false`.
- **A wait with no limit.** `BlockingQueue.take()` and `Semaphore.acquire()` wait forever. This package uses `poll(timeout)` in the pool and `tryAcquire()` in the semaphore.
- **"Caller runs" rejection.** It runs the refused task on the submitting thread, which is the accept loop. The package uses `AbortPolicy`.
- **A permit or a connection that is not returned on an error path.** The semaphore release is in `finally`, and the connection is in a `Lease` closed by try-with-resources.
- **Swallowing an interrupt.** Catching `InterruptedException` and carrying on hides a shutdown. `serve` restores the flag with `Thread.currentThread().interrupt()` before it returns.
- **A future that never completes.** `submit` completes the future on every path, including a bug.
- **Deciding a seat's owner in Java.** Reading the seat, deciding, then writing is a race between two threads. The single conditional `UPDATE` makes the check and the change one step in the database.

## Check yourself

1. **A hold arrives when 64 workers are busy and the queue holds 120 holds. What happens, and with what status?** `tryExecute` returns `false`, `submit` completes the future with `QUEUE_FULL`, and the status is 503. No worker is involved.
2. **Why does `serve` check the queue wait before it borrows a connection?** A hold that already waited its whole budget in the queue does not need an answer, and borrowing would waste a connection. The check refuses it before it costs anything.
3. **Where does `waitingBorrowers` go down if the wait is interrupted?** In the `finally` block of `borrow`, which runs on every path.
4. **Why can `Lease.close` call `offer` without checking the result?** The queue has exactly as many slots as there are connections, and each connection is returned once, so the queue is never full.
5. **Two fans send `A-7` at the same moment. How does the node make sure only one gets 201?** Both run the same conditional `UPDATE`. The database changes the row for one of them (1 row updated) and the `WHERE` does not match for the other (0 rows updated, `SEAT_TAKEN`).

## Further reading

Official documentation only, for JDK 25 and H2 2.5.252:

- `ThreadPoolExecutor`, the pool and its rejection policies: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
- The `java.util.concurrent` package (`ArrayBlockingQueue`, `Semaphore`, `CompletableFuture`, `Executors`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html
- Virtual threads, JEP 444: https://openjdk.org/jeps/444
- `PreparedStatement`, including `setQueryTimeout` and `executeUpdate`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/PreparedStatement.html
- H2 features, including in-memory databases: https://h2database.com/html/features.html
