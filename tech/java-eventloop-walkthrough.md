# Java walkthrough: the `eventloop` package

## What it is

This file reads, one source file at a time, the Java package `io.plainround.seatwave.concurrency.eventloop`. It is Lab 3 of Module 4: a small server written with one **event-loop** thread, and the clients that test it. An **event loop** is a design where one thread watches many network connections and reacts only when one of them is ready, instead of giving each connection its own thread. The package has five files. `LoopMode` and `BlockingWork` are tiny. `EventLoopServer` is the server, `EventLoopLab` is the clients, and `EventLoopCli` is the program you start.

A **connection** here is a **TCP connection**: a reliable stream of bytes between two programs. A **socket** is one end of it. A **port** is a number that tells the operating system which program a connection is for. The server listens on the **loopback address** (`127.0.0.1`), which means "this same machine", so no byte ever leaves the computer.

Read `java.md` and `java-concurrency.md` first.

## Why this repository uses it

Module 4 teaches that an event loop serves thousands of connections with one thread, and also that it has one rule: **never block the loop thread**. If the work of one request waits (for a database, a disk, a lock), no other connection is served meanwhile, because the one thread that would serve them is the one that waits. The lab reproduces that mistake, and then fixes it by moving the blocking part to a virtual thread. Ten clients send one hold each at the same moment. In the broken mode, the last waits for ten holds in a row. In the fixed mode, all wait about one hold.

## Where it appears

| File | What it does |
|---|---|
| `LoopMode.java` | The two modes: blocking in the loop, and offloaded. |
| `BlockingWork.java` | The blocking part of a request, as a function. |
| `EventLoopServer.java` | The server: one selector thread, non-blocking sockets, the line protocol. |
| `EventLoopLab.java` | The clients: connect, wait for a shared start, send, time the reply. |
| `EventLoopCli.java` | Reads the settings, runs both modes, prints each client's wait. |

## How it works underneath

**Blocking I/O: one thread per connection.** The classic way to serve many connections is to give each its own thread. The thread calls `read`, and the call waits (**blocks**) until bytes arrive. This is simple, and it is what the node of this repository does with its worker threads. Its cost is the thread: a platform thread has its own stack, and thousands of them are a lot of memory and scheduling.

**Non-blocking I/O and the selector.** Java's `java.nio` package ("new input and output") offers another way. A **channel** is a connection that can be put in **non-blocking** mode: `read` returns at once with whatever is there, maybe nothing. A **selector** is an object that lets one thread ask "which of these channels is ready?". The thread registers each channel with the selector and says which events it cares about: a new connection to accept (`OP_ACCEPT`), bytes to read (`OP_READ`), room to write (`OP_WRITE`). Then it calls `select()`, which waits until at least one channel is ready, and returns the set of ready **keys**. A **key** (`SelectionKey`) is the link between a channel and the selector, and it can carry an **attachment**, an object of your own (here, the connection's state). The thread handles each ready key and calls `select()` again. That repeating loop is the event loop.

**Buffers.** A `ByteBuffer` is a block of bytes with a **position** (where the next read or write happens) and a **limit** (the end of the usable part). A channel `read` puts bytes in at the position and moves the position. To read what was put in, you call `flip()`, which sets the limit to the position and the position to 0. `clear()` resets the buffer for reuse. The code below uses all three.

**The protocol.** The client sends `HOLD A-1` and a newline. The server answers one line: `201 A-1`. A line longer than 64 bytes closes the connection, so a client cannot make the server grow a buffer without limit. A TCP stream has no message boundaries: the bytes of one line can arrive in two pieces, and two lines in one piece. So the server collects bytes per connection until it sees a newline.

**Who may touch what.** The selector, the channels and each connection's buffers belong to the loop thread alone. This is called **thread confinement**: if only one thread ever touches a piece of data, it needs no lock. The only data that crosses threads is the queue of finished replies, which is thread-safe.

**The two modes.**

- `BLOCKING_IN_LOOP` runs the blocking work on the loop thread itself. While it sleeps 40 ms, the loop serves nobody.
- `OFFLOADED` hands the work to `VirtualWorkers` (from the `node` package), which runs it on a virtual thread, bounded by a semaphore. The virtual thread puts its finished reply on a queue and calls `selector.wakeup()`, which makes a sleeping `select()` return. The loop then writes the reply. The loop thread never waits.

**A real run.** `./gradlew -q eventLoop --args='config/event-loop.properties'` printed:

```text
10 clients send one hold each at the same moment; each hold blocks for 40 ms.
blocking_in_loop  waits in ms, shortest to longest: 47, 90, 131, 176, 221, 263, 306, 351, 391, 437
offloaded         waits in ms, shortest to longest: 48, 48, 48, 48, 48, 48, 48, 48, 48, 48
```

In the broken mode, each wait is about 44 ms more than the one before: the holds are served one after another. In the fixed mode, all ten take 48 ms, which is one hold. The numbers differ on every run; the staircase and the flat line do not.

## Walkthrough of this repository's use

### `LoopMode.java`

Where the event-loop server runs the blocking part of each request. An enum with two values.

**Lines 1 to 1: package and imports**

```java
package io.plainround.seatwave.concurrency.eventloop;
```


**Lines 2 to 9: the two modes**

```java
/** Where the event-loop server runs the blocking part of each request. */
public enum LoopMode {
  /** On the loop thread itself: the mistake Lab 3 reproduces. */
  BLOCKING_IN_LOOP,
  /** On a virtual thread, with the reply handed back to the loop: the fix. */
  OFFLOADED
}
```

- `BLOCKING_IN_LOOP` is the mistake Lab 3 reproduces: the blocking part runs on the loop thread itself.
- `OFFLOADED` is the fix: the blocking part runs on a virtual thread, and the reply is handed back to the loop.
- `EventLoopCli` walks `LoopMode.values()`, which is the array of every value in the order written, so both modes run, the broken one first.

### `BlockingWork.java`

The blocking part of one request, such as a database call. A one-method interface.

**Lines 1 to 1: package and imports**

```java
package io.plainround.seatwave.concurrency.eventloop;
```


**Lines 2 to 15: the interface**

```java
/** The blocking part of one request, such as a database call. */
@FunctionalInterface
public interface BlockingWork {

  /**
   * Holds one seat and returns the reply line.
   *
   * @param seatLabel the seat named in the request
   * @return the reply, without the line end
   * @throws InterruptedException if the waiting thread is interrupted
   */
  String hold(String seatLabel) throws InterruptedException;
}
```

- `@FunctionalInterface` marks an interface with exactly one abstract method. Any lambda with the same shape can then be used as the interface. The compiler checks that there is only one method.
- `String hold(String seatLabel) throws InterruptedException` takes the seat named in the request and returns the reply line, without the line end. It may throw `InterruptedException`, the exception a blocking call throws when its thread is interrupted. See `java-concurrency.md`.
- `EventLoopLab` provides the work as a lambda that sleeps. A real server would run a database call here.

### `EventLoopLab.java`

The client side of Lab 3: many fans connect, then all send one hold at the same moment, and each fan's wait is measured.

**Lines 1 to 20: package and imports**

```java
package io.plainround.seatwave.concurrency.eventloop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
```

- `BufferedReader`: a reader that collects characters into lines.
- `IOException`: the exception for a failed read, write or socket call.
- `InputStreamReader`: turns a stream of bytes into characters with a chosen encoding.
- `OutputStream`: the sink that bytes are written to.
- `InetSocketAddress`: an address plus a port.
- `Socket`: one end of a TCP connection, in the blocking style.
- `StandardCharsets`: the standard character encodings, such as UTF-8.
- `Duration`: a length of time.
- `ArrayList`: a growable list.
- `List`: an ordered collection.
- `Objects`: helpers such as `requireNonNull`.
- `CountDownLatch`: a gate that opens when a count reaches zero.
- `ExecutionException`: the exception that wraps a failure inside a task.
- `ExecutorService`: something that runs tasks on threads and can be closed.
- `Executors`: factory methods that build executors.
- `Future`: a handle on a result that is not ready yet.
- `TimeUnit`: a unit of time for waits, such as nanoseconds.
- `TimeoutException`: thrown when a wait ran out of time.

**Lines 21 to 28: the class**

```java
/**
 * Lab 3's client side: many fans connect, then all send one hold at the same moment, and each fan's
 * wait is measured. Stateless; each call opens and closes its own server and sockets.
 */
public final class EventLoopLab {

  private EventLoopLab() {}
```

- The class comment says it is stateless: each call opens and closes its own server and sockets. A private constructor stops anyone from making an object.

**Lines 29 to 49: the settings**

```java
  /**
   * The settings of one lab run.
   *
   * @param clients how many fans connect and send at once
   * @param workTime how long the blocking part of each request takes
   * @param socketTimeout the longest any socket call may block
   * @param offloadMaxInFlight how many requests may run off the loop at once
   */
  public record Settings(
      int clients, Duration workTime, Duration socketTimeout, int offloadMaxInFlight) {

    /** Creates the settings. */
    public Settings {
      Objects.requireNonNull(workTime, "workTime");
      Objects.requireNonNull(socketTimeout, "socketTimeout");
      if (clients < 1 || offloadMaxInFlight < 1 || socketTimeout.isZero()) {
        throw new IllegalArgumentException("clients, bound and timeout must be positive");
      }
    }
  }
```

- `Settings` is a record nested in the class: `clients` (how many fans), `workTime` (how long the blocking part takes), `socketTimeout` (the longest any socket call may block) and `offloadMaxInFlight` (how many requests may run off the loop at once).
- The compact constructor refuses null durations, fewer than one client, a bound below 1, and a zero timeout.

**Lines 50 to 69: the blocking work**

```java
  /**
   * Runs one mode: every client sends at once, and the time to each reply is measured.
   *
   * @param mode where the server runs the blocking work
   * @param settings the client count and the times
   * @return each client's wait from the shared start, sorted from shortest to longest
   * @throws IOException if a socket fails or times out
   * @throws InterruptedException if interrupted while waiting
   */
  public static List<Duration> run(LoopMode mode, Settings settings)
      throws IOException, InterruptedException {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(settings, "settings");
    BlockingWork databaseCall =
        seatLabel -> {
          // The blocking part: stands for a database call that takes the work time.
          Thread.sleep(settings.workTime());
          return "201 " + seatLabel;
        };
```

- `run(mode, settings)` runs one mode and returns each client's wait, sorted from shortest to longest. It throws `IOException` if a socket fails or times out.
- `BlockingWork databaseCall = seatLabel -> {...}` is a lambda that implements `BlockingWork`. `Thread.sleep(settings.workTime())` pauses the thread for the work time: this stands for the database call. It returns `"201 " + seatLabel`, the reply line.

**Lines 70 to 73: the server and the fans**

```java
    try (EventLoopServer server =
            new EventLoopServer(
                mode, databaseCall, settings.offloadMaxInFlight(), settings.socketTimeout());
        ExecutorService fans = Executors.newVirtualThreadPerTaskExecutor()) {
```

- `try (EventLoopServer server = new EventLoopServer(...); ExecutorService fans = Executors.newVirtualThreadPerTaskExecutor())` opens two resources in one `try`. They are closed in **reverse** order at the end of the block: first `fans` (which waits for the fan threads), then `server`. Both are closed even if the body throws.
- `new EventLoopServer(mode, databaseCall, offloadMaxInFlight, socketTimeout)` starts the server and its loop thread. The last argument is how long `close` waits for the loop thread.
- `Executors.newVirtualThreadPerTaskExecutor()` gives one virtual thread to each fan. Each fan blocks on its socket, and virtual threads make that cheap.

**Lines 74 to 88: starting the fans and the shared gate**

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
    }
  }
```

- `CountDownLatch connected` starts at the number of clients, and each fan counts it down once it has connected. `startGate` starts at 1 and opens when the main thread counts it down. A **latch** is a gate that threads wait at until its count reaches zero.
- For each client, `fans.submit(() -> oneFan(...))` starts one fan and returns a `Future<Duration>`. The seat label is `"A-" + (clientIndex + 1)`, so every fan asks for a different seat.
- `connected.await(timeout)` waits until every fan has connected, or gives up with an exception. Only then does `startGate.countDown()` open the gate for all of them **at the same moment**. This is what makes the burst simultaneous: connecting takes time, and a start that was not synchronized would spread the sends out.
- `collect(waits, settings)` gathers each fan's wait.

**Lines 89 to 97: one fan: the timeout**

```java
  private static Duration oneFan(
      InetSocketAddress address,
      String seatLabel,
      Settings settings,
      CountDownLatch connected,
      CountDownLatch startGate)
      throws IOException, InterruptedException {
    int timeoutMillis = Math.toIntExact(settings.socketTimeout().toMillis());
```

- `oneFan` is the code of one client. `Math.toIntExact(settings.socketTimeout().toMillis())` converts the timeout to an `int` of milliseconds, and throws if it does not fit.

**Lines 98 to 106: one fan: connect and set up**

```java
    try (Socket socket = new Socket()) {
      socket.connect(address, timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      socket.setTcpNoDelay(true);
      OutputStream out = socket.getOutputStream();
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      connected.countDown();
```

- `try (Socket socket = new Socket())` makes an unconnected socket that is closed at the end of the block.
- `socket.connect(address, timeoutMillis)` connects, and gives up after the timeout. `socket.setSoTimeout(timeoutMillis)` makes every later `read` give up after the timeout, so no call blocks forever. `socket.setTcpNoDelay(true)` turns off **Nagle's algorithm**, which holds small writes back to join them into larger packets; here a small request must go at once.
- `out` is the stream to write bytes to. `in` is a `BufferedReader` over an `InputStreamReader` that decodes the bytes as UTF-8, so `readLine()` returns a whole line.
- `connected.countDown()` tells the main thread this fan is ready.

**Lines 107 to 109: one fan: wait for the gate**

```java
      if (!startGate.await(settings.socketTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
        throw new IOException("the start signal did not come within the socket timeout");
      }
```

- `startGate.await(timeout, NANOSECONDS)` waits for the gate to open, at most the socket timeout. If it does not open in time, the fan throws.

**Lines 110 to 119: one fan: send and time the reply**

```java
      long sentNanos = System.nanoTime();
      out.write(("HOLD " + seatLabel + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      String reply = in.readLine();
      if (reply == null || !reply.startsWith("201 ")) {
        throw new IOException("unexpected reply: " + reply);
      }
      return Duration.ofNanos(System.nanoTime() - sentNanos);
    }
  }
```

- `sentNanos = System.nanoTime()` is the moment of sending. `out.write(("HOLD " + seatLabel + "\n").getBytes(UTF_8))` writes the request line as bytes, and `out.flush()` makes sure it is sent now.
- `in.readLine()` waits for the reply line. A null means the server closed the connection, and a line that does not start with `201 ` is unexpected: either case throws `IOException`.
- The returned value is the time from sending to the reply, as a `Duration`.

**Lines 120 to 136: collecting the waits**

```java
  private static List<Duration> collect(List<Future<Duration>> waits, Settings settings)
      throws IOException, InterruptedException {
    List<Duration> measured = new ArrayList<>();
    for (Future<Duration> wait : waits) {
      try {
        measured.add(wait.get(settings.socketTimeout().toNanos(), TimeUnit.NANOSECONDS));
      } catch (ExecutionException failed) {
        throw new IOException("a client failed", failed.getCause());
      } catch (TimeoutException tooSlow) {
        throw new IOException("a client got no reply within the socket timeout", tooSlow);
      }
    }
    measured.sort(null);
    return List.copyOf(measured);
  }
}
```

- For each future, `wait.get(socketTimeout, NANOSECONDS)` waits for the fan's result for at most the socket timeout. `ExecutionException` means the fan failed, and its cause is attached to the `IOException`. `TimeoutException` means no reply came in time.
- `measured.sort(null)` sorts by the natural order of `Duration` (shortest first); `null` means "use the natural order". `List.copyOf(measured)` returns an unchangeable list.

### `EventLoopCli.java`

The command-line entry point of Lab 3. Exit status 2 means bad usage or settings; 1 means a run failed.

**Lines 1 to 9: package and imports**

```java
package io.plainround.seatwave.concurrency.eventloop;

import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
```

- `SettingsReader`: the class that reads typed values from a settings file.
- `IOException`: the exception for a failed read, write or socket call.
- `Path`: a file path.
- `Duration`: a length of time.
- `List`: an ordered collection.
- `Locale`: a language and region; `Locale.ROOT` means none, so numbers print the same everywhere.
- `Collectors`: ready-made ways to gather a stream into one result.

**Lines 10 to 25: constants**

```java
/**
 * Command-line entry point of Lab 3: the same burst of holds against the event loop with the
 * blocking call inside it, then with the call moved off it. Exit status 2 means bad usage or
 * settings; 1 means a run failed.
 */
public final class EventLoopCli {

  private static final int EXIT_RUN_FAILED = 1;
  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int MAX_CLIENTS = 1000;
  private static final int MAX_MILLIS = 60_000;
  private static final double NANOS_PER_MILLI = 1e6;

  private EventLoopCli() {}
```

- Two exit statuses, and three limits for the settings: at most 1,000 clients, and at most 60,000 ms for any time. `NANOS_PER_MILLI = 1e6` is a `double` written in scientific notation: 1 x 10 to the 6th, 1,000,000.

**Lines 26 to 36: checking the arguments**

```java
  /**
   * Runs both modes and prints each client's wait.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: eventLoop <event-loop.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```

- One argument is required: the settings file. Otherwise the usage line goes to `System.err` and the process exits with 2.

**Lines 37 to 42: reading the settings**

```java
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int clients = reader.intBetween("clients", 1, MAX_CLIENTS);
      Duration work = reader.millisBetween("work.millis", 0, MAX_MILLIS);
      Duration timeout = reader.millisBetween("socket.timeout.millis", 1, MAX_MILLIS);
      int bound = reader.intBetween("offload.max.in.flight", 1, MAX_CLIENTS);
```

- `SettingsReader.load(Path.of(arguments[0]))` loads the file. The four keys are `clients` (1 to 1,000), `work.millis` (0 to 60,000), `socket.timeout.millis` (1 to 60,000) and `offload.max.in.flight` (1 to 1,000). See `java-properties.md`.

**Lines 43 to 46: bad settings**

```java
      if (!reader.problems().isEmpty()) {
        reader.problems().forEach(problem -> System.err.println("invalid setting: " + problem));
        System.exit(EXIT_BAD_USAGE);
      }
```

- If any key had a problem, print each one as `invalid setting: ...` and exit with 2. I ran it with a file that had only `clients=3` and got three lines, one for each missing key.

**Lines 47 to 52: the heading**

```java
      EventLoopLab.Settings settings = new EventLoopLab.Settings(clients, work, timeout, bound);
      System.out.printf(
          Locale.ROOT,
          "%d clients send one hold each at the same moment; each hold blocks for %d ms.%n",
          clients,
          work.toMillis());
```

- `new EventLoopLab.Settings(...)` builds the lab's settings. `System.out.printf(Locale.ROOT, "...%d...%n", ...)` prints the first line: `10 clients send one hold each at the same moment; each hold blocks for 40 ms.`

**Lines 53 to 62: running each mode**

```java
      for (LoopMode mode : LoopMode.values()) {
        List<Duration> waits = EventLoopLab.run(mode, settings);
        System.out.printf(
            Locale.ROOT,
            "%-17s waits in ms, shortest to longest: %s%n",
            mode.name().toLowerCase(Locale.ROOT),
            waits.stream()
                .map(wait -> String.format(Locale.ROOT, "%.0f", wait.toNanos() / NANOS_PER_MILLI))
                .collect(Collectors.joining(", ")));
      }
```

- For each `LoopMode`, `EventLoopLab.run(mode, settings)` returns the sorted waits. `waits.stream().map(wait -> String.format(Locale.ROOT, "%.0f", wait.toNanos() / NANOS_PER_MILLI)).collect(Collectors.joining(", "))` turns the list of durations into one text such as `47, 90, 131`: each wait in milliseconds with no decimals, joined by commas.
- `"%-17s ..."` pads the mode name to 17 characters so the two lines line up. `mode.name().toLowerCase(Locale.ROOT)` prints `blocking_in_loop` and `offloaded`.

**Lines 63 to 72: failures**

```java
    } catch (IOException failed) {
      System.err.println("the lab failed: " + failed);
      System.exit(EXIT_RUN_FAILED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the lab was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }
}
```

- `IOException` prints `the lab failed: ...` and exits with 1. `InterruptedException` restores the interrupt flag, prints a message and exits with 1.

### `EventLoopServer.java`

The server: a small line-based server on one event-loop thread, written with `java.nio`.

**Lines 1 to 21: package and imports**

```java
package io.plainround.seatwave.concurrency.eventloop;

import io.plainround.seatwave.concurrency.node.VirtualWorkers;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
```

- `VirtualWorkers`: the virtual-thread executor with a semaphore bound.
- `IOException`: the exception for a failed read, write or socket call.
- `InterruptedIOException`: the input-output exception that says a blocking call was interrupted.
- `UncheckedIOException`: wraps an `IOException` so it can be thrown where checked exceptions are not allowed.
- `InetAddress`: an internet address, here the loopback one.
- `InetSocketAddress`: an address plus a port.
- `ByteBuffer`: a block of bytes with a read position and a limit.
- `SelectionKey`: the link between one channel and a selector, with the events of interest.
- `Selector`: the object that lets one thread watch many channels.
- `ServerSocketChannel`: a channel that accepts incoming connections.
- `SocketChannel`: a channel for one connection that can be read without blocking.
- `StandardCharsets`: the standard character encodings, such as UTF-8.
- `Duration`: a length of time.
- `ArrayDeque`: a double-ended queue backed by an array.
- `Iterator`: walks a collection one element at a time.
- `Objects`: helpers such as `requireNonNull`.
- `Queue`: the interface of a first-in, first-out collection.
- `ArrayBlockingQueue`: a bounded queue that threads can wait on.
- `BlockingQueue`: the interface of a queue with waiting operations.

**Lines 22 to 44: the class comment and constants**

```java
/**
 * A small line-based server on one event-loop thread, written with {@code java.nio}: one thread
 * watches every connection with a selector and reacts only when a socket is ready.
 *
 * <p>Protocol: the client sends {@code HOLD <seat>} and a newline; the server answers one line. A
 * request line longer than {@link #MAX_LINE_BYTES} closes the connection, so a slow or hostile
 * client cannot grow a buffer without bound. The server listens on the loopback address only.
 *
 * <p>Thread-safety: the selector, the channels and each connection's buffers are confined to the
 * loop thread. The only cross-thread traffic is the queue of finished replies, a thread-safe
 * bounded queue, followed by {@link Selector#wakeup()}; and, when that queue is full, closing the
 * channel, which is thread-safe.
 */
public final class EventLoopServer implements AutoCloseable {

  /** The longest request line accepted, in bytes. */
  public static final int MAX_LINE_BYTES = 64;

  private static final String REQUEST_PREFIX = "HOLD ";
  private static final String BUSY_REPLY = "503 busy";
  private static final String BAD_REQUEST_REPLY = "400 bad request";
  private static final int READ_BUFFER_BYTES = 256;
```

- The class comment states the protocol and the thread-safety rule: the selector, the channels and each connection's buffers are confined to the loop thread. The only cross-thread traffic is the queue of finished replies, followed by `Selector.wakeup()`; and, when that queue is full, closing the channel, which is thread-safe.
- `MAX_LINE_BYTES = 64` is public so a test can send a line that is too long. The three reply texts and `READ_BUFFER_BYTES = 256` (the size of one read) are constants.

**Lines 45 to 54: the fields**

```java
  private final LoopMode mode;
  private final BlockingWork work;
  private final Duration closeLimit;
  private final Selector selector;
  private final ServerSocketChannel serverChannel;
  private final VirtualWorkers offloadWorkers;
  private final BlockingQueue<FinishedReply> finishedReplies;
  private final Thread loopThread;
  private volatile boolean isRunning = true;
```

- `mode` and `work`: how and what. `closeLimit`: how long `close` waits for the loop thread. `selector` and `serverChannel`: the nio objects. `offloadWorkers`: a `VirtualWorkers` from the `node` package, used for the offloaded mode. `finishedReplies`: the bounded queue of replies that virtual threads hand to the loop. `loopThread`: the loop thread.
- `private volatile boolean isRunning = true`: `volatile` means a write by one thread is seen by the next read in any other thread. The loop thread reads this flag on every round, and `close`, on another thread, sets it to false. Without `volatile`, the loop might never see the change. See `java-concurrency.md`.

**Lines 55 to 73: the constructor: the parts**

```java
  /**
   * Opens the server on a free loopback port and starts its loop thread.
   *
   * @param mode where the blocking work runs
   * @param work the blocking part of each request
   * @param offloadMaxInFlight how many requests may run off the loop at once
   * @param closeLimit the longest {@link #close} waits for the loop thread
   * @throws IOException if the socket cannot be opened
   */
  public EventLoopServer(
      LoopMode mode, BlockingWork work, int offloadMaxInFlight, Duration closeLimit)
      throws IOException {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.work = Objects.requireNonNull(work, "work");
    this.closeLimit = Objects.requireNonNull(closeLimit, "closeLimit");
    this.offloadWorkers = new VirtualWorkers(offloadMaxInFlight);
    // Bounded like every queue: the loop drains it on every wake-up, so it holds at most a burst.
    this.finishedReplies = new ArrayBlockingQueue<>(offloadMaxInFlight);
```

- `new VirtualWorkers(offloadMaxInFlight)` is the bounded executor. `new ArrayBlockingQueue<>(offloadMaxInFlight)` is the queue of finished replies, bounded like every queue. The comment: the loop drains it on every wake-up, so it holds at most a burst.

**Lines 74 to 81: the constructor: the sockets and the loop**

```java
    this.selector = Selector.open();
    this.serverChannel = ServerSocketChannel.open();
    // Port 0 asks the operating system for any free port; loopback keeps every byte on the machine.
    serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    serverChannel.configureBlocking(false);
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    this.loopThread = Thread.ofPlatform().name("seatwave-event-loop").start(this::runLoop);
  }
```

- `Selector.open()` creates the selector. `ServerSocketChannel.open()` creates the listening channel.
- `serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))` binds it to the loopback address and **port 0**, which asks the operating system for any free port. The comment says loopback keeps every byte on the machine. Using a free port means two runs never collide.
- `serverChannel.configureBlocking(false)` puts the channel in non-blocking mode. `serverChannel.register(selector, SelectionKey.OP_ACCEPT)` registers it, to be told when a connection is waiting.
- `Thread.ofPlatform().name("seatwave-event-loop").start(this::runLoop)` starts a named platform thread that runs `runLoop`. The loop is a platform thread on purpose: it is the one thread that must always be ready.

**Lines 82 to 91: the address**

```java
  /**
   * Returns the address clients connect to.
   *
   * @return the loopback address and the port the operating system chose
   * @throws IOException if the channel is closed
   */
  public InetSocketAddress address() throws IOException {
    return (InetSocketAddress) serverChannel.getLocalAddress();
  }
```

- `serverChannel.getLocalAddress()` returns the address and port the operating system chose. The cast `(InetSocketAddress)` says what kind of address it is. Clients connect to it.

**Lines 92 to 125: closing**

```java
  /**
   * Stops the loop, waits up to the close limit for it, then closes every channel.
   *
   * @throws IOException if a channel fails to close, or {@link InterruptedIOException} if
   *     interrupted while waiting for the loop thread
   */
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
    } finally {
      // Free the workers and the channels even when the loop thread did not stop in time.
      releaseResources();
    }
  }

  private void releaseResources() throws IOException {
    offloadWorkers.close();
    for (SelectionKey key : selector.keys()) {
      key.channel().close();
    }
    selector.close();
  }
```

- `isRunning = false` tells the loop to stop. `selector.wakeup()` makes a sleeping `select()` return at once, so the loop sees the flag.
- `loopThread.join(closeLimit)` waits up to the close limit for the loop thread to end and returns `false` if it did not (`Thread.join(Duration)` is Java 19). If it did not stop, `IllegalStateException` is thrown.
- `catch (InterruptedException interrupted)` restores the interrupt flag, then throws `InterruptedIOException`. The comment says: keep the interrupt visible to the caller, and report it the way I/O code does. `initCause` attaches the original exception.
- The `finally` block runs whether the wait ended well, timed out or was interrupted. It calls `releaseResources()`, so the workers and the channels are freed on every path. Without it, a loop thread that did not stop in time would leave them open.
- `releaseResources()` does three things in order: `offloadWorkers.close()` waits for the running virtual threads, every selector key's channel is closed (`key.channel().close()`), and the selector is closed. On the normal path the loop has already stopped, so nothing else touches the channels while they close.

**Lines 126 to 143: the loop**

```java
  private void runLoop() {
    try {
      while (isRunning) {
        selector.select();
        sendFinishedReplies();
        Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
        while (ready.hasNext()) {
          SelectionKey key = ready.next();
          ready.remove();
          handle(key);
        }
      }
    } catch (IOException loopFailed) {
      // The loop thread has no caller to throw to; failing loudly ends the thread with the cause.
      throw new UncheckedIOException("the event loop failed", loopFailed);
    }
  }
```

- `while (isRunning)`: the loop repeats until `close` clears the flag.
- `selector.select()` blocks until a channel is ready or `wakeup()` is called. This is the only place the loop waits, and it waits for **any** event on **any** connection.
- `sendFinishedReplies()` drains the queue of replies that virtual threads have finished. It is called after every wake-up, whatever the reason.
- `selector.selectedKeys()` is the set of keys that are ready. The loop walks it with an iterator, calls `ready.remove()` on each, and calls `handle(key)`. The selector adds keys to the selected set but never removes them, so without `remove()` the same key would be handled again on the next round.
- `catch (IOException loopFailed)`: the loop thread has no caller to throw to, so the comment says failing loudly ends the thread with the cause. It throws `UncheckedIOException`, which wraps the checked `IOException`, so it can leave `runLoop`, which cannot declare `throws`.

**Lines 144 to 156: handling one key**

```java
  private void handle(SelectionKey key) throws IOException {
    if (!key.isValid()) {
      return;
    }
    if (key.isAcceptable()) {
      accept();
    } else if (key.isReadable()) {
      read(key);
    } else if (key.isWritable()) {
      flush(key);
    }
  }
```

- A key can be cancelled while the loop still holds it, so `if (!key.isValid()) return;` skips it.
- `key.isAcceptable()`: a client is connecting, so `accept()`. `key.isReadable()`: bytes arrived, so `read(key)`. `key.isWritable()`: room to write, so `flush(key)` continues a write that stopped half way.

**Lines 157 to 165: accepting a connection**

```java
  private void accept() throws IOException {
    SocketChannel client = serverChannel.accept();
    if (client == null) {
      return;
    }
    client.configureBlocking(false);
    client.register(selector, SelectionKey.OP_READ, new Connection());
  }
```

- `serverChannel.accept()` returns the new `SocketChannel`, or `null` if the client has already gone. `client.configureBlocking(false)` puts it in non-blocking mode and `client.register(selector, SelectionKey.OP_READ, new Connection())` registers it for reading, with a new `Connection` as the attachment. That attachment is where this connection's buffers live.

**Lines 166 to 176: reading bytes**

```java
  private void read(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    Connection connection = (Connection) key.attachment();
    ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    int bytesRead = channel.read(buffer);
    if (bytesRead < 0) {
      key.cancel();
      channel.close();
      return;
    }
```

- The channel and the connection come from the key. `ByteBuffer.allocate(READ_BUFFER_BYTES)` makes a 256-byte buffer. `channel.read(buffer)` reads what is there and returns the number of bytes. A negative result means the client closed its end: the code cancels the key and closes the channel.

**Lines 177 to 190: finding the lines**

```java
    buffer.flip();
    while (buffer.hasRemaining()) {
      byte next = buffer.get();
      if (next == '\n') {
        String line = connection.takeLine();
        dispatch(key, line);
      } else if (!connection.append(next)) {
        // Over the limit: the bound on every read, so a client cannot hold memory without end.
        key.cancel();
        channel.close();
        return;
      }
    }
  }
```

- `buffer.flip()` switches the buffer from "being filled" to "being read". The loop takes one byte at a time with `buffer.get()`.
- A newline (`'\n'`) ends a line: `connection.takeLine()` returns the bytes collected so far as text and `dispatch(key, line)` handles the request.
- Any other byte is added with `connection.append(next)`. It returns `false` if the line buffer is full, which is the **line limit**: the code cancels the key, closes the channel and returns. The comment says this is the bound on every read, so a client cannot hold memory without end.

**Lines 191 to 197: dispatching: the request check**

```java
  private void dispatch(SelectionKey key, String line) {
    if (!line.startsWith(REQUEST_PREFIX)) {
      queueReply(key, BAD_REQUEST_REPLY);
      return;
    }
    String seatLabel = line.substring(REQUEST_PREFIX.length()).strip();
```

- A line that does not start with `HOLD ` gets the `400 bad request` reply. Otherwise the seat label is everything after the prefix, stripped of spaces.

**Lines 198 to 218: dispatching: the two modes**

```java
    switch (mode) {
      case BLOCKING_IN_LOOP -> queueReply(key, holdOnThisThread(seatLabel));
      case OFFLOADED -> {
        boolean isAccepted =
            offloadWorkers.tryExecute(() -> {
              FinishedReply finished = new FinishedReply(key, holdOnThisThread(seatLabel));
              if (finishedReplies.offer(finished)) {
                // The loop may be asleep in select(); wake it so the reply goes out now.
                selector.wakeup();
              } else {
                // The loop is a full queue behind: close, so the client fails fast and
                // retries instead of waiting for a reply that has nowhere to go.
                closeChannel(key);
              }
            });
        if (!isAccepted) {
          queueReply(key, BUSY_REPLY);
        }
      }
    }
  }
```

- `switch (mode)` has two arrow cases. In `BLOCKING_IN_LOOP`, `queueReply(key, holdOnThisThread(seatLabel))` runs the work **here, on the loop thread**, and the loop is stuck for the whole work time. That is the mistake.
- In `OFFLOADED`, `offloadWorkers.tryExecute(() -> {...})` runs the work on a virtual thread, or returns `false` if the bound is reached. The lambda does the blocking work through `holdOnThisThread`, makes a `FinishedReply` (the key and the reply text), and offers it to the queue. `finishedReplies.offer(finished)` returns `false` at once if the queue is full, with no waiting.
- If the offer worked, `selector.wakeup()` wakes the loop, which may be asleep in `select()`, so the reply goes out now. If the queue was full, the comment explains: the loop is a full queue behind, so close the connection, and the client fails fast and retries instead of waiting for a reply that has nowhere to go.
- If `tryExecute` returned `false`, the loop answers `503 busy` itself. Every request gets an answer or a closed connection.

**Lines 219 to 228: running the work**

```java
  private String holdOnThisThread(String seatLabel) {
    try {
      return work.hold(seatLabel);
    } catch (InterruptedException interrupted) {
      // Only close interrupts the work: keep the flag and answer as a refusal.
      Thread.currentThread().interrupt();
      return BUSY_REPLY;
    }
  }
```

- `work.hold(seatLabel)` runs the blocking part. If the thread is interrupted, the code restores the flag and returns the busy reply. The comment says only `close` interrupts the work.

**Lines 229 to 238: sending the finished replies**

```java
  private void sendFinishedReplies() {
    for (FinishedReply finished = finishedReplies.poll();
        finished != null;
        finished = finishedReplies.poll()) {
      if (finished.key().isValid()) {
        queueReply(finished.key(), finished.reply());
      }
    }
  }
```

- A `for` loop that starts with `finishedReplies.poll()` (take one, or `null` if empty) and repeats until the queue is empty. For each reply whose key is still valid, `queueReply` queues it for the connection. A client that has gone has an invalid key, and its reply is dropped.

**Lines 239 to 250: queueing a reply**

```java
  private void queueReply(SelectionKey key, String reply) {
    Connection connection = (Connection) key.attachment();
    connection.pendingReplies.add(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.UTF_8)));
    try {
      flush(key);
    } catch (IOException clientGone) {
      // The client closed its end; nothing is left to answer, so the connection is dropped.
      key.cancel();
      closeChannel(key);
    }
  }
```

- The connection is the key's attachment. `connection.pendingReplies.add(ByteBuffer.wrap((reply + "\n").getBytes(UTF_8)))` appends the reply, with a newline, to the connection's list of replies waiting to be written. `ByteBuffer.wrap` makes a buffer around the byte array.
- `flush(key)` then tries to write. If the client closed its end, `IOException` is caught, the key is cancelled and the channel closed. The comment: nothing is left to answer.

**Lines 251 to 266: writing replies**

```java
  private static void flush(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    Connection connection = (Connection) key.attachment();
    while (!connection.pendingReplies.isEmpty()) {
      ByteBuffer next = connection.pendingReplies.peek();
      channel.write(next);
      if (next.hasRemaining()) {
        // The socket's send buffer is full: ask to be told when it drains, and stop for now.
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        return;
      }
      connection.pendingReplies.poll();
    }
    key.interestOps(SelectionKey.OP_READ);
  }
```

- `flush` writes as much as the socket accepts. For each pending buffer, `channel.write(next)` writes bytes. If `next.hasRemaining()` is still true, the socket's send buffer is full: the code asks to be told when it drains, with `key.interestOps(OP_READ | OP_WRITE)`, and stops for now. The `|` combines two events. When the buffer is written completely, it is removed with `poll()`.
- When nothing is pending, `key.interestOps(SelectionKey.OP_READ)` turns off the write interest. If it stayed on, `select()` would return every time, because a socket with room is always writable.

**Lines 267 to 274: closing a channel**

```java
  private static void closeChannel(SelectionKey key) {
    try {
      key.channel().close();
    } catch (IOException alreadyBroken) {
      throw new UncheckedIOException("could not close a broken connection", alreadyBroken);
    }
  }
```

- `key.channel().close()` closes the connection. Closing is thread-safe, which is why a virtual thread may call it. `IOException` is wrapped in `UncheckedIOException`, since the method is called from a lambda.

**Lines 275 to 276: a finished reply**

```java
  private record FinishedReply(SelectionKey key, String reply) {}
```

- A one-line record that pairs a key with the reply text. Private to the class.

**Lines 277 to 298: one connection's bytes**

```java
  /** The bytes of one connection. Confined to the loop thread. */
  private static final class Connection {
    private final ByteBuffer lineBytes = ByteBuffer.allocate(MAX_LINE_BYTES);
    private final Queue<ByteBuffer> pendingReplies = new ArrayDeque<>();

    boolean append(byte next) {
      if (!lineBytes.hasRemaining()) {
        return false;
      }
      lineBytes.put(next);
      return true;
    }

    String takeLine() {
      lineBytes.flip();
      String line = StandardCharsets.UTF_8.decode(lineBytes).toString();
      lineBytes.clear();
      return line;
    }
  }
}
```

- `Connection` holds what belongs to one client: `lineBytes`, a 64-byte buffer that collects the request line, and `pendingReplies`, an `ArrayDeque` (a queue) of buffers waiting to be written. It is confined to the loop thread.
- `append(byte next)` returns `false` if the buffer is full, else puts the byte in and returns `true`.
- `takeLine()` calls `flip()` to switch to reading, decodes the bytes as UTF-8 text with `StandardCharsets.UTF_8.decode(lineBytes).toString()`, and `clear()` empties the buffer for the next line.

## Try it yourself

All commands run from the repository folder.

**1. Run the lab.**

```text
./gradlew -q eventLoop --args='config/event-loop.properties'
```

It takes about a second. I got the two lines shown under "A real run". Read the first list as a staircase: each client waits one more hold than the one before.

**2. Change the work time.** I copied `config/event-loop.properties` to a file of my own, set `work.millis=80`, and ran the same command with that file path. The staircase doubled its step, and the offloaded line stayed flat at one hold:

```text
10 clients send one hold each at the same moment; each hold blocks for 80 ms.
blocking_in_loop  waits in ms, shortest to longest: 88, 173, 258, 344, 426, 512, 597, 682, 767, 850
offloaded         waits in ms, shortest to longest: 93, 94, 94, 94, 94, 94, 94, 94, 94, 94
```

**3. A file with missing keys.** I ran the lab with a file that had only `clients=3`:

```text
invalid setting: work.millis is missing
invalid setting: socket.timeout.millis is missing
invalid setting: offload.max.in.flight is missing
```

The process exited with status 2 and Gradle reported a failed build.

**4. The tests that cover the server.** `./gradlew test --tests 'io.plainround.seatwave.concurrency.eventloop.*' --console=plain` ran six tests, all `PASSED`, among them `aBlockingCallInsideTheLoopStallsEveryOtherConnection`, `movingTheCallOffTheLoopKeepsOtherConnectionsAnswered` and `closesAConnectionThatSendsALineLongerThanTheLimit`.

## Mistakes people make

- **Blocking the loop.** Calling a database, a file read or `Thread.sleep` on the loop thread stalls every connection. `BLOCKING_IN_LOOP` does it on purpose; `OFFLOADED` fixes it.
- **Forgetting `ready.remove()`.** The selector never clears the selected set, so a key would be handled again and again.
- **Forgetting that a read can return half a line.** TCP has no message boundaries. The connection collects bytes until a newline.
- **An unbounded line buffer.** A client that never sends a newline would grow the buffer forever. The server closes the connection after 64 bytes.
- **Leaving `OP_WRITE` on.** A socket with room is always writable, so `select()` would spin. `flush` turns the write interest off when nothing is pending.
- **Touching the selector from another thread.** Only the loop thread handles keys. Other threads use the thread-safe queue and `wakeup()`.
- **No timeout on a socket.** The fans set `setSoTimeout`, so a missing reply fails after the timeout and does not hang the lab.

## Check yourself

1. **Why do the waits in `blocking_in_loop` go up in steps of about one hold?** One thread serves every client in turn, and each hold blocks it for the whole work time.
2. **What does `selector.wakeup()` do, and who calls it?** It makes a `select()` that is waiting return at once. A virtual thread calls it after putting a finished reply on the queue, so the loop sends the reply without waiting for the next network event.
3. **Why is the queue of finished replies bounded?** Like every queue in the repository, so nothing grows without limit. If it is full, the connection is closed and the client retries.
4. **What does `flip()` do?** It switches a buffer from being filled to being read: the limit becomes the current position and the position becomes 0.
5. **Why is `isRunning` `volatile`?** The loop thread reads it and `close` writes it from another thread. `volatile` makes the change visible to the reader.

## Further reading

Official documentation only, for JDK 25:

- `java.nio.channels.Selector`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/Selector.html
- The `java.nio` package (`ByteBuffer` and channels): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/package-summary.html
- `Thread`, `Thread.Builder` and virtual threads: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html
