# Tests of the `config`, `eventloop`, `sizing` and `threads` packages, walked through

## What it is

Four small packages sit beside the node and the benchmark. Each has its own test file or files:

- **`config`** reads a settings file. A **settings file** here is a `.properties` file: plain text with one `key=value` per line, where a line that starts with `#` is a comment. Java reads it into a `Properties` object, a table of text keys and text values. `SettingsReader` checks each value (is it a whole number, is it in range, is it present?) and collects **every** problem, so a person fixing a file sees all the mistakes in one run.
- **`sizing`** is the calculator of **Little's Law**. The law says that in a steady system the average number of things inside equals the arrival rate times the time each one spends inside. If 500 holds arrive per second and each holds a connection for 0.040 seconds, then 500 x 0.040 = 20 connections are busy on average. The program uses it to size a connection pool.
- **`eventloop`** is Lab 3: a tiny network server with **one thread** that serves many connections. A **socket** is one end of a network connection. The **loopback address** (`127.0.0.1`) is the address of your own computer: bytes sent to it never leave the machine. An **event loop** is a loop on one thread that waits for something to happen on any connection, handles it quickly and goes back to waiting. It is fast as long as no handler **blocks**. A handler that blocks, for example one that waits for a database, stops the one thread, and every other connection waits behind it.
- **`threads`** has two demos. `ThreadCost` measures what it costs to start and keep thousands of threads that only wait. A **platform thread** is an operating-system thread, with its own stack of memory. A **virtual thread** is a light thread that Java schedules itself on top of a few platform threads called **carriers**. `CarrierPinning` shows a case where a virtual thread cannot give its carrier back (it is **pinned**), and so blocks other virtual threads.

`junit.md`, `assertj.md` and `awaitility.md` explain the test tools, and `tests-node-walkthrough.md` explains latches, fake clocks and why every wait here has a limit.

## Why this repository uses it

These packages are the other half of the module. Little's Law (`sizing`) tells you how big a pool must be before you build it. The event loop (`eventloop`) shows why "one thread, never block" works and what happens when you break the rule. The thread-cost and pinning demos (`threads`) put numbers on platform threads against virtual threads. Each one prints a result for a learner, so each one needs a test that says the printed result is right. The coverage of the four packages (`jacoco.md`): `config` 78 of 80 lines, `sizing` 108 of 128 (the missing 20 are `SizingCli`, `SizingInputs` and `SizingReport` failure branches), `eventloop` 166 of 231, `threads` 57 of 126. The `main` methods of the command-line classes (`SizingCli`, `EventLoopCli`, `ThreadCostCli`, `PinningCli`) are not called by any test.

## Where it appears

| Path | What it does |
| --- | --- |
| `src/test/java/io/plainround/seatwave/concurrency/config/SettingsReaderTest.java` | 3 tests of the settings reader. |
| `.../eventloop/EventLoopServerTest.java` | 6 tests of the event-loop server and of the lab that measures it. |
| `.../sizing/LittlesLawTest.java` | 7 tests of the Little's Law arithmetic and the sizing report. |
| `.../threads/CarrierPinningTest.java` | 2 tests of the pinning demo. |
| `.../threads/ThreadCostTest.java` | 3 tests of the thread-cost measurement. |

## How it works underneath

1. **Pure arithmetic is the easiest thing to test.** `LittlesLaw` and `SettingsReader` use no thread and no clock. A test gives them a number and checks the number that comes out.
2. **A server test needs a client.** `EventLoopServerTest` starts the real server on the loopback address, on a port the operating system picks (port 0 means "any free port"), and connects to it with ordinary blocking sockets from the test thread.
3. **A latch controls "slow" work.** The server calls a piece of **blocking work** (a function) for each hold. In production that stands for a database call. In the test the work waits on a latch that the test opens. So "the call takes a long time" is not a measured number of milliseconds but a state the test controls: the call is stuck until the test says go. The class comment says it: "The blocking call waits on a latch the test controls, so the tests never depend on how long anything takes."
4. **Proving that something does not happen needs a time window.** No event ends "nothing happened". The test waits a quiet period (200 ms) and requires that no reply arrives. This is the one place where a fixed time is used, as a minimum and not as a guess (`awaitility.md`).
5. **The demos differ by a whole blocking period.** The pinning demo blocks for 500 ms. A task that starts at once takes about a millisecond, and a task that waits for a pinned carrier takes about 500 ms. The two results differ by a factor of 500, so a test can compare them safely on any machine: the class comment says "The two demos differ by a whole blocking period, so the comparison is safe on any machine."

## Walkthrough of this repository's use

Words that first appear here.

- **`varargs`** (`String... keysAndValues`) is a parameter that takes any number of arguments: a call can pass two, four or sixteen strings, and the method sees them as an array.
- **`Properties`** has `setProperty(key, value)` to store a pair and `getProperty(key)` to read one.
- **A nested class**, written inside another class (`private static final class Gate`), is a helper that only this file uses.
- **`implements AutoCloseable`** makes a class usable in a try-with-resources block (`tests-node-walkthrough.md`).
- **`Duration`** is Java's object for a length of time; `Duration.ofMillis(200)` is 200 milliseconds.
- **A `Callable`** is a small function that returns a value and may throw.

### `SettingsReaderTest.java`

```java
package io.plainround.seatwave.concurrency.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final int TEN = 10;
```

The imports include `Files`, `Path` and `StandardCharsets` for the file test, `Properties` for the settings, and `TempDir`. `ExecutorMode` is the two-value type that `enumList` reads (`PLATFORM` and `VIRTUAL`). `TEN = 10` is a limit used in the range checks.

```java
  @Test
  void readsEveryKindOfValue() {
    SettingsReader reader =
        reader(
            "count",
            "7",
            "rates",
            "200, 320",
            "wait.millis",
            "40",
            "share",
            "0.05",
            "modes",
            "platform,virtual");

    assertThat(reader.intBetween("count", 1, TEN)).isEqualTo(7);
    assertThat(reader.intListBetween("rates", 1, Integer.MAX_VALUE)).containsExactly(200, 320);
    assertThat(reader.millisBetween("wait.millis", 0, Integer.MAX_VALUE))
        .isEqualTo(Duration.ofMillis(40));
    assertThat(reader.decimalBetween("share", 0, 1)).isEqualTo(0.05);
    assertThat(reader.enumList("modes", ExecutorMode.class))
        .containsExactly(ExecutorMode.PLATFORM, ExecutorMode.VIRTUAL);
    assertThat(reader.problems()).isEmpty();
  }
```

**`readsEveryKindOfValue`** proves that every reader method turns text into the right Java value. The helper `reader(...)` (at the end of the file) builds a `SettingsReader` from pairs of key and value. The five pairs and what they test:

- `count` = `7`: `intBetween("count", 1, TEN)` returns the whole number 7, accepted because it lies from 1 to 10.
- `rates` = `200, 320`: `intListBetween("rates", 1, Integer.MAX_VALUE)` returns the list 200, 320. The space after the comma does not matter, because the reader strips each item.
- `wait.millis` = `40`: `millisBetween(...)` returns a `Duration` of 40 milliseconds.
- `share` = `0.05`: `decimalBetween("share", 0, 1)` returns the decimal number 0.05.
- `modes` = `platform,virtual`: `enumList("modes", ExecutorMode.class)` returns the two enum values. Upper or lower case does not matter.
- At the end `reader.problems()` is empty: no problem was reported for valid input.

```java
  @Test
  void reportsEveryProblemInOneRun() {
    SettingsReader reader =
        reader(
            "count",
            "70",
            "word",
            "seven",
            "rates",
            "200,,320",
            "share",
            "2",
            "modes",
            "green",
            "list",
            "1,x",
            "big",
            "1,99",
            "decimal",
            "half");

    reader.intBetween("count", 1, TEN);
    reader.intBetween("word", 1, TEN);
    reader.intBetween("absent", 1, TEN);
    reader.intListBetween("rates", 1, TEN);
    reader.intListBetween("list", 1, TEN);
    reader.intListBetween("big", 1, TEN);
    reader.decimalBetween("share", 0, 1);
    reader.decimalBetween("decimal", 0, 1);
    reader.enumList("modes", ExecutorMode.class);

    assertThat(reader.problems())
        .containsExactly(
            "count must be from 1 to 10, but was 70",
            "word must be a whole number, but was seven",
            "absent is missing",
            "rates has an empty item",
            "list must list whole numbers, but one was x",
            "every value of big must be from 1 to 10, but one was 99",
            "share must be from 0.0 to 1.0, but was 2",
            "decimal must be a decimal number, but was half",
            "modes has an unknown value: green");
  }
```

**`reportsEveryProblemInOneRun`** proves that a bad file is reported **completely**. The test gives the reader eight keys and then makes nine read calls (one for a key that is not set at all), and every call meets something wrong. Each wrong value adds one line to the problem list, and the final check compares the whole list, in order:

| Key | Value | Call | Problem line |
| --- | --- | --- | --- |
| `count` | `70` | `intBetween("count", 1, TEN)` | `count must be from 1 to 10, but was 70` |
| `word` | `seven` | `intBetween("word", 1, TEN)` | `word must be a whole number, but was seven` |
| `absent` | (not set) | `intBetween("absent", 1, TEN)` | `absent is missing` |
| `rates` | `200,,320` | `intListBetween("rates", 1, TEN)` | `rates has an empty item` |
| `list` | `1,x` | `intListBetween("list", 1, TEN)` | `list must list whole numbers, but one was x` |
| `big` | `1,99` | `intListBetween("big", 1, TEN)` | `every value of big must be from 1 to 10, but one was 99` |
| `share` | `2` | `decimalBetween("share", 0, 1)` | `share must be from 0.0 to 1.0, but was 2` |
| `decimal` | `half` | `decimalBetween("decimal", 0, 1)` | `decimal must be a decimal number, but was half` |
| `modes` | `green` | `enumList("modes", ExecutorMode.class)` | `modes has an unknown value: green` |

The return values are not checked here, because after a problem a reader returns a harmless default (the minimum, or an empty list). What matters is the message: it names the key, says what was expected and shows what was found, so the person who edits the file knows what to fix. A reader that stopped at the first error would make them run the program nine times.

```java
  @Test
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("settings.properties");
    Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8);

    Properties loaded = SettingsReader.load(file);

    assertThat(new SettingsReader(loaded).text("name")).isEqualTo("Arena Night");
  }
```

**`loadsAFileAsUtf8`** proves that a real file is read, with comments, in the UTF-8 character encoding. `@TempDir Path folder` is a new empty folder. The test writes `# a comment\nname=Arena Night\n` into `settings.properties` in it (`\n` is a line break). `SettingsReader.load(file)` reads the file, and `text("name")` returns `Arena Night`: the comment line was ignored and the value came through. Naming `StandardCharsets.UTF_8` on both the write and the read makes the result independent of the computer's default encoding.

```java
  private static SettingsReader reader(String... keysAndValues) {
    Properties properties = new Properties();
    List<String> pairs = List.of(keysAndValues);
    for (int pairIndex = 0; pairIndex < pairs.size(); pairIndex += 2) {
      properties.setProperty(pairs.get(pairIndex), pairs.get(pairIndex + 1));
    }
    return new SettingsReader(properties);
  }
}
```

`reader(String... keysAndValues)` is the helper. It makes a `Properties` object, turns the arguments into a list of `pairs`, and walks through it **two at a time** (`pairIndex += 2`), storing `pairs.get(pairIndex)` as the key and the next element as its value. Writing the pairs as one flat list keeps the tests short.

### `EventLoopServerTest.java`

The server (`EventLoopServer`) speaks a tiny text protocol over TCP. A client sends one line, `HOLD A-1`, ending in a line break. The server answers one line: `201 A-1` for a hold, `400 bad request` for anything that does not start with `HOLD `, and `503 busy` if too many holds are already running off the loop. A line longer than 64 bytes (`EventLoopServer.MAX_LINE_BYTES`) makes the server close the connection. The constructor takes the **mode**, the **blocking work** (a function from the seat label to the reply, standing for a database call), the largest number of offloaded holds, and a limit for closing.

- `LoopMode.BLOCKING_IN_LOOP`: the loop thread runs the blocking work itself. This is the mistake that the lab demonstrates.
- `LoopMode.OFFLOADED`: the loop thread hands the work to a virtual thread and goes back to waiting, and the answer comes back to the loop when it is ready.

```java
package io.plainround.seatwave.concurrency.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.concurrency.node.TestNodes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The event loop with a blocking call inside it, and with the call moved off it. The blocking call
 * waits on a latch the test controls, so the tests never depend on how long anything takes.
 */
class EventLoopServerTest {

  private static final String BLOCKED_SEAT = "A-1";
  private static final Duration QUIET_PERIOD = Duration.ofMillis(200);
  private static final int ONE_IN_FLIGHT = 1;
  private static final int MANY_IN_FLIGHT = 10;
  private static final int SOCKET_TIMEOUT_MILLIS = 5000;

```

- The imports show the tools: `Socket`, `BufferedReader` and `InputStreamReader` for the test client, `Duration` for the quiet period, `CountDownLatch` and `TimeUnit` for the gate, `Awaitility.await`, and `TestNodes` for the `LONG_WAIT` limit.
- The class comment says what the file tests: "The event loop with a blocking call inside it, and with the call moved off it."
- `BLOCKED_SEAT = "A-1"` is the seat whose hold the gate blocks. Every other seat gets its reply at once.
- `QUIET_PERIOD` is 200 milliseconds, the length of time the test watches for silence.
- `ONE_IN_FLIGHT = 1` and `MANY_IN_FLIGHT = 10` are the bounds on offloaded holds: one means "a second hold is refused", ten means "no hold is refused".
- `SOCKET_TIMEOUT_MILLIS = 5000` is the limit on every socket call of the test client.

```java
  @Test
  void aBlockingCallInsideTheLoopStallsEveryOtherConnection() throws Exception {
    Gate gate = new Gate();
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.BLOCKING_IN_LOOP, gate::hold, MANY_IN_FLIGHT, TestNodes.LONG_WAIT);
        Client blocked = new Client(server);
        Client other = new Client(server)) {
      blocked.send("HOLD " + BLOCKED_SEAT);
      gate.awaitStarted();

      other.send("HOLD B-2");

      await().during(QUIET_PERIOD).atMost(TestNodes.LONG_WAIT).until(() -> !other.hasReply());
      gate.open();
      assertThat(blocked.reply()).isEqualTo("201 " + BLOCKED_SEAT);
      assertThat(other.reply()).isEqualTo("201 B-2");
    }
  }
```

**`aBlockingCallInsideTheLoopStallsEveryOtherConnection`** is the lab's lesson as a test: one blocked call freezes all connections.

1. `Gate gate = new Gate()` is the controllable blocking work (explained below). Its method `gate::hold` is passed to the server as the blocking work. `gate::hold` is a method reference: "the `hold` method of this gate".
2. The `try` lists three resources: the server in the broken mode `BLOCKING_IN_LOOP`, and two clients, `blocked` and `other`. They are closed in reverse order at the end: `other`, `blocked`, then the server.
3. `blocked.send("HOLD " + BLOCKED_SEAT)` sends `HOLD A-1`. The loop thread reads it and runs the work **on itself**. The work signals "started" and then waits on the gate.
4. `gate.awaitStarted()` waits until the loop thread is inside the work. Only now is it certain that the loop is blocked. A test without this step could send the second request before the first one reached the work.
5. `other.send("HOLD B-2")` sends a second request on a second connection. The loop thread cannot read it, because it is stuck in the first call.
6. `await().during(QUIET_PERIOD).atMost(TestNodes.LONG_WAIT).until(() -> !other.hasReply())` requires that for 200 milliseconds the second client gets **no reply**. A reply would turn the condition false and fail the test. `hasReply()` is a non-blocking check, so the test thread does not get stuck.
7. `gate.open()` lets the first call finish. Then both clients get their answers: `201 A-1` for `blocked` and `201 B-2` for `other` (`reply()` waits up to five seconds for a line). Both answers arrive late but correct: nothing was lost, the second hold only waited.

```java
  @Test
  void movingTheCallOffTheLoopKeepsOtherConnectionsAnswered() throws Exception {
    Gate gate = new Gate();
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.OFFLOADED, gate::hold, MANY_IN_FLIGHT, TestNodes.LONG_WAIT);
        Client blocked = new Client(server);
        Client other = new Client(server)) {
      blocked.send("HOLD " + BLOCKED_SEAT);
      gate.awaitStarted();

      other.send("HOLD B-2");

      assertThat(other.reply()).isEqualTo("201 B-2");
      assertThat(blocked.hasReply()).isFalse();
      gate.open();
      assertThat(blocked.reply()).isEqualTo("201 " + BLOCKED_SEAT);
    }
  }
```

**`movingTheCallOffTheLoopKeepsOtherConnectionsAnswered`** is the fix. The same steps run in `OFFLOADED` mode. The loop thread hands `HOLD A-1` to a virtual thread, which waits on the gate, and the loop goes back to waiting. So when `other` sends `HOLD B-2`, the loop answers at once: `other.reply()` returns `201 B-2` while the gate is still closed. Meanwhile `blocked.hasReply()` is `false`: the first hold is still waiting in its own thread, so nothing could have answered it. When the gate opens, `blocked.reply()` returns `201 A-1`. The test needs no quiet period here. The reply of `other` is the proof, and it arrives as fast as the machine allows, bounded by the five-second socket timeout.

```java
  @Test
  void refusesWith503WhenTheOffloadBoundIsReached() throws Exception {
    Gate gate = new Gate();
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.OFFLOADED, gate::hold, ONE_IN_FLIGHT, TestNodes.LONG_WAIT);
        Client blocked = new Client(server);
        Client other = new Client(server)) {
      blocked.send("HOLD " + BLOCKED_SEAT);
      gate.awaitStarted();

      other.send("HOLD B-2");

      assertThat(other.reply()).isEqualTo("503 busy");
      gate.open();
      assertThat(blocked.reply()).isEqualTo("201 " + BLOCKED_SEAT);
    }
  }
```

**`refusesWith503WhenTheOffloadBoundIsReached`** proves the bound on offloaded holds. With `ONE_IN_FLIGHT` the server allows one offloaded hold. The first hold (`A-1`) takes it and waits on the gate. The second hold (`B-2`) finds no free permit, so the server answers `503 busy` at once, without queueing. After the gate opens, the first answer is `201 A-1`. Every queue and wait in the project has a limit; this test shows that the event-loop lab keeps the rule.

```java
  @Test
  void answersAnUnknownCommandWith400() throws Exception {
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.BLOCKING_IN_LOOP,
                seat -> "201 " + seat,
                ONE_IN_FLIGHT,
                TestNodes.LONG_WAIT);
        Client client = new Client(server)) {
      client.send("DROP TABLE seats");

      assertThat(client.reply()).isEqualTo("400 bad request");
    }
  }

  @Test
  void closesAConnectionThatSendsALineLongerThanTheLimit() throws Exception {
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.BLOCKING_IN_LOOP,
                seat -> "201 " + seat,
                ONE_IN_FLIGHT,
                TestNodes.LONG_WAIT);
        Client client = new Client(server)) {
      client.sendWithoutLineEnd("H".repeat(EventLoopServer.MAX_LINE_BYTES + 1));

      assertThat(client.reply()).isNull();
    }
  }
```

- **`answersAnUnknownCommandWith400`**: the work is `seat -> "201 " + seat`, a lambda that answers at once and never blocks. The client sends `DROP TABLE seats` (the kind of text an attacker might try). The server answers `400 bad request`. It never calls the work, since the line does not start with `HOLD `.
- **`closesAConnectionThatSendsALineLongerThanTheLimit`**: the client sends 65 letters `H` without a line break (`"H".repeat(EventLoopServer.MAX_LINE_BYTES + 1)`, one over the limit of 64). The server must not keep an unbounded line in memory, so it closes the connection. For the client, a closed connection means the end of the stream, and `reply()` returns `null` (`readLine` returns `null` at the end of the stream). This is a bound on input size, in the same spirit as the bounds on queues.

```java
  @Test
  void theLabMeasuresOneWaitPerClient() throws Exception {
    EventLoopLab.Settings settings =
        new EventLoopLab.Settings(3, Duration.ZERO, TestNodes.LONG_WAIT, MANY_IN_FLIGHT);

    List<Duration> blocking = EventLoopLab.run(LoopMode.BLOCKING_IN_LOOP, settings);
    List<Duration> offloaded = EventLoopLab.run(LoopMode.OFFLOADED, settings);

    assertThat(blocking).hasSize(3).isSorted();
    assertThat(offloaded).hasSize(3).isSorted();
  }
```

**`theLabMeasuresOneWaitPerClient`** tests `EventLoopLab`, the code that the `eventLoop` task runs. `EventLoopLab.Settings(3, Duration.ZERO, TestNodes.LONG_WAIT, MANY_IN_FLIGHT)` means: 3 clients, a work time of zero, a socket timeout of five seconds, and ten offloaded holds at most. The test runs the lab in both modes and checks only what is true everywhere: each run returns a list of exactly 3 durations (one per client), and the list is sorted from the shortest to the longest wait (`isSorted()`). With a work time of zero the numbers themselves are tiny and different on every run, so no value is checked.

```java
  /** Blocking work that waits until the test opens it, and says when it has started. */
  private static final class Gate {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch opened = new CountDownLatch(1);

    String hold(String seatLabel) throws InterruptedException {
      if (BLOCKED_SEAT.equals(seatLabel)) {
        started.countDown();
        if (!opened.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("the test never opened the gate");
        }
      }
      return "201 " + seatLabel;
    }

    void awaitStarted() throws InterruptedException {
      assertThat(started.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    void open() {
      opened.countDown();
    }
  }
```

`Gate` is the controllable blocking work. It implements the server's `BlockingWork` through its method `hold(seatLabel)`, which returns the reply text.

- Two latches with a count of 1: `started` (the work says "I am running") and `opened` (the test says "you may go on").
- `hold` for the blocked seat counts down `started`, then waits on `opened` for at most `LONG_WAIT`. If the time passes, it throws `IllegalStateException("the test never opened the gate")`, so a forgotten `gate.open()` fails fast and does not hang. After the wait it returns `"201 " + seatLabel`. For any other seat it returns at once, without touching a latch.
- `awaitStarted()` waits for `started` and asserts that it really happened within five seconds (`assertThat(started.await(...)).isTrue()`).
- `open()` counts down `opened`.

The gate is how the tests stay deterministic: "the call is slow" is replaced by "the call is stuck until the test says go".

```java
  /** One blocking test client on the loopback address. Used by one test thread. */
  private static final class Client implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;

    Client(EventLoopServer server) throws IOException {
      socket = new Socket();
      socket.connect(server.address(), SOCKET_TIMEOUT_MILLIS);
      socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
      in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    void send(String line) throws IOException {
      sendWithoutLineEnd(line + "\n");
    }

    void sendWithoutLineEnd(String text) throws IOException {
      socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
    }

    boolean hasReply() throws IOException {
      return in.ready();
    }

    String reply() throws IOException {
      return in.readLine();
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
```

`Client` is a small blocking test client. It implements `AutoCloseable`, so the tests can list it in a `try`.

- The constructor opens a `Socket`, connects it to `server.address()` with a connect timeout of 5,000 ms, sets a read timeout of 5,000 ms (`setSoTimeout`) so no read can wait for ever, and wraps the input stream in a `BufferedReader` that decodes UTF-8 lines.
- `send(line)` sends the line plus `\n`. `sendWithoutLineEnd(text)` writes the bytes (`text.getBytes(StandardCharsets.UTF_8)`) and **flushes**, which pushes them out of the buffer at once. It is used directly by the long-line test.
- `hasReply()` returns `in.ready()`: true when a line can be read without blocking. The quiet-period check uses it.
- `reply()` returns `in.readLine()`: the next line, waiting up to the read timeout, or `null` if the server closed the connection.
- `close()` closes the socket.

The class comment says "Used by one test thread": the client is not safe for use by several threads at once, and the tests do not do that.

### `LittlesLawTest.java`

`LittlesLaw` has four functions, all pure arithmetic:

- `inFlight(rate, time)` is rate x time: the average number of holds in flight.
- `poolSize(rate, time, ceiling)` is the number of connections that keeps the pool at or below a **ceiling** of utilization (for example 0.8 for 80 percent busy): `ceiling-of(inFlight / ceiling)`, and never below 1.
- `capacityPerSecond(poolSize, time)` is `poolSize / time`: how many holds per second a pool of that size can carry at most.
- `waitingFactor(u)` is `u / (1 - u)`: how many times longer than the service time a queue waits, on average, at utilization `u`, for one server. At 50 percent it is 1, at 99 percent it is 99. This is the curve behind "latency jumps as a pool nears full use".

```java
package io.plainround.seatwave.concurrency.sizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LittlesLawTest {

  private static final Duration FORTY_MILLIS = Duration.ofMillis(40);
  private static final int HOLDS_PER_SECOND = 500;
  private static final double CEILING = 0.8;
  private static final double TOLERANCE = 1e-9;
```

`within` is AssertJ's helper for a tolerance (`assertj.md`). `FORTY_MILLIS` is a `Duration` of 40 ms, `HOLDS_PER_SECOND = 500`, `CEILING = 0.8` and `TOLERANCE = 1e-9` (one billionth, written in scientific notation). A `double` carries a tiny rounding error, so the tests compare decimals with a tolerance and not with exact equality.

```java
  @Test
  void puts20HoldsInFlightAt500PerSecondAnd40Milliseconds() {
    assertThat(LittlesLaw.inFlight(HOLDS_PER_SECOND, FORTY_MILLIS))
        .isCloseTo(20, within(TOLERANCE));
  }
```

**`puts20HoldsInFlightAt500PerSecondAnd40Milliseconds`**: 500 holds per second times 0.040 seconds is 20. The name of the test is the calculation, so a reader sees the example from the README (the SeatWave walkthrough of Module 4).

```java
  @Test
  void sizesThePoolAt25ConnectionsForAn80PercentCeiling() {
    assertThat(LittlesLaw.poolSize(HOLDS_PER_SECOND, FORTY_MILLIS, CEILING)).isEqualTo(25);
    assertThat(LittlesLaw.capacityPerSecond(25, FORTY_MILLIS)).isCloseTo(625, within(TOLERANCE));
  }
```

**`sizesThePoolAt25ConnectionsForAn80PercentCeiling`**: 20 holds in flight, divided by 0.8, is 25 connections. `poolSize` returns a whole number, so it is compared with `isEqualTo(25)`. Then `capacityPerSecond(25, FORTY_MILLIS)` is 25 / 0.040 = 625 holds per second, which is more than the 500 that arrive, and 500 / 625 is exactly 80 percent busy.

```java
  @Test
  void neverSizesAPoolBelowOne() {
    assertThat(LittlesLaw.poolSize(1, Duration.ofNanos(1), 1)).isEqualTo(1);
  }
```

**`neverSizesAPoolBelowOne`**: with a tiny load, 1 hold per second held for 1 nanosecond with a ceiling of 1, the number of holds in flight is one billionth. Rounded up it is 1, and the function guards the lower end explicitly (`Math.max(1, ...)`): a pool of zero connections is not a pool. The answer is 1.

```java
  @Test
  void growsTheWaitingFactorSteeplyNearFullUse() {
    assertThat(LittlesLaw.waitingFactor(0.5)).isCloseTo(1, within(TOLERANCE));
    assertThat(LittlesLaw.waitingFactor(0.8)).isCloseTo(4, within(TOLERANCE));
    assertThat(LittlesLaw.waitingFactor(0.99)).isCloseTo(99, within(TOLERANCE));
  }
```

**`growsTheWaitingFactorSteeplyNearFullUse`**: the three points of the curve, 1 at 50 percent, 4 at 80 percent and 99 at 99 percent. The test pins the shape that the module teaches: waiting grows slowly at first and then explodes near full use.

```java
  @Test
  void rejectsImpossibleInputs() {
    assertThatThrownBy(() -> LittlesLaw.inFlight(0, FORTY_MILLIS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.inFlight(Double.NaN, FORTY_MILLIS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.poolSize(HOLDS_PER_SECOND, FORTY_MILLIS, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.capacityPerSecond(0, FORTY_MILLIS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.waitingFactor(1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**`rejectsImpossibleInputs`**: each wrong input must throw `IllegalArgumentException`. A rate of 0, a rate that is `NaN` ("not a number", the result of a calculation such as 0 divided by 0), a ceiling of 0, a pool of 0 connections and a utilization of 1 (full use, where the factor would divide by zero) are all refused. Every refusal is a case where the formula would give nonsense or an error that hides the real cause.

```java
  @Test
  void printsTheWalkthroughSizing() {
    SizingInputs inputs =
        SizingInputs.from(new SettingsReader(walkthrough())).discardError().orElseThrow();

    String report = SizingReport.render(inputs);

    assertThat(report)
        .contains(
            "= 20.0 holds",
            "= 25 connections; the database allows 32: fits",
            "= 10.0 vCPUs busy",
            "250 %: one node cannot serve this",
            "safe at 70 %: 560 seat maps/s",
            "= 4 nodes",
            "80 %: 4.0");
  }
```

**`printsTheWalkthroughSizing`** tests the whole report of the `sizing` task. `walkthrough()` (below) holds the inputs of the course's worked example. `SizingInputs.from(...)` reads them and `SizingReport.render(inputs)` returns the text. The test checks that the text contains the seven key results, each a piece of a line that the README quotes:

- `= 20.0 holds`: the holds in flight.
- `= 25 connections; the database allows 32: fits`: the pool size, checked against the database limit of 32.
- `= 10.0 vCPUs busy`: the seat-map load, 2,000 per second times 5 ms of CPU each is 10 CPUs.
- `250 %: one node cannot serve this`: those 10 CPUs on a node with 4 are 250 percent.
- `safe at 70 %: 560 seat maps/s`: a safe rate at a 70 percent CPU target.
- `= 4 nodes`: the number of nodes needed.
- `80 %: 4.0`: the waiting factor at 80 percent.

```java
  @Test
  void listsMissingSizingInputs() {
    Properties properties = walkthrough();
    properties.remove("node.vcpus");

    Result<SizingInputs, List<String>> read = SizingInputs.from(new SettingsReader(properties));

    assertThat(((Result.Error<SizingInputs, List<String>>) read).error())
        .containsExactly("node.vcpus is missing");
  }

  private static Properties walkthrough() {
    Properties properties = new Properties();
    properties.setProperty("hold.rate.per.second", "500");
    properties.setProperty("hold.time.millis", "40");
    properties.setProperty("database.max.connections", "32");
    properties.setProperty("seatmap.rate.per.second", "2000");
    properties.setProperty("seatmap.cpu.millis", "5");
    properties.setProperty("node.vcpus", "4");
    properties.setProperty("pool.utilization.ceiling.percent", "80");
    properties.setProperty("cpu.utilization.target.percent", "70");
    return properties;
  }
}
```

- **`listsMissingSizingInputs`** removes `node.vcpus` and expects an error list with exactly one message, `node.vcpus is missing`. It shows that `SizingInputs` reports through the same reader and the same `Result` as the other settings (`result4j.md`), and that the cast opens the error case.
- `walkthrough()` builds the eight inputs: 500 holds per second, a hold time of 40 ms, a database limit of 32 connections, 2,000 seat maps per second at 5 ms of CPU each, a node with 4 vCPUs, a pool ceiling of 80 percent and a CPU target of 70 percent. They are the numbers of `config/sizing.properties`.

### `CarrierPinningTest.java`

A **carrier** is a platform thread that runs virtual threads. By default Java makes as many carriers as there are processors. When a virtual thread blocks, for example in `Thread.sleep`, it is **unmounted**: Java takes it off its carrier and parks it in memory, and the carrier runs another virtual thread. A virtual thread is **pinned** when it blocks without being able to unmount. It then holds its carrier for the whole wait. Blocking inside a **class initializer** (the code that runs once, the first time a class is used) pins the thread in Java 25.

The demo starts one blocking virtual thread for every carrier. Then it starts one more, unrelated virtual thread and measures how long that one waits before it starts running. If the blockers sleep (unmount), the carriers are free and the delay is tiny. If the blockers are pinned, every carrier is stuck, and the unrelated thread waits until a blocker finishes.

```java
package io.plainround.seatwave.concurrency.threads;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.concurrency.node.TestNodes;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The two demos differ by a whole blocking period, so the comparison is safe on any machine: an
 * unmounted blocker frees its carrier at once, and a pinned one frees it only when it stops
 * blocking. The pinned demo touches a class that only this test initializes.
 */
class CarrierPinningTest {

```

The class comment explains why the comparison is safe on any machine: "The two demos differ by a whole blocking period, so the comparison is safe on any machine: an unmounted blocker frees its carrier at once, and a pinned one frees it only when it stops blocking. The pinned demo touches a class that only this test initializes." The last sentence matters: a class initializer runs only **once** in a JVM. If another test had triggered it earlier, the pinned demo would find the class already initialized and would not block. Only this test touches that class.

```java
  @Test
  void anUnrelatedTaskStartsAtOnceWhileEveryCarrierSleepsUnmounted() throws Exception {
    Duration delay = CarrierPinning.delayWhileSleeping(TestNodes.LONG_WAIT);

    assertThat(delay).isLessThan(CarrierPinning.BLOCKS_FOR);
  }
```

**`anUnrelatedTaskStartsAtOnceWhileEveryCarrierSleepsUnmounted`**: `CarrierPinning.delayWhileSleeping(TestNodes.LONG_WAIT)` runs the demo with `Thread.sleep` blockers and returns the delay as a `Duration`. The check: the delay is **less than** `CarrierPinning.BLOCKS_FOR`, the 500 ms that each blocker sleeps. In my run the delay was about 1 ms. The bound has a very large margin: even a slow machine starts a task in far less than 500 ms. `LONG_WAIT` is the limit on every step inside the demo: if the blockers did not start within five seconds, the demo throws `TimeoutException` instead of hanging.

```java
  @Test
  void anUnrelatedTaskWaitsWhileEveryCarrierIsPinnedByAClassInitializer() throws Exception {
    Duration delay = CarrierPinning.delayWhilePinned(TestNodes.LONG_WAIT);

    assertThat(delay).isGreaterThanOrEqualTo(CarrierPinning.BLOCKS_FOR.dividedBy(2));
  }
}
```

**`anUnrelatedTaskWaitsWhileEveryCarrierIsPinnedByAClassInitializer`**: `delayWhilePinned` runs the same demo with blockers that touch the slow class. The check: the delay is **at least** half the blocking period, `CarrierPinning.BLOCKS_FOR.dividedBy(2)`, 250 ms. In my run it was about 505 ms. Why half and not the full 500: a timer or the scheduler can shave a little off, and the test must not fail on a small measurement difference. The two results are far apart, 1 ms against 505 ms, and the two bounds, under 500 and over 250, sit between them with room on both sides.

### `ThreadCostTest.java`

`ThreadCost.measure(kind, threads, waitLimit)` starts the given number of threads of one kind (platform or virtual), each of which waits on a latch, and measures how long the start took, how much heap memory (the memory for objects) grew and how many platform threads the JVM has. It returns a `ThreadCost.Result` record.

```java
package io.plainround.seatwave.concurrency.threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.TestNodes;
import org.junit.jupiter.api.Test;

class ThreadCostTest {

  private static final int PLATFORM_THREADS = 20;
  private static final int VIRTUAL_THREADS = 2000;

```

`PLATFORM_THREADS = 20` and `VIRTUAL_THREADS = 2000` are the sizes of the two runs: 20 platform threads and 2,000 virtual threads. The real measurement uses 1,000 to 100,000, which would be too slow for a test.

```java
  @Test
  void startsEveryPlatformThreadAndSeesThemInTheJvm() throws Exception {
    ThreadCost.Result result =
        ThreadCost.measure(ExecutorMode.PLATFORM, PLATFORM_THREADS, TestNodes.LONG_WAIT);

    assertThat(result.threads()).isEqualTo(PLATFORM_THREADS);
    assertThat(result.platformThreadsInJvm()).isGreaterThanOrEqualTo(PLATFORM_THREADS);
    assertThat(result.startTime()).isPositive();
  }
```

**`startsEveryPlatformThreadAndSeesThemInTheJvm`** starts 20 platform threads. `result.threads()` is 20: all of them started. `result.platformThreadsInJvm()` is **at least** 20: the JVM counts its own threads too (the main thread and the test thread among them), so the count is 20 plus some, never less. `result.startTime()` is positive (`isPositive()`): starting takes some time, and a duration of zero or less would mean the timer was not used. None of the checks depends on how fast the machine is.

```java
  @Test
  void startsThousandsOfVirtualThreadsWithoutAPlatformThreadEach() throws Exception {
    ThreadCost.Result result =
        ThreadCost.measure(ExecutorMode.VIRTUAL, VIRTUAL_THREADS, TestNodes.LONG_WAIT);

    assertThat(result.threads()).isEqualTo(VIRTUAL_THREADS);
    assertThat(result.platformThreadsInJvm()).isLessThan(VIRTUAL_THREADS);
  }
```

**`startsThousandsOfVirtualThreadsWithoutAPlatformThreadEach`** starts 2,000 virtual threads. All 2,000 start (`threads()` is 2,000), and the JVM has **fewer than 2,000** platform threads (`isLessThan(VIRTUAL_THREADS)`): the 2,000 virtual threads share a handful of carriers. This is the module's claim in one assertion. In my real run of the `threadCost` task, 100,000 virtual threads ran on 20 platform threads. The check is safe: a JVM never has 2,000 platform threads in this test, so the comparison does not depend on the machine.

```java
  @Test
  void rejectsZeroThreads() {
    assertThatThrownBy(() -> ThreadCost.measure(ExecutorMode.VIRTUAL, 0, TestNodes.LONG_WAIT))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

**`rejectsZeroThreads`**: `ThreadCost.measure(ExecutorMode.VIRTUAL, 0, TestNodes.LONG_WAIT)` must throw `IllegalArgumentException`. Measuring the cost of no threads would be a division by zero in the "heap per thread" figure.

## Try it yourself

1. Run the tests of the four packages:

```
./gradlew cleanTest test --tests '*config*' --tests '*eventloop*' --tests '*sizing*' --tests '*threads*'
```

   Expected: 21 lines ending in `PASSED` (3 + 6 + 7 + 2 + 3) and `BUILD SUCCESSFUL`.

2. Run the Little's Law calculator, which the `printsTheWalkthroughSizing` test checks:

```
./gradlew -q sizing --args='config/sizing.properties' | head -3
```

   Expected: the lines `HOLDS`, `  In flight (Little's Law): 500 holds/s x 0.040 s = 20.0 holds` and `  Connection pool, ceiling 80 %: ceiling of 20.0 / 0.80 = 25 connections; the database allows 32: fits`.

3. Run the event-loop lab and watch the stall:

```
./gradlew -q eventLoop --args='config/event-loop.properties'
```

   Expected: the line `10 clients send one hold each at the same moment; each hold blocks for 40 ms.` and two lines of waits. In `blocking_in_loop` mode the waits grow in steps of about 40 ms (my run: 43, 85, 130, 175, 222, 267, 313, 357, 401, 447 ms). In `offloaded` mode they are all about the same (my run: 52 ms for all ten). The numbers differ on every machine and every run.

4. Run the pinning demo and see the two delays:

```
./gradlew -q pinning
```

   Expected: a line such as `12 carriers; 12 virtual threads each block for 500 ms, then an unrelated virtual task is started.` (the number is your processor count) and two lines. In my run the first said `the unrelated task waited    1.1 ms to start` and the second `the unrelated task waited  505.2 ms to start`.

5. Run the thread-cost measurement:

```
./gradlew -q threadCost --args='config/thread-cost.properties'
```

   Expected: a table with one row per run. In my run `platform` with 4,000 threads started in 205.3 ms with 4,006 platform threads in the JVM, and `virtual` with 100,000 threads started in 104.0 ms with 20 platform threads. The columns are the kind, the number of threads, the start time, the heap growth, the heap per thread and the platform threads. The numbers differ on every machine.

6. Run one test by name:

```
./gradlew cleanTest test --tests '*EventLoopServerTest.aBlockingCallInsideTheLoopStallsEveryOtherConnection'
```

   Expected: one `PASSED` line. The test waits at least 200 ms, because it proves that nothing happens during that time.

## Mistakes people make

- **Testing a server with the real database call.** A real call has no fixed duration. The gate makes "slow" a state that the test controls.
- **Checking a negative with one look.** "No reply yet" proves nothing: the reply may only be late. The test requires silence for a whole quiet period.
- **No limit on a socket read.** A test that reads from a socket without a timeout can hang for ever. Every client here sets `setSoTimeout`.
- **Using a fixed port.** Two test runs would collide. The server binds port 0, and the operating system picks a free one.
- **Asserting a measured time.** The thread tests assert only comparisons that hold by a wide margin (under 500 ms against over 250 ms, fewer than 2,000 threads), never "starts in 3 ms".
- **Reusing the pinning class in two tests.** A class initializer runs once per JVM, so a second test would find the class initialized and would see no pinning.
- **Comparing decimals for equality.** `0.1 + 0.2` is not exactly `0.3`. `LittlesLawTest` uses `isCloseTo(..., within(TOLERANCE))`.

## Check yourself

1. *Why does a blocking call inside an event loop stall every connection?* The loop has one thread. While it is stuck in the call, it cannot read from or write to any other connection.
2. *How does `aBlockingCallInsideTheLoopStallsEveryOtherConnection` prove that no reply came?* `await().during(QUIET_PERIOD)` requires that `hasReply()` stays false for 200 ms.
3. *What does the `Gate` replace?* A slow database call with a call that stays stuck until the test opens a latch, so no test depends on how long anything takes.
4. *Why does `reportsEveryProblemInOneRun` check the whole list of messages?* So that a person who edits a bad settings file sees every mistake in one run, each with the key and the value found.
5. *Why are the two bounds in `CarrierPinningTest` safe on any machine?* The two demos differ by a whole blocking period (about 1 ms against about 505 ms), and each test bound (under 500 ms, at least 250 ms) is far from both results.

## Further reading

- `java.util.Properties`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html>
- `java.nio.channels.Selector` (the tool of an event loop): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/Selector.html>
- Virtual threads and pinning (JEP 444): <https://openjdk.org/jeps/444>
- Virtual threads, synchronized pinning removed (JEP 491): <https://openjdk.org/jeps/491>
- `java.lang.Thread.Builder`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.Builder.html>
- `java.net.Socket`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Socket.html>
