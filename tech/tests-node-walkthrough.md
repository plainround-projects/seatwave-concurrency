# Tests of the `node` package, walked through

## What it is

The **node** is one running copy of the SeatWave service. It accepts "hold this seat for me" requests, which are called **holds**, and answers each one. To answer, a hold needs a **thread** to run on and a **database connection** to talk to the seat table. A thread is one line of execution inside a running program: a program with many threads does many things at the same moment. A **connection pool** is a small box of ready database connections that threads borrow and give back, because opening a connection is slow and a database allows only so many of them.

The node has two ways to run holds, called modes. In **platform** mode a fixed set of operating-system threads (64 in the real settings) takes holds from a queue. In **virtual** mode every hold gets its own virtual thread, a very light thread that the Java runtime schedules itself, and a counter caps how many holds may run at once. Both modes put a **limit on every queue and every wait**. When a limit is reached, the node refuses the hold at once with status 503 ("service unavailable") instead of letting it wait without end.

A **test** is a small program that calls the code and checks the result. This file explains the four test classes and the one helper class of the `node` package. `junit.md` says what `@Test` is, `assertj.md` says what `assertThat` does, and `awaitility.md` says how `await()` works. This file adds one idea that the other notes do not need: how to test **code that runs on several threads** and still get the same result on every run.

## Why this repository uses it

This module teaches what limits one node: the threads, the queue in front of them, and the connection pool behind them. The tests are the proof that the limits really work: a full queue refuses the next hold, a pool that stays empty for too long makes a hold fail with 503, a hold that waited too long in the queue is refused before it costs anything, and 50 fans racing for one seat produce exactly one winner. If these tests were wrong or flaky, the benchmark numbers in the README would stand on sand. The `node` package has 292 lines, and 268 of them run in the tests (`jacoco.md`); the 24 that do not are failure branches such as "the database could not be opened".

## Where it appears

| Path | What it does |
| --- | --- |
| `src/test/java/io/plainround/seatwave/concurrency/node/TestNodes.java` | A helper, not a test: small node settings, a unique database name per test, and the `LONG_WAIT` limit used by every wait in the test suite. |
| `.../node/ConnectionPoolTest.java` | 7 tests of the connection pool. |
| `.../node/HoldEndpointTest.java` | 6 tests of the hold endpoint: races, refusals, the fake clock. |
| `.../node/NodeValuesTest.java` | 8 tests of the small value types (request, reply, outcome, settings). |
| `.../node/WorkersTest.java` | 4 tests of the two kinds of workers. |

`TestNodes` is also used by the tests of the `bench`, `eventloop` and `threads` packages (`tests-bench-walkthrough.md`, `tests-other-packages-walkthrough.md`).

## How it works underneath

1. **A test is one thread; the code under test may use many.** JUnit runs a test method on one thread. When the test starts a node, the node starts its own threads. The test must then wait for those threads to reach a known place before it checks anything. If it checks too early, it reads a half-finished state.
2. **A race is a result that depends on who is faster.** If two threads both try to take the same seat, the database decides who wins. A test for that must accept either order and check only what is true in both: exactly one winner.
3. **A deterministic test gives the same result on every run and every machine.** The tests in this package reach that with five tools, and each one appears in a test below.
   - A **latch** (`CountDownLatch`) is a gate that holds threads until the test opens it. A latch starts at a count, for example 1. A thread that calls `await()` on it waits while the count is above zero. A call to `countDown()` lowers the count; at zero every waiting thread goes on. A test uses a latch to keep a worker busy for exactly as long as the test wants.
   - **Awaitility** (`awaitility.md`) waits for a **state**, such as "one borrower is waiting", and not for a time.
   - A **fake clock** is a clock the test controls. Code that asks a fake clock "what time is it?" gets the time the test chose. The test can move it forward by 301 seconds without waiting 301 seconds. The node takes two kinds of clocks: a `java.time.Clock` for the date and time that hold expiry is calculated from, and a `LongSupplier` (a function that returns a `long` number) for the stopwatch that measures waits in nanoseconds. A **nanosecond** is a billionth of a second.
   - **Bounds on every wait.** A wait with a limit ends with an error, not with a hang. Every wait in these tests carries the limit `TestNodes.LONG_WAIT`, five seconds.
   - **Unique, small things.** Each test gets its own in-memory database name (`TestNodes.uniqueUrl()`), so tests cannot see each other's seats, and tiny settings (one worker, one queued hold, one connection) so a limit is reached with two or three holds instead of thousands.
4. **Closing everything.** Nodes, pools, worker sets and executors implement `AutoCloseable`: an interface with one method, `close()`, that frees what the object holds. Java's **try-with-resources** statement, `try (Thing thing = ...) { ... }`, calls `close()` on every listed resource when the block ends, even if the block throws, and closes them in the reverse order of opening. The tests use it so no test leaves threads or database connections behind. A thread that survives one test could disturb the next.
5. **The database is H2 in memory** (`h2-and-jdbc.md`). A JDBC URL such as `jdbc:h2:mem:test-<random>` creates a database that lives only in the memory of the test JVM, and `DriverManager.getConnection(url)` opens a connection to it. All connections opened with the same URL see the same database. A different URL is a different, empty database.

## Walkthrough of this repository's use

Words that first appear here, explained once.

- A **record** is a short way to declare a class that only holds values: `record PoolSnapshot(int size, int busy, ...)` creates the class, its constructor, an accessor method per field (such as `snapshot.busy()`) and an `equals` that compares the fields. A record can run checks in its constructor, and the value types here refuse impossible values that way.
- A **lambda** is a small unnamed function written `() -> something`. `() -> workers.queued() == 0` is a function with no input that returns true or false.
- A **method reference** such as `fakeNanos::get` means "the method `get` of the object `fakeNanos`", used where a function is expected.
- **`Optional<T>`** is a box that holds a value or nothing. `orElseThrow()` opens the box and throws if it is empty.
- A **`CompletableFuture<T>`** is a promise of a value that arrives later, from another thread. `join()` waits until the value is there and returns it.
- **`@TempDir`** and the other test annotations are in `junit.md`.

### `TestNodes.java`: the helper

`TestNodes` is not a test. It holds the small settings and the unique database names that the tests share. It is `public` and `final`, so other test packages can use it and nobody can extend it.

```java
package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small, fast node settings and databases for tests; every database has its own name. */
public final class TestNodes {

  /**
   * A wait long enough never to expire in a passing test, and short enough to end a failing one.
   */
  public static final Duration LONG_WAIT = Duration.ofSeconds(5);

  private static final int STATEMENT_TIMEOUT_SECONDS = 2;
  private static final int TWO_IN_FLIGHT = 2;
  private static final int ROOMY_WORKERS = 16;
  private static final int ROOMY_QUEUE = 1000;
  private static final int ROOMY_CONNECTIONS = 8;

  private TestNodes() {}
```

- Lines 3 to 9 import the Java classes used: `Connection`, `DriverManager` and `SQLException` from JDBC (Java's standard way to talk to a database), `Duration` for lengths of time, `ArrayList` and `List`, and `UUID` for random unique names.
- The comment above `LONG_WAIT` says what the constant is for: "A wait long enough never to expire in a passing test, and short enough to end a failing one." Five seconds is a hundred times longer than any step in these tests takes, so a passing test never reaches it, yet a broken test fails in five seconds instead of hanging the build. All waits in the test suite use this one value, so changing the patience takes one edit.
- `STATEMENT_TIMEOUT_SECONDS = 2` is how long one SQL statement may run before the database gives up on it. `TWO_IN_FLIGHT = 2`, `ROOMY_WORKERS = 16`, `ROOMY_QUEUE = 1000` and `ROOMY_CONNECTIONS = 8` are the sizes of the two kinds of settings below. Named constants keep numbers out of the code, which the build's style rules ask for.
- `private TestNodes() {}` is a constructor nobody can call. It makes the class a plain holder of static methods: you never create a `TestNodes` object.

```java
  /**
   * Returns settings with one worker, one queued hold, one connection and no database wait.
   *
   * @param mode the thread model
   * @return the settings
   */
  public static NodeSettings tiny(ExecutorMode mode) {
    return new NodeSettings(
        mode,
        1,
        1,
        TWO_IN_FLIGHT,
        LONG_WAIT,
        1,
        LONG_WAIT,
        Duration.ZERO,
        STATEMENT_TIMEOUT_SECONDS,
        uniqueUrl());
  }
```

`tiny(mode)` returns settings for a node that is easy to overload. The ten values of `NodeSettings` in order are: the mode, the number of platform workers (1), the queue bound (1, so one hold may wait while the worker is busy), the cap on virtual holds in flight (2), the longest a hold may wait in the queue (`LONG_WAIT`), the number of connections in the pool (1), the longest a hold may wait for a connection (`LONG_WAIT`), the simulated database wait (`Duration.ZERO`, so holds are not slowed on purpose), the statement timeout, and the database URL. The limits are long (5 seconds) on purpose: in a `tiny` node, a refusal must come from a **full** queue or pool, not from a timer that ran out on a slow machine.

```java
  /**
   * Returns settings with room for many holds at once and no database wait.
   *
   * @param mode the thread model
   * @return the settings
   */
  public static NodeSettings roomy(ExecutorMode mode) {
    return new NodeSettings(
        mode,
        ROOMY_WORKERS,
        ROOMY_QUEUE,
        ROOMY_QUEUE,
        LONG_WAIT,
        ROOMY_CONNECTIONS,
        LONG_WAIT,
        Duration.ZERO,
        STATEMENT_TIMEOUT_SECONDS,
        uniqueUrl());
  }
```

`roomy(mode)` is the opposite: 16 workers, a queue of 1,000, 1,000 virtual holds in flight and 8 connections. A test that wants all holds to succeed, so that it can count winners and losers of a race, uses this node. Nothing in it can be full.

```java
  /**
   * Returns a JDBC URL no other test uses.
   *
   * @return the URL of a fresh in-memory database
   */
  public static String uniqueUrl() {
    return "jdbc:h2:mem:test-" + UUID.randomUUID();
  }

  /**
   * Opens connections to one fresh in-memory database.
   *
   * @param count how many
   * @return the open connections
   * @throws SQLException if H2 cannot open them
   */
  public static List<Connection> connections(int count) throws SQLException {
    String url = uniqueUrl();
    List<Connection> connections = new ArrayList<>();
    for (int connectionIndex = 0; connectionIndex < count; connectionIndex++) {
      connections.add(DriverManager.getConnection(url));
    }
    return connections;
  }
}
```

- `uniqueUrl()` builds the text `jdbc:h2:mem:test-` followed by a random UUID (a 36-character random identifier). Every call gives a different in-memory database, so two tests never share seats. This is what makes the tests independent of their order.
- `connections(count)` opens `count` connections to **one** fresh database (it calls `uniqueUrl()` once, then `DriverManager.getConnection(url)` in a loop) and returns them as a list. Because the URL is the same, all of them see the same database, which is what a pool needs. The loop variable is named `connectionIndex` and the list `connections`, so the code says what it is. The method says `throws SQLException`: opening a connection can fail, and the test methods that call it declare `throws Exception`.

### `ConnectionPoolTest.java`: seven tests of the pool

The pool (`ConnectionPool`) lends out connections with `borrow(limit)`. `borrow` waits at most `limit` for a free connection. It returns an `Optional<Lease>`: a **lease** is the right to use one connection until you call its `close()`, which gives the connection back. A lease is `AutoCloseable`, so `try (ConnectionPool.Lease lease = pool.borrow(...).orElseThrow()) { ... }` returns the connection automatically. `snapshot()` returns a `PoolSnapshot`, a frozen copy of four counters: the pool size, how many connections are busy, how many borrowers are waiting right now, and how many borrows have timed out so far.

```java
package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

  private static final Duration SHORT_LIMIT = Duration.ofMillis(20);
  private static final int CONNECTIONS = 4;
  private static final int BORROWERS = 200;
```

- The imports show what the file uses. `assertThat` and `assertThatThrownBy` are AssertJ, `await` is Awaitility. `ConcurrentHashMap` and `Set` are used for the race test below, `Executors` and `ExecutorService` start virtual threads, `Future` and `CompletableFuture` hold results that arrive later, and `IntStream` counts from 0 to 199.
- The class is `class ConnectionPoolTest` with no `public`: JUnit does not need it.
- `SHORT_LIMIT` is 20 milliseconds, the borrow limit used when the test wants a borrow to give up quickly. `CONNECTIONS = 4` and `BORROWERS = 200` are the sizes of the race test.

```java
  @Test
  void refusesAPoolWithoutConnections() {
    assertThatThrownBy(() -> new ConnectionPool(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**`refusesAPoolWithoutConnections`** proves that a pool of nothing cannot be built: `new ConnectionPool(List.of())` must throw `IllegalArgumentException`. A pool with no connection would make every borrower wait out its limit for nothing, so the constructor refuses it at the start. `List.of()` is an empty list. The call is inside a lambda, `() -> new ConnectionPool(...)`, so AssertJ can run it and catch the exception (`assertj.md`).

```java
  @Test
  void givesUpAfterTheLimitWhenEveryConnectionIsLent() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(1))) {
      Optional<ConnectionPool.Lease> first = pool.borrow(SHORT_LIMIT);

      Optional<ConnectionPool.Lease> second = pool.borrow(SHORT_LIMIT);

      assertThat(first).isPresent();
      assertThat(second).isEmpty();
      assertThat(pool.snapshot()).isEqualTo(new PoolSnapshot(1, 1, 0, 1));
    }
  }
```

**`givesUpAfterTheLimitWhenEveryConnectionIsLent`** proves that `borrow` does not wait forever. The pool has one connection (`TestNodes.connections(1)`). The first `borrow` takes it. The second `borrow` finds the pool empty, waits 20 milliseconds and returns an empty `Optional`. The test then checks the counters with one comparison of whole records: `new PoolSnapshot(1, 1, 0, 1)` means "size 1, busy 1, waiting 0, timeouts 1". The timeouts counter is 1 because the second borrow gave up.

The three steps are separated by blank lines: **arrange** and the first borrow (`first`), **act** (`second`), **assert**. The test stays deterministic because the first lease is never returned: whether the machine is fast or slow, the second borrow can only find the pool empty. The 20 milliseconds is a limit that the code under test enforces, and the test does not measure it. The `try (ConnectionPool pool = ...)` block closes the pool, and so its connections, at the end.

```java
  @Test
  void countsAWaitingBorrowerUntilAConnectionComesBack() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(1));
        ExecutorService borrower = Executors.newVirtualThreadPerTaskExecutor()) {
      ConnectionPool.Lease held = pool.borrow(SHORT_LIMIT).orElseThrow();
      Future<Optional<ConnectionPool.Lease>> waiting =
          borrower.submit(() -> pool.borrow(TestNodes.LONG_WAIT));
      await().atMost(TestNodes.LONG_WAIT).until(() -> pool.snapshot().waitingBorrowers() == 1);

      held.close();

      assertThat(waiting.get()).isPresent();
      assertThat(pool.snapshot().waitingBorrowers()).isZero();
      assertThat(pool.snapshot().busy()).isEqualTo(1);
    }
  }
```

**`countsAWaitingBorrowerUntilAConnectionComesBack`** proves the `waitingBorrowers` counter and that a waiting borrower gets the connection once it is returned. It is the first test that needs a second thread.

1. The `try` lists two resources: the pool and `borrower`, an `ExecutorService` made by `Executors.newVirtualThreadPerTaskExecutor()`. That is a small thread pool that starts a new virtual thread for each task. When the block ends, Java closes them **in reverse order**: first `borrower` (its `close()` waits for its tasks to finish), then `pool`.
2. `held` takes the only connection. `orElseThrow()` opens the `Optional` and fails the test if it is empty.
3. `borrower.submit(() -> pool.borrow(TestNodes.LONG_WAIT))` starts the second borrower on its own thread and returns a `Future` at once, before the thread has even run. The second borrower waits up to five seconds for a connection.
4. The test cannot yet give the connection back. If it did, the second borrower might not have started waiting, and the test would not prove anything about waiting. So `await().atMost(TestNodes.LONG_WAIT).until(() -> pool.snapshot().waitingBorrowers() == 1)` waits **for the state**: exactly one borrower is inside `borrow`. This is the determinism trick of the whole package: look at the pool's own counter, never at the clock.
5. `held.close()` gives the connection back. The waiting borrower now gets it.
6. `waiting.get()` waits for the second thread's result. It must hold a lease (`isPresent()`). The counter `waitingBorrowers` is back to zero (`isZero()`), and `busy` is 1 because the second borrower holds the connection.

```java
  @Test
  void returnsALeaseOnlyOnceEvenWhenClosedTwice() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(2))) {
      ConnectionPool.Lease lease = pool.borrow(SHORT_LIMIT).orElseThrow();

      lease.close();
      lease.close();

      assertThat(pool.snapshot().busy()).isZero();
      assertThat(pool.borrow(SHORT_LIMIT)).isPresent();
      assertThat(pool.borrow(SHORT_LIMIT)).isPresent();
      assertThat(pool.borrow(SHORT_LIMIT)).isEmpty();
    }
  }
```

**`returnsALeaseOnlyOnceEvenWhenClosedTwice`** proves that closing a lease twice does not put the connection back twice. The pool has two connections. The test takes one lease and closes it two times. If the second `close()` returned the connection again, the pool could lend one connection to two borrowers. The assertions check the result from outside: `busy` is zero, then two borrows succeed (`isPresent()` twice), and the third finds the pool empty (`isEmpty()`), so there are exactly two connections in the box.

```java
  @Test
  void neverLendsOneConnectionToTwoBorrowersAtOnce() throws Exception {
    Set<Connection> inUse = ConcurrentHashMap.newKeySet();
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(CONNECTIONS));
        ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Boolean>> borrows =
          IntStream.range(0, BORROWERS)
              .mapToObj(borrowerIndex ->
                  CompletableFuture.supplyAsync(() -> borrowAlone(pool, inUse), threads))
              .toList();

      for (CompletableFuture<Boolean> borrow : borrows) {
        assertThat(borrow.get()).isTrue();
      }
      assertThat(pool.snapshot().busy()).isZero();
    }
  }
```

**`neverLendsOneConnectionToTwoBorrowersAtOnce`** is the **race test** of the pool. It tries to make a bug appear by brute force: 200 borrowers compete for 4 connections.

- `ConcurrentHashMap.newKeySet()` makes a **set** (a collection without duplicates) that many threads can use safely. `inUse` will hold the connections that are lent out at this moment.
- `IntStream.range(0, BORROWERS).mapToObj(borrowerIndex -> CompletableFuture.supplyAsync(() -> borrowAlone(pool, inUse), threads)).toList()` starts 200 tasks, one per number from 0 to 199, each on a virtual thread of `threads`, and collects the 200 `CompletableFuture` objects in a list. `supplyAsync(task, executor)` runs `task` on `executor` and returns a future for its result.
- The loop `for (CompletableFuture<Boolean> borrow : borrows)` waits for each task with `get()` and requires its result to be `true`.
- The helper `borrowAlone` (below) borrows a connection, then tries to **add** the connection to `inUse`. `Set.add` returns `true` if the connection was not yet in the set. If a second borrower held the same connection at the same time, its `add` would return `false`. The helper returns that answer, so a `false` means the pool lent one connection twice.
- At the end `busy` is zero: every lease was returned.

This test cannot prove that the pool is correct, because a race that never happens in 200 tries is still possible. It proves that the failure it looks for did not show up under heavy contention, with 50 borrowers for each connection. A failing run would be a real bug, never a slow machine, because the check does not depend on time.

```java
  @Test
  void closesEveryConnection() throws Exception {
    List<Connection> connections = TestNodes.connections(2);
    ConnectionPool pool = new ConnectionPool(connections);

    pool.close();

    assertThat(connections).allSatisfy(connection -> assertThat(connection.isClosed()).isTrue());
  }

  @Test
  void rejectsImpossibleCounters() {
    assertThatThrownBy(() -> new PoolSnapshot(1, 2, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- **`closesEveryConnection`** closes the pool and then checks that every connection in the list reports `isClosed()`. `allSatisfy` runs the check on every element. This is a **leak test**: if `close()` forgot one connection, the database would keep it open.
- **`rejectsImpossibleCounters`** builds `new PoolSnapshot(1, 2, 0, 0)`: a pool of size 1 with 2 busy connections. The record's constructor refuses it with `IllegalArgumentException`. A counter that says something impossible would be a bug somewhere else, so it is caught at the place where the numbers are put together.

```java
  // True when no other borrower held this connection while this one did.
  private static boolean borrowAlone(ConnectionPool pool, Set<Connection> inUse) {
    try (ConnectionPool.Lease lease = pool.borrow(TestNodes.LONG_WAIT).orElseThrow()) {
      boolean isAlone = inUse.add(lease.connection());
      inUse.remove(lease.connection());
      return isAlone;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
```

`borrowAlone` is a private helper, so it is not a test. The comment on line 111 says what it returns: "True when no other borrower held this connection while this one did." It borrows with the long limit, so a borrower that must queue behind others still gets a connection. The `try (ConnectionPool.Lease lease = ...)` returns the connection when the block ends, even on an error. `inUse.add(lease.connection())` returns whether this borrower is alone with the connection, and `inUse.remove(...)` takes it out again before the lease is closed. Between `add` and `remove` the lease is still held, so another borrower could only add the same connection if the pool lent it twice.

`borrow` is declared to throw `InterruptedException`, a checked exception that means "another thread asked this thread to stop waiting". The `catch` block does the standard thing: it **restores the interrupt flag** with `Thread.currentThread().interrupt()` (the flag was cleared when the exception was thrown) and then throws an unchecked `IllegalStateException`, so the future fails and the test fails loudly. It never ignores the interrupt.

### `HoldEndpointTest.java`: six tests of the hold endpoint

`HoldEndpoint` is the heart of the node: `submit(request)` gives a hold to the workers and returns a `CompletableFuture<HoldReply>`. A `HoldReply` has an `outcome` (`CREATED` 201, `SEAT_TAKEN` 409, or one of the refusals, all 503), and three durations in nanoseconds: the time in the queue, the time waiting for a connection and the time doing the work. If the workers cannot take the hold at all, the reply is `HoldReply.refusedAtTheDoor(HoldOutcome.QUEUE_FULL)`, completed at once.

```java
package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HoldEndpointTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
  private static final int RACING_FANS = 50;
  private static final Duration SHORT_LIMIT = Duration.ofMillis(20);
  private static final Duration QUEUE_WAIT_LIMIT = Duration.ofMillis(300);
  private static final int SERVICE_UNAVAILABLE = 503;
  // One second past the 300-second hold.
  private static final Duration JUST_AFTER_EXPIRY = Duration.ofSeconds(301);
```

- The imports bring in `Clock`, `Instant` and `ZoneOffset` for the fake clock, `Duration` for lengths of time, `CompletableFuture`, `CountDownLatch`, `TimeUnit` and `AtomicLong` for the concurrency tools, `LongSupplier` for the stopwatch function, `Collectors` and `IntStream` for counting outcomes, and `Connection` for the database connections.
- `FIXED_CLOCK` is a **fake clock**: `Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC)` is a `java.time.Clock` that always says it is 10:00 on 10 November 2026, UTC. The seat store reads it to decide when a hold expires. With a fixed clock, no hold can expire by accident during a test, and the test can compute exact expiry times.
- `RACING_FANS = 50` is the number of concurrent requests in the race test. `QUEUE_WAIT_LIMIT` is 300 milliseconds, the limit on time spent in the queue that one test uses. `SERVICE_UNAVAILABLE = 503` is the HTTP status code that means "try again later".
- `JUST_AFTER_EXPIRY` is 301 seconds. The comment says why: "One second past the 300-second hold." A hold lasts 300 seconds (five minutes), so 301 seconds later it has expired.

```java
  @Test
  void letsExactlyOneOfManyRacingHoldsWinOneSeat() throws Exception {
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.roomy(ExecutorMode.VIRTUAL), FIXED_CLOCK)) {
      List<CompletableFuture<HoldReply>> replies =
          IntStream.range(0, RACING_FANS)
              .mapToObj(fan -> node.endpoint().submit(new HoldRequest(1, "C-42", "fan_" + fan)))
              .toList();

      Map<HoldOutcome, Long> outcomes =
          replies.stream()
              .map(CompletableFuture::join)
              .collect(Collectors.groupingBy(HoldReply::outcome, Collectors.counting()));

      assertThat(outcomes)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(HoldOutcome.CREATED, 1L, HoldOutcome.SEAT_TAKEN, RACING_FANS - 1L));
    }
  }
```

**`letsExactlyOneOfManyRacingHoldsWinOneSeat`** is the main correctness test of the whole module: 50 fans try to hold **the same seat**, `C-42`, at the same moment, and exactly one may succeed.

1. `SeatWaveNode.start(TestNodes.roomy(ExecutorMode.VIRTUAL), FIXED_CLOCK)` starts a real node: it opens 8 connections to a fresh in-memory database, creates the tables, seeds 20,000 seats and starts the virtual-thread workers. `try (...)` closes it at the end.
2. The `IntStream` creates 50 requests (`HoldRequest(1, "C-42", "fan_" + fan)`: event 1, seat C-42, customer `fan_0` to `fan_49`) and submits each one. The 50 holds run in parallel on virtual threads, and the `toList()` collects the 50 futures.
3. The next statement waits for all of them (`CompletableFuture::join`) and counts the outcomes with `Collectors.groupingBy(HoldReply::outcome, Collectors.counting())`: a map from each outcome to how many replies had it.
4. The assertion compares the whole map with `Map.of(CREATED, 1L, SEAT_TAKEN, RACING_FANS - 1L)`, in any order: one winner and 49 losers.

Why the result is the same on every run: **which** fan wins is a race, and the test does not care. The rule it checks is made by the database. The hold is one SQL statement, `UPDATE ... WHERE ... status = 'AVAILABLE' ...` (`h2-and-jdbc.md`), and a database runs conflicting updates one after the other. Exactly one update finds the seat available. The loser statements then find it held and change zero rows, which the code reports as `SEAT_TAKEN`. The fixed clock matters here too: no hold expires during the test, so a loser can never take over an expired hold.

```java
  @Test
  void refusesWith503WhenNoConnectionFreesWithinTheBorrowLimit() throws Exception {
    NodeSettings settings = TestNodes.tiny(ExecutorMode.VIRTUAL);
    NodeSettings shortBorrow =
        new NodeSettings(
            settings.mode(),
            settings.platformWorkers(),
            settings.executorQueueBound(),
            settings.virtualMaxInFlight(),
            settings.queueWaitLimit(),
            settings.poolConnections(),
            SHORT_LIMIT,
            settings.databaseWait(),
            settings.statementTimeoutSeconds(),
            settings.databaseUrl());
    try (SeatWaveNode node = SeatWaveNode.start(shortBorrow, FIXED_CLOCK);
        ConnectionPool.Lease taken = node.pool().borrow(SHORT_LIMIT).orElseThrow()) {

      HoldReply reply = node.endpoint().submit(new HoldRequest(1, "A-1", "fan_1")).join();

      assertThat(reply.outcome()).isEqualTo(HoldOutcome.POOL_TIMEOUT);
      assertThat(reply.outcome().httpStatus()).isEqualTo(SERVICE_UNAVAILABLE);
      assertThat(reply.poolWaitNanos()).isGreaterThanOrEqualTo(SHORT_LIMIT.toNanos());
      assertThat(taken.connection()).isNotNull();
    }
  }
```

**`refusesWith503WhenNoConnectionFreesWithinTheBorrowLimit`** proves that a hold that cannot get a connection in time is refused with 503 and not left waiting.

- The test starts from the `tiny` settings and builds a copy with only one value changed: the borrow limit is `SHORT_LIMIT` (20 ms) instead of five seconds. Settings are records, which cannot be changed, so the test copies every field with an accessor (`settings.mode()`, `settings.platformWorkers()` and the rest) and puts `SHORT_LIMIT` in the seventh place, where the borrow limit goes.
- The `try` starts the node and then **takes its only connection** with `node.pool().borrow(SHORT_LIMIT).orElseThrow()`. The test now holds the pool empty.
- The hold goes through `node.endpoint().submit(...)` and `.join()` waits for the reply. The hold finds no connection, waits 20 ms and gives up.
- The checks: the outcome is `POOL_TIMEOUT`, its HTTP status is 503, and `reply.poolWaitNanos()` is at least `SHORT_LIMIT.toNanos()`. The last check is a **lower bound**: the code waited at least the limit, which is true on a fast machine and on a slow one, because a slow machine only waits longer.
- The last line, `assertThat(taken.connection()).isNotNull();`, looks pointless, but it has a job. The compiler flag `-Xlint:all` (`gradle.md`) warns about "an auto-closeable resource that is never referenced in the body of the try statement", and `-Werror` makes that warning an error. I removed the line in a scratch copy and the build failed with `warning: [try] auto-closeable resource taken is never referenced in body of corresponding try statement`. The line uses the resource, so the compiler is satisfied, and it also checks that the lease holds a connection.

```java
  @Test
  void refusesAtTheDoorWhenTheWorkerAndTheQueueAreTaken() throws Exception {
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.tiny(ExecutorMode.PLATFORM), FIXED_CLOCK)) {
      ConnectionPool.Lease taken = node.pool().borrow(SHORT_LIMIT).orElseThrow();
      CompletableFuture<HoldReply> first =
          node.endpoint().submit(new HoldRequest(1, "A-1", "fan_1"));
      await()
          .atMost(TestNodes.LONG_WAIT)
          .until(() -> node.pool().snapshot().waitingBorrowers() == 1);
      CompletableFuture<HoldReply> second =
          node.endpoint().submit(new HoldRequest(1, "A-2", "fan_2"));

      HoldReply third = node.endpoint().submit(new HoldRequest(1, "A-3", "fan_3")).join();

      assertThat(third).isEqualTo(HoldReply.refusedAtTheDoor(HoldOutcome.QUEUE_FULL));
      taken.close();
      assertThat(first.join().outcome()).isEqualTo(HoldOutcome.CREATED);
      assertThat(second.join().outcome()).isEqualTo(HoldOutcome.CREATED);
    }
  }
```

**`refusesAtTheDoorWhenTheWorkerAndTheQueueAreTaken`** proves the **bounded queue**: with one worker busy and one hold waiting in the queue, the next hold is refused at once. This is the test that shows a limit working.

1. A `tiny` platform node has 1 worker thread, a queue of 1 and 1 connection. The test takes the only connection (`taken`) and keeps it.
2. The first hold is submitted. The worker picks it up and blocks, waiting for a connection that never comes (up to five seconds).
3. `await()... waitingBorrowers() == 1` waits until that is true. Now the worker is certainly busy, and the queue is certainly empty.
4. The second hold is submitted. The worker is busy, so it goes into the queue, which now has no room.
5. The third hold is submitted. There is no worker and no room in the queue, so the endpoint answers at once: `HoldReply.refusedAtTheDoor(HoldOutcome.QUEUE_FULL)`. The test compares the whole reply with `isEqualTo`.
6. `taken.close()` returns the connection. The worker can now serve the first hold, then the second one from the queue. Both end `CREATED` (they are different seats, `A-1` and `A-2`), and `join()` waits for them.

Without step 3 the test would be a race: the second hold could arrive while the first still sat in the queue. The wait for a state fixes that without a single `sleep`.

```java
  @Test
  void refusesAHoldThatWaitedInTheQueueLongerThanTheLimit() throws Exception {
    AtomicLong fakeNanos = new AtomicLong();
    CountDownLatch release = new CountDownLatch(1);
    try (PlatformWorkers workers = new PlatformWorkers(1, 1);
        ConnectionPool pool = new ConnectionPool(seededConnections())) {
      HoldEndpoint endpoint = endpoint(workers, pool, fakeNanos::get);
      workers.tryExecute(() -> awaitQuietly(release));
      // Wait until the worker has taken the blocker, so the one queue slot is free for the hold.
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.queued() == 0);
      CompletableFuture<HoldReply> queued = endpoint.submit(new HoldRequest(1, "A-1", "fan_1"));

      fakeNanos.addAndGet(QUEUE_WAIT_LIMIT.toNanos() + 1);
      release.countDown();

      HoldReply reply = queued.join();
      assertThat(reply.outcome()).isEqualTo(HoldOutcome.QUEUE_WAIT_EXCEEDED);
      assertThat(reply.queueWaitNanos()).isEqualTo(QUEUE_WAIT_LIMIT.toNanos() + 1);
      assertThat(pool.snapshot().busy()).isZero();
    }
  }
```

**`refusesAHoldThatWaitedInTheQueueLongerThanTheLimit`** proves a rule that normally needs time to pass: a hold that has already spent its whole queue budget (300 ms) in the queue is refused **before it takes a connection**. A test that really waited 300 ms would be slow, and the exact wait it reports would differ on every run. So the test replaces the stopwatch with a **fake clock**.

- `AtomicLong fakeNanos = new AtomicLong()` is a number that starts at 0. An `AtomicLong` is a `long` that several threads can read and change safely. `fakeNanos::get` is a method reference that the endpoint receives as its `LongSupplier`: every time the endpoint asks "what time is it, in nanoseconds?", it gets the current value of `fakeNanos`.
- `CountDownLatch release = new CountDownLatch(1)` is the gate that will keep the single worker busy.
- The `try` creates `PlatformWorkers(1, 1)` (one worker, a queue of one) and a pool with one seeded connection (`seededConnections()`, below). The two resources are closed at the end.
- `workers.tryExecute(() -> awaitQuietly(release))` gives the worker a blocker job that waits on the latch. The worker is now busy.
- `endpoint.submit(...)` sends the hold. It arrives at fake time 0 and waits in the queue behind the blocker.
- `fakeNanos.addAndGet(QUEUE_WAIT_LIMIT.toNanos() + 1)` moves the fake clock forward by 300 ms plus one nanosecond. No real time passes.
- `release.countDown()` opens the gate. The blocker ends, the worker takes the hold from the queue and reads the fake clock: the hold waited exactly 300,000,001 ns, which is over the limit of 300,000,000 ns.
- The checks: the outcome is `QUEUE_WAIT_EXCEEDED`, `queueWaitNanos` equals exactly `QUEUE_WAIT_LIMIT.toNanos() + 1` (a precise number that only a fake clock can give), and the pool shows `busy` zero: the hold never took a connection.

**Why the test waits before it submits the hold.** The queue has room for one job. If the worker had not yet taken the blocker out of the queue when `submit` ran, the queue would still be full, and the hold would be refused at the door with `QUEUE_FULL`. So the test first waits, with a bound, until `workers.queued()` is zero: `await().atMost(TestNodes.LONG_WAIT).until(() -> workers.queued() == 0)`. `WorkersTest` closes the same window the same way.

```java
  @Test
  void answers503WhenTheDatabaseFails() throws Exception {
    List<Connection> connections = TestNodes.connections(1);
    connections.getFirst().close();
    try (VirtualWorkers workers = new VirtualWorkers(1);
        ConnectionPool pool = new ConnectionPool(connections)) {
      HoldEndpoint endpoint = endpoint(workers, pool, System::nanoTime);

      HoldReply reply = endpoint.submit(new HoldRequest(1, "A-1", "fan_1")).join();

      assertThat(reply.outcome()).isEqualTo(HoldOutcome.DATABASE_FAILED);
      assertThat(pool.snapshot().busy()).isZero();
    }
  }
```

**`answers503WhenTheDatabaseFails`** proves that a database error becomes a refusal the fan can retry, and that the connection goes back to the pool. The test opens one connection and **closes it** (`connections.getFirst().close()`), so the pool holds a dead connection. The first hold borrows it and the SQL statement throws `SQLException`. The endpoint answers `DATABASE_FAILED`, a 503. The last assertion, `busy` zero, proves that the lease was returned on the error path: a leaked lease would leave the pool one connection short for ever. The clock is `System::nanoTime`, the real stopwatch, because this test does not measure time.

```java
  @Test
  void letsAnotherFanHoldASeatOnceTheFirstHoldExpired() throws Exception {
    List<Connection> connections = seededConnections();
    Clock afterExpiry = Clock.offset(FIXED_CLOCK, JUST_AFTER_EXPIRY);
    HoldRequest firstFan = new HoldRequest(1, "B-9", "fan_1");
    HoldRequest secondFan = new HoldRequest(1, "B-9", "fan_2");
    try (ConnectionPool pool = new ConnectionPool(connections);
        ConnectionPool.Lease lease = pool.borrow(SHORT_LIMIT).orElseThrow()) {
      SeatStore now = new SeatStore(FIXED_CLOCK, 1);
      SeatStore later = new SeatStore(afterExpiry, 1);

      HoldOutcome first = now.hold(lease.connection(), firstFan);
      HoldOutcome tooEarly = now.hold(lease.connection(), secondFan);
      HoldOutcome afterTheHoldExpired = later.hold(lease.connection(), secondFan);

      assertThat(first).isEqualTo(HoldOutcome.CREATED);
      assertThat(tooEarly).isEqualTo(HoldOutcome.SEAT_TAKEN);
      assertThat(afterTheHoldExpired).isEqualTo(HoldOutcome.CREATED);
    }
  }
```

**`letsAnotherFanHoldASeatOnceTheFirstHoldExpired`** proves the **expiry rule** of a hold with no waiting at all. It talks to the `SeatStore` (the class that runs the SQL) directly, with two stores that share one database but have different clocks:

- `now` uses `FIXED_CLOCK`, 10:00.
- `later` uses `Clock.offset(FIXED_CLOCK, JUST_AFTER_EXPIRY)`, a clock that is always 301 seconds after the fixed one.

Steps: the first fan holds seat `B-9` through `now` and gets `CREATED`. The second fan tries the same seat through `now` and gets `SEAT_TAKEN`, because the hold is alive. Then the second fan tries through `later`: 301 seconds have passed, the hold (300 seconds) has expired, the SQL condition `hold_expires_at <= ?` is now true, and the hold is `CREATED`. A real test would need to wait five minutes. With two fake clocks it runs in milliseconds, and the result cannot vary.

```java
  private static HoldEndpoint endpoint(
      Workers workers, ConnectionPool pool, LongSupplier nanoClock) {
    return new HoldEndpoint(
        workers,
        pool,
        new SeatStore(FIXED_CLOCK, 1),
        new HoldEndpoint.Limits(QUEUE_WAIT_LIMIT, SHORT_LIMIT, Duration.ZERO),
        nanoClock);
  }

  private static List<Connection> seededConnections() throws Exception {
    List<Connection> connections = TestNodes.connections(1);
    new SeatStore(FIXED_CLOCK, 1).createSchema(connections.getFirst());
    return connections;
  }

  private static void awaitQuietly(CountDownLatch release) {
    try {
      if (!release.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the test never released the worker");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
```

- `endpoint(workers, pool, nanoClock)` builds a `HoldEndpoint` with the fixed clock for expiry (`new SeatStore(FIXED_CLOCK, 1)`: a clock and a 1-second statement timeout), the three limits (`HoldEndpoint.Limits`: queue wait 300 ms, pool borrow 20 ms, database wait zero) and the stopwatch the caller chooses. It exists so that the tests that build their own workers and pool do not repeat these lines.
- `seededConnections()` opens one connection and creates the schema on it (`createSchema`), the tables and the 20,000 seats. It returns the list for the pool.
- `awaitQuietly(release)` is the blocker job: it waits on the latch for at most `LONG_WAIT`. If the time passes without the latch opening, it throws `IllegalStateException("the test never released the worker")`, so a forgotten `countDown()` fails the test instead of hanging it. If the thread is interrupted, it restores the interrupt flag (the same rule as in `borrowAlone`) and returns.

### `NodeValuesTest.java`: eight tests of the value types

These tests use no thread. A **value type** is a small immutable object, here a record, that refuses to hold an impossible value. The node's whole defense against bad input is that such an object cannot be created in a wrong state. The class comment says so: "The value types of the node: each refuses to hold an invalid value."

```java
package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The value types of the node: each refuses to hold an invalid value. */
class NodeValuesTest {

  private static final int LAST_SEAT = 19_999;

  @Test
  void acceptsAValidHoldRequest() {
    HoldRequest request = new HoldRequest(1, "T-1000", "fan_1");

    assertThat(request.seatLabel()).isEqualTo("T-1000");
  }
```

- `Result` and `SettingsReader` appear in the imports. A `Result<T, E>` is the result4j type for "either a value of type `T` or an error of type `E`" (`result4j.md`); `SettingsReader` reads a `Properties` object, a table of text keys and values read from a `.properties` file.
- `LAST_SEAT = 19_999` is the index of the last of the 20,000 seats; the underscore in a number is only for reading.
- **`acceptsAValidHoldRequest`** builds `new HoldRequest(1, "T-1000", "fan_1")`: event 1, seat `T-1000` (the last seat of the last section), customer `fan_1`. It must be accepted and keep its seat label.

```java
  @Test
  void rejectsHoldRequestsOutsideTheAllowLists() {
    assertThatThrownBy(() -> new HoldRequest(0, "A-1", "fan"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HoldRequest(1, "Z-1;drop", "fan"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HoldRequest(1, "A-1", "fan one"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HoldRequest(1, "A-1", "x".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mapsEveryOutcomeToItsStatus() {
    assertThat(HoldOutcome.CREATED.httpStatus()).isEqualTo(201);
    assertThat(HoldOutcome.SEAT_TAKEN.httpStatus()).isEqualTo(409);
    assertThat(HoldOutcome.values())
        .filteredOn(outcome -> !outcome.isGood())
        .allSatisfy(outcome -> assertThat(outcome.httpStatus()).isEqualTo(503));
  }
```

**`rejectsHoldRequestsOutsideTheAllowLists`** proves the **allow-list** rule. The request type accepts only what matches a fixed pattern: an event number of 1 or more, a seat label of a letter A to T, a dash and one to four digits, and a customer name of 1 to 64 letters, digits, underscores or dashes. Four attempts, each must throw `IllegalArgumentException`: event `0`; the label `Z-1;drop`, which has a letter outside A to T and a semicolon, the kind of text used in an SQL injection attack; the customer `fan one` with a space; and a customer of 65 letters (`"x".repeat(65)`), one more than allowed. An allow-list is safer than a block-list: it says what is allowed and refuses everything else.

**`mapsEveryOutcomeToItsStatus`** checks the HTTP status of each outcome: `CREATED` is 201, `SEAT_TAKEN` is 409 (a conflict). Then it takes `HoldOutcome.values()` (every outcome) and uses `filteredOn(outcome -> !outcome.isGood())` to keep only the refusals, and requires that **all** of them are 503. If someone adds a new outcome and forgets its status, the test catches it.

```java
  @Test
  void rejectsANegativeDurationInAReply() {
    assertThatThrownBy(() -> new HoldReply(HoldOutcome.CREATED, -1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void labelsTheFirstAndLastSeat() {
    assertThat(SeatStore.seatLabel(0)).isEqualTo("A-1");
    assertThat(SeatStore.seatLabel(LAST_SEAT)).isEqualTo("T-1000");
    assertThatThrownBy(() -> SeatStore.seatLabel(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SeatStore.seatLabel(LAST_SEAT + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- **`rejectsANegativeDurationInAReply`**: `new HoldReply(HoldOutcome.CREATED, -1, 0, 0)` has a negative queue wait. A duration is never negative, so the record refuses it.
- **`labelsTheFirstAndLastSeat`**: seat index 0 is `A-1`, and index 19,999 is `T-1000`. Index -1 and index 20,000 are outside the 20 sections of 1,000 seats, and each throws `IllegalArgumentException`. This proves the arithmetic of the labels at the edges, where mistakes usually hide.

```java
  @Test
  void readsNodeSettingsFromAFile() {
    Result<NodeSettings, List<String>> read =
        NodeSettings.from(new SettingsReader(validProperties()), ExecutorMode.VIRTUAL);

    NodeSettings settings = read.discardError().orElseThrow();
    assertThat(settings.mode()).isEqualTo(ExecutorMode.VIRTUAL);
    assertThat(settings.poolBorrowLimit()).isEqualTo(Duration.ofMillis(500));
    assertThat(settings.withMode(ExecutorMode.PLATFORM).mode()).isEqualTo(ExecutorMode.PLATFORM);
    assertThat(settings.withDatabaseUrl("jdbc:h2:mem:other").databaseUrl())
        .isEqualTo("jdbc:h2:mem:other");
  }
```

**`readsNodeSettingsFromAFile`** proves that the settings of a node are read correctly. `NodeSettings.from(new SettingsReader(validProperties()), ExecutorMode.VIRTUAL)` returns a `Result`. `read.discardError().orElseThrow()` turns it into the settings if it is a success, and throws if it is an error. The checks: the mode is `VIRTUAL`, the borrow limit is 500 milliseconds (`pool.borrow.limit.millis=500` in the properties), and the two "copy with one change" methods work: `withMode(ExecutorMode.PLATFORM)` returns settings with the new mode, and `withDatabaseUrl("jdbc:h2:mem:other")` returns settings with the new URL. The benchmark uses both to give each mode its own database.

```java
  @Test
  void listsEveryMissingOrInvalidNodeSetting() {
    Properties properties = validProperties();
    properties.remove("pool.connections");
    properties.setProperty("platform.workers", "zero");

    Result<NodeSettings, List<String>> read =
        NodeSettings.from(new SettingsReader(properties), ExecutorMode.PLATFORM);

    assertThat(((Result.Error<NodeSettings, List<String>>) read).error())
        .containsExactly(
            "platform.workers must be a whole number, but was zero", "pool.connections is missing");
  }

  @Test
  void refusesADatabaseThatIsNotInMemory() {
    Properties properties = validProperties();
    properties.setProperty("database.url", "jdbc:h2:file:./seatwave");

    Result<NodeSettings, List<String>> read =
        NodeSettings.from(new SettingsReader(properties), ExecutorMode.PLATFORM);

    assertThat(((Result.Error<NodeSettings, List<String>>) read).error())
        .singleElement()
        .asString()
        .startsWith("only jdbc:h2:mem: URLs are allowed");
  }
```

- **`listsEveryMissingOrInvalidNodeSetting`** proves that a bad settings file is reported **all at once**, not one problem per run. It removes `pool.connections` and sets `platform.workers` to the word `zero`. The result must be an error with exactly two messages, in the order the settings are read: `platform.workers must be a whole number, but was zero` and `pool.connections is missing`. The expression `((Result.Error<NodeSettings, List<String>>) read).error()` is a **cast**: it tells Java that `read` is really the error case so the test can read its `error()` list (`assertj.md`). If `read` were a success, the cast would throw and the test would fail.
- **`refusesADatabaseThatIsNotInMemory`** proves a safety rule: the URL `jdbc:h2:file:./seatwave` would make H2 write a file, and the benchmark must never write files or reach another host. The error list must have a single element (`singleElement()`) whose text (`asString()`) starts with `only jdbc:h2:mem: URLs are allowed`.

```java
  /**
   * Returns the node keys of the SIMPLE settings file.
   *
   * @return the properties
   */
  static Properties validProperties() {
    Properties properties = new Properties();
    properties.setProperty("platform.workers", "64");
    properties.setProperty("platform.queue.bound", "120");
    properties.setProperty("virtual.max.in.flight", "184");
    properties.setProperty("queue.wait.limit.millis", "300");
    properties.setProperty("pool.connections", "16");
    properties.setProperty("pool.borrow.limit.millis", "500");
    properties.setProperty("database.wait.millis", "40");
    properties.setProperty("database.statement.timeout.seconds", "2");
    properties.setProperty("database.url", "jdbc:h2:mem:seatwave-test-");
    return properties;
  }
}
```

`validProperties()` is the helper that builds a `Properties` object with the nine node keys of the SIMPLE settings: 64 platform workers, a queue bound of 120, 184 virtual holds in flight, a 300 ms queue wait, 16 connections, a 500 ms borrow limit, a 40 ms database wait, a 2-second statement timeout and the URL prefix `jdbc:h2:mem:seatwave-test-`. Its doc comment ("Returns the node keys of the SIMPLE settings file.") says where the numbers come from. It is package-private (no `public`) and `static`, so a test in the same package could reuse it.

### `WorkersTest.java`: four tests of the two worker kinds

`Workers` is the interface of the thing that runs holds. `tryExecute(task)` returns `true` if the task was accepted and `false` if it was refused. `threadsAlive()` says how many threads are working and `queued()` how many tasks wait. `PlatformWorkers(threads, queueBound)` has a fixed set of platform threads and a queue. `VirtualWorkers(maxInFlight)` starts a virtual thread per task and counts permits: a **semaphore** is a counter of permits that a task takes before it runs and gives back when it ends, and when none is left the next task is refused.

```java
package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkersTest {

  private static final int TWO = 2;
```

The imports bring in `CountDownLatch` and `TimeUnit` for the gate that keeps a worker busy, and the AssertJ and Awaitility methods. `TWO` is the number 2 under a name, so the code has no bare numbers.

```java
  @Test
  void platformWorkersRefuseWhenEveryThreadIsBusyAndTheQueueIsFull() {
    CountDownLatch release = new CountDownLatch(1);
    try (PlatformWorkers workers = new PlatformWorkers(1, 1)) {
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.queued() == 0);
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();

      boolean isThirdAccepted = workers.tryExecute(() -> awaitQuietly(release));

      assertThat(isThirdAccepted).isFalse();
      assertThat(workers.threadsAlive()).isEqualTo(1);
      assertThat(workers.queued()).isEqualTo(1);
      release.countDown();
    }
  }
```

**`platformWorkersRefuseWhenEveryThreadIsBusyAndTheQueueIsFull`** proves the **bounded queue of the platform mode**, with no database at all.

- `PlatformWorkers(1, 1)`: one thread, a queue of one.
- The first `tryExecute` is accepted (`isTrue()`). Its job is `() -> awaitQuietly(release)`: wait on the latch. The thread takes it and blocks.
- `await().atMost(TestNodes.LONG_WAIT).until(() -> workers.queued() == 0)` waits until the thread has taken the job out of the queue. Before that, the job may still sit in the queue and take its only place. This is the wait that makes the next step safe.
- The second job is accepted: the thread is busy, so it takes the free place in the queue.
- The third job is refused: `isThirdAccepted` is `false`. The variable name starts with `is` because it holds a yes-or-no answer.
- The counters match: one thread alive, one job queued.
- `release.countDown()` opens the gate so the jobs end and `close()` can finish.

```java
  @Test
  void virtualWorkersRefuseOverTheInFlightBoundAndFreeThePermitAfterward() {
    CountDownLatch release = new CountDownLatch(1);
    try (VirtualWorkers workers = new VirtualWorkers(TWO)) {
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();

      boolean isThirdAccepted = workers.tryExecute(() -> awaitQuietly(release));

      assertThat(isThirdAccepted).isFalse();
      assertThat(workers.threadsAlive()).isEqualTo(TWO);
      assertThat(workers.queued()).isZero();
      release.countDown();
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.threadsAlive() == 0);
      assertThat(workers.tryExecute(() -> {})).isTrue();
    }
  }
```

**`virtualWorkersRefuseOverTheInFlightBoundAndFreeThePermitAfterward`** proves the same bound for virtual mode, and that a permit is given back. With `VirtualWorkers(2)` the first two jobs are accepted, and the third is refused. There is no queue (`queued()` is zero) and two jobs are in flight (`threadsAlive()` is 2). Then the gate opens, and `await()... threadsAlive() == 0` waits until both jobs have ended and given their permits back. The last assertion, `tryExecute(() -> {})` accepts a new job (`() -> {}` is a job that does nothing), proves that the permits were really released. A permit that is never released is a **leak**, and the node would slowly refuse everything.

```java
  @Test
  void virtualWorkersRefuseAfterClose() {
    VirtualWorkers workers = new VirtualWorkers(TWO);
    workers.close();

    assertThat(workers.tryExecute(() -> {})).isFalse();
    assertThat(workers.threadsAlive()).isZero();
  }

  @Test
  void rejectsAZeroBound() {
    assertThatThrownBy(() -> new PlatformWorkers(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VirtualWorkers(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static void awaitQuietly(CountDownLatch release) {
    try {
      if (!release.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the test never released the worker");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
```

- **`virtualWorkersRefuseAfterClose`** closes the workers and then tries to run a job. The job is refused, and no thread is alive. This is the rule "a node that is shutting down answers like a full node".
- **`rejectsAZeroBound`** proves that `new PlatformWorkers(0, 1)` and `new VirtualWorkers(0)` throw `IllegalArgumentException`. A bound of zero would refuse every hold.
- `awaitQuietly` is the same blocker helper as in `HoldEndpointTest`: wait on the latch for at most five seconds, fail loudly if it never opens, and restore the interrupt flag if interrupted.

## Try it yourself

1. Run the `node` package tests:

```
./gradlew cleanTest test --tests '*node*'
```

   Expected: 25 lines ending in `PASSED` (7 + 6 + 8 + 4) and `BUILD SUCCESSFUL`.

2. See that the tests wait for states, not for time. Count the `await()` calls and the `sleep` calls in this package:

```
grep -c "await()" src/test/java/io/plainround/seatwave/concurrency/node/*.java
grep -c "sleep" src/test/java/io/plainround/seatwave/concurrency/node/*.java
```

   Expected: one `await()` in `ConnectionPoolTest.java`, one in `HoldEndpointTest.java` and two in `WorkersTest.java`, zero elsewhere, and `0` for every file in the second command.

3. Run the race test alone:

```
./gradlew cleanTest test --tests '*ConnectionPoolTest.neverLendsOneConnectionToTwoBorrowersAtOnce'
```

   Expected: one `PASSED` line.

4. Run the 50-fan race alone:

```
./gradlew cleanTest test --tests '*HoldEndpointTest.letsExactlyOneOfManyRacingHoldsWinOneSeat'
```

   Expected: one `PASSED` line. The result is the same on every run: one `CREATED` and 49 `SEAT_TAKEN`.

5. Look at the unique database names that keep tests apart:

```
grep -n "jdbc:h2:mem" src/test/java/io/plainround/seatwave/concurrency/node/*.java
```

   Expected: `TestNodes.java` builds `"jdbc:h2:mem:test-" + UUID.randomUUID()`, and `NodeValuesTest.java` uses fixed text only for settings that never open a database.

## Mistakes people make

- **Waiting with `Thread.sleep`.** The right length depends on the machine. Wait for a state, as every test here does (`awaitility.md`).
- **Checking before the other thread has arrived.** A `submit` returns before the thread has run. Wait for a counter that proves arrival, as `ConnectionPoolTest` and `WorkersTest` do. `refusesAHoldThatWaitedInTheQueueLongerThanTheLimit` does the same before it submits its hold (see above).
- **Using the real clock for a rule about time.** A fake clock gives exact results and no waiting: `fakeNanos` for waits and `Clock.offset` for expiry.
- **Sharing a database between tests.** One test's seats would appear in another test. `TestNodes.uniqueUrl()` prevents that.
- **Forgetting to close.** A node, a pool or a worker set left open keeps threads and connections alive. The `try (...)` blocks close them.
- **Testing a race once.** A race test must try many times to have a chance of finding a bug (200 borrowers here), and its check must be true on every run.
- **Hanging the build.** A wait without a limit hangs for ever on a bug. Each wait here is bounded by `LONG_WAIT`.

## Check yourself

1. *What is a deterministic test?* One that gives the same result on every run and every machine.
2. *How does `ConnectionPoolTest` know the second borrower is waiting before it gives the connection back?* It waits with Awaitility until `pool.snapshot().waitingBorrowers() == 1`.
3. *What does a fake clock make possible in `letsAnotherFanHoldASeatOnceTheFirstHoldExpired`?* A hold that lasts 300 seconds expires after 301 fake seconds without any waiting, so the test runs in milliseconds and gives an exact result.
4. *Why does the 50-fan race test pass on every run although it is a race?* It does not check who wins, only that exactly one fan wins and 49 get `SEAT_TAKEN`, which the database guarantees.
5. *What does `try (ConnectionPool pool = ...)` do that a plain statement does not?* It calls `pool.close()` at the end of the block, even on an exception, so no connection or thread is left behind.

## Further reading

- `java.util.concurrent.CountDownLatch`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CountDownLatch.html>
- `java.util.concurrent.ThreadPoolExecutor`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html>
- `java.util.concurrent.CompletableFuture`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html>
- `java.time.Clock`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html>
- The try-with-resources statement (Java Language Specification, Java 25): <https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.20.3>
- Virtual threads (JEP 444): <https://openjdk.org/jeps/444>
