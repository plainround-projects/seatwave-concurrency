# Awaitility 4.3.0

## What it is

Awaitility is a small Java library for tests that have to wait. Some things in a program do not happen at once: a thread starts, a queue drains, a connection is handed back. A test that needs to continue only after such a thing has happened can ask Awaitility to **check a condition again and again until it is true, or give up after a time limit**. If the limit passes, Awaitility throws an exception that says which condition failed and how long it waited, and the test fails.

The idea in one line: wait for a **state**, never for a **time**.

A **thread** is one line of execution inside a running program. Several threads can run at the same moment, each doing its own work. This module is about threads, so most tests here start some and then need to know when they have reached the place the test cares about.

## Why this repository uses it

A concurrency test has two ways to wait for another thread, and one of them is wrong.

- **Wrong: `Thread.sleep(200)`**, "wait 200 milliseconds and hope the other thread is ready". On a fast machine this wastes time. On a loaded machine, or a build server shared by many jobs, 200 milliseconds is not enough, and the test fails without any bug in the code. That is a **flaky** test: it sometimes passes and sometimes fails on the same code. People stop trusting a flaky test, and then it hides real failures.
- **Right: wait for the state the test needs.** "Wait until exactly one borrower is waiting for a connection" is true or false, and it does not depend on the speed of the machine. Awaitility checks it every 100 milliseconds and continues the moment it is true. A passing test is as fast as the code allows. A failing test ends at the limit with a clear message.

The README states the rule: "The tests are deterministic: latches, Awaitility with bounds and a fake clock, never `Thread.sleep` to synchronize." I searched `src/test` for `sleep` and found nothing. The README table gives the library its job: "Bounded waits for a state in tests, instead of `Thread.sleep`."

## Where it appears

| Path | What it does with Awaitility |
| --- | --- |
| `gradle/libs.versions.toml` | `awaitility = "4.3.0"` and the library entry `awaitility`. |
| `build.gradle.kts` | `testImplementation(libs.awaitility)`. Only the tests see it. |
| `gradle.lockfile` | Locks `org.awaitility:awaitility:4.3.0` and the library it brings, `org.hamcrest:hamcrest:2.1` (`dependency-locking.md`). |
| `src/test/java/io/plainround/seatwave/concurrency/node/ConnectionPoolTest.java` | One wait, for a waiting borrower. |
| `src/test/java/io/plainround/seatwave/concurrency/node/HoldEndpointTest.java` | One wait, for a waiting borrower. |
| `src/test/java/io/plainround/seatwave/concurrency/node/WorkersTest.java` | Two waits, for the queue to empty and for the threads to finish. |
| `src/test/java/io/plainround/seatwave/concurrency/bench/ThreadCensusTest.java` | One wait, for three threads to be waiting. |
| `src/test/java/io/plainround/seatwave/concurrency/eventloop/EventLoopServerTest.java` | One wait that proves something does **not** happen. |

Six waits in all: `await()` appears 6 times in `src/test`.

## How it works underneath

1. `await()` returns a **builder** (an object you configure by calling methods on it, each returning the next step). Nothing waits yet.
2. `atMost(limit)` sets the longest time Awaitility is allowed to wait. If you leave it out, the limit is 10 seconds. I measured it: a condition that never became true ended with `ConditionTimeoutException` after 10,062 ms and the message `was not fulfilled within 10 seconds`.
3. `until(condition)` starts the waiting. The condition is a **lambda** (a small unnamed function) that returns `true` or `false`, for example `() -> workers.queued() == 0`.
4. Awaitility calls the condition from a **separate thread** that it creates, named `awaitility-thread`, not from the test's own thread. I printed the thread name inside a condition to check. The first call comes after a delay of 100 milliseconds, and then every 100 milliseconds. In my run the calls came at 113, 219 and 321 milliseconds after the start, and the condition turned true just before the third, so `until` returned after 322 milliseconds.
5. As soon as the condition returns `true`, `until` returns and the test goes on. If the limit passes first, `until` throws `org.awaitility.core.ConditionTimeoutException`. In a test that exception is an error, so the test fails and the message names the test class and the limit. I measured the message for a 300 millisecond limit: `Condition with Lambda expression in ... was not fulfilled within 300 milliseconds.`
6. Because the condition runs on another thread than the test, it reads values that the test thread and other threads change. That is safe in this repository because everything the conditions read (`pool.snapshot()`, `workers.queued()`, `Thread.getState()`) is built to be read from any thread. The pool counts waiting borrowers in an `AtomicInteger`, a counter designed for use by several threads at once.

The library also has a **`during`** step, which turns the question around: the condition must stay true for a whole time period. It is used once here, to prove that something does not happen. I measured it: `during(200 ms)` with a condition that is always true took 312 milliseconds, because the checks come every 100 milliseconds and the period was over at the third check after 300 milliseconds. If `atMost` is shorter than `during`, the wait can never succeed: with `during(3 s)` and `atMost(1 s)` I got `ConditionTimeoutException: ... was not fulfilled within 1 seconds`.

Awaitility 4.3.0 brings one other library, Hamcrest 2.1, which it uses for matcher conditions (a way to describe a condition as an object). This repository never calls a Hamcrest method itself. The README table says so ("It brings Hamcrest 2.1 ... into the test classpath"), and `gradle.lockfile` has the line `org.hamcrest:hamcrest:2.1=testCompileClasspath,testRuntimeClasspath`.

## Walkthrough of this repository's use

Every use of Awaitility in the repository is quoted below. The import in each of the five files is the same:

```java
import static org.awaitility.Awaitility.await;
```

A **static import** lets the test write `await()` instead of `Awaitility.await()`.

### `TestNodes.LONG_WAIT`: the limit that is never reached

All six waits use the same limit, a constant from the test helper `TestNodes`:

```java
  /**
   * A wait long enough never to expire in a passing test, and short enough to end a failing one.
   */
  public static final Duration LONG_WAIT = Duration.ofSeconds(5);
```

A `Duration` is a length of time. Five seconds is far longer than any of these steps takes on a normal machine (a few milliseconds), so a healthy test never comes near it. It is short enough that a broken test ends in five seconds instead of hanging the build. The limit is a **bound**, not a delay: a passing test does not wait 5 seconds, it waits until the condition is true.

### `ConnectionPoolTest`: wait until one borrower is waiting

```java
      ConnectionPool.Lease held = pool.borrow(SHORT_LIMIT).orElseThrow();
      Future<Optional<ConnectionPool.Lease>> waiting =
          borrower.submit(() -> pool.borrow(TestNodes.LONG_WAIT));
      await().atMost(TestNodes.LONG_WAIT).until(() -> pool.snapshot().waitingBorrowers() == 1);

      held.close();
```

The test takes the only connection of a pool of one, then starts a second borrower on its own thread with `borrower.submit(...)`. That second thread cannot get a connection, so it waits inside `pool.borrow`. The test must not give the connection back until the second thread is really waiting. If it did, the second borrower would get the connection at once and the test would prove nothing about waiting.

Without Awaitility the test could not know when the other thread had arrived. `submit` returns before the thread has run, so a plain next line would race with the thread. The `await()` line asks the pool itself: `waitingBorrowers()` is the count the pool keeps of borrowers inside `borrow`. When it is 1, the thread is in the wait. Only then does `held.close()` hand the connection back. After that the test can check, deterministically, that the waiting borrower got its connection.

### `HoldEndpointTest`: wait until the first hold is stuck on the pool

```java
      await()
          .atMost(TestNodes.LONG_WAIT)
          .until(() -> node.pool().snapshot().waitingBorrowers() == 1);
```

This is the same condition on the node's own pool. The node has one worker, a queue of one and a pool of one connection, and the test holds that connection. The first hold is picked up by the worker, which then waits for a connection. The second hold goes to the queue. The third must find the worker busy and the queue full. The test submits the second and third holds only after the wait, so the first hold is certainly in the worker and the queue is certainly free for the second. Without the wait, the first hold could still be in the queue when the second arrives, and the "refused at the door" result would depend on how fast the worker thread woke up.

### `WorkersTest`: wait until the worker has taken the first job, and until the jobs have ended

```java
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.queued() == 0);
```

`PlatformWorkers(1, 1)` has one thread and a queue that holds one job. The first `tryExecute` puts a job in the queue; the single worker thread then takes it out. The test waits for `queued()` to be 0, which means the worker has taken the first job and the queue is free. Only then does it add a second job, which fills the queue, and a third, which must be refused. Without the wait, the first job could still sit in the queue when the second arrives, and the second would be refused instead of the third.

```java
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.threadsAlive() == 0);
```

In the virtual-thread test, after the latch is released, the two running jobs finish and give their permits back. `threadsAlive()` is `maxInFlight` minus the free permits, so it reaches 0 when both are done. The test waits for that, and only then checks that a new job is accepted: a permit that is not given back would be a leak, and this line catches it.

### `ThreadCensusTest`: wait until three threads are waiting

```java
    await()
        .atMost(TestNodes.LONG_WAIT)
        .until(() ->
            threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING));
```

The test starts three threads that each wait on a latch with a time limit. A thread that waits with a time limit is in the state `TIMED_WAITING`. The census code under test takes a snapshot of the threads and their states, so the test must take the snapshot only when all three threads are really in that state. `thread.getState()` is a value that Java keeps for every thread. `allMatch` is true only when every thread in the list matches. If the census ran at once, a thread that had not yet reached the wait would be `RUNNABLE` and the count would be wrong.

### `EventLoopServerTest`: wait for nothing to happen

```java
      await().during(QUIET_PERIOD).atMost(TestNodes.LONG_WAIT).until(() -> !other.hasReply());
```

This test proves that a blocking call inside the event loop stalls every other connection. The first client's request is stuck in the loop. The test sends a second client's request and must now show that **no reply comes**. You cannot see "nothing happens" by looking once, because the reply might simply be late. So the test requires the condition `!other.hasReply()` to stay true for a quiet period of 200 milliseconds (`QUIET_PERIOD`, set at the top of the class as `Duration.ofMillis(200)`), checked every 100 milliseconds. If a reply arrives in that time, the condition turns false, the `during` requirement breaks, and the test fails. `atMost(TestNodes.LONG_WAIT)` is the outer bound, so the whole wait cannot run longer than 5 seconds.

This is the one honest use of a fixed time in the tests: proving that something does not happen needs a time window, because no event ends it. The window is a minimum, not a guess, and a slow machine does not break it. It can only make the test slightly slower. A fast machine cannot make it wrongly pass: if the loop were not blocked, the reply would arrive within a few milliseconds and the `during` requirement would fail.

## Try it yourself

1. Find every use of Awaitility:

```
grep -rn "await()" src/test | wc -l
```

   Expected: `6`.

2. Confirm there is no `Thread.sleep` in the tests:

```
grep -rn "sleep" src/test | wc -l
```

   Expected: `0`.

3. See Awaitility in the dependency tree:

```
./gradlew -q dependencies --configuration testRuntimeClasspath | grep -A1 '^+--- org.awaitility'
```

   Expected: `+--- org.awaitility:awaitility:4.3.0` followed by `|    \--- org.hamcrest:hamcrest:2.1`.

4. Run the tests that wait:

```
./gradlew cleanTest test --tests '*WorkersTest' --tests '*ConnectionPoolTest'
```

   Expected: eleven lines ending in `PASSED` (four from `WorkersTest` and seven from `ConnectionPoolTest`) and `BUILD SUCCESSFUL`.

5. See a wait fail on purpose, in a copy of the repository, never in the real one. In the copy, add a test file whose body is `await().atMost(Duration.ofMillis(300)).until(() -> false);` and run it. Expected: `ConditionTimeoutException: Condition with Lambda expression in ... was not fulfilled within 300 milliseconds.` That is what I got.

## Mistakes people make

- **`Thread.sleep` in a test.** Too long on a fast machine, too short on a slow one. Wait for the state instead.
- **No upper limit.** Awaitility's own default is 10 seconds. This repository always writes `atMost(TestNodes.LONG_WAIT)`, so every wait has a limit that a reader can see and change in one place.
- **Waiting for something the code under test does not guarantee.** The conditions here read counters that the pool and workers keep on purpose (`waitingBorrowers()`, `queued()`, `threadsAlive()`), which is why the tests can rely on them.
- **A `during` window longer than `atMost`.** The wait can never succeed and throws `ConditionTimeoutException`.
- **A condition with a side effect.** Awaitility calls the condition many times. A condition that changes state (adds to a list, takes a lock) would change it on every check. The conditions here only read.
- **Expecting an exact moment.** Awaitility checks every 100 milliseconds, so it can return up to 100 milliseconds after the state became true. Do not use it to measure time.

## Check yourself

1. *Why is `Thread.sleep(200)` a poor way to wait for another thread?* The right length depends on the speed of the machine, so the test wastes time on a fast one and fails on a slow one.
2. *What does `await().atMost(TestNodes.LONG_WAIT).until(...)` do when the condition never becomes true?* After 5 seconds it throws `ConditionTimeoutException` and the test fails with a message that names the condition and the limit.
3. *How often does Awaitility check the condition by default, and on which thread?* Every 100 milliseconds, from its own thread named `awaitility-thread`.
4. *Why does `ConnectionPoolTest` wait for `waitingBorrowers() == 1` before it closes the lease?* So the second borrower is surely already waiting; otherwise the test would not be testing a wait.
5. *What does `during(QUIET_PERIOD)` add?* The condition must stay true for the whole period, which is how a test proves that something does not happen.

## Further reading

- Awaitility project and usage guide: <https://github.com/awaitility/awaitility/wiki/Usage>
- Awaitility 4.3.0 Javadoc: <https://javadoc.io/doc/org.awaitility/awaitility/4.3.0/index.html>
- Awaitility home page: <http://www.awaitility.org/>
