# seatwave-concurrency

SeatWave's hold operation on platform threads and on virtual threads, a connection pool with a bounded wait and saturation counters, and an open-model benchmark that writes latency-against-load charts as CSV and SVG.

## Which module this belongs to

This is the hands-on project of Module 4, "Inside the box: threads, I/O and queues", of the course System Design Fundamentals. The running case is SeatWave, a ticket-booking service with four operations: list events, view seats, hold a seat and confirm a booking.

Module 4 asks what limits the throughput of one node, how to size its pools with Little's Law, how platform and virtual threads compare on I/O-heavy work, and why latency jumps as a pool nears full use. This repository holds:

- A SeatWave node whose thread model is one setting: 64 platform threads with a bounded queue (SeatWave version 0's model), or one virtual thread per hold with a cap. Every queue and every pool wait has a limit, and over it the hold is refused with 503.
- The benchmark (`benchmark` task): the same open-model load against each mode, a table, a thread census, a text chart, a CSV and two SVG charts.
- The Little's Law sizing program (`sizing`), the thread-cost measurement (`threadCost`), a carrier-pinning demo (`pinning`), and Lab 3's event-loop server (`eventLoop`).

## Prerequisites

- JDK 25. The build was verified on Eclipse Temurin 25.0.2 on macOS on 10 November 2026. The current JDK 25 update on that date was 25.0.5 (build 25.0.5+8), which was not installed on the verification machine.
- Nothing else. The Gradle wrapper (9.9.1) downloads Gradle and the libraries below on the first build.

## Dependencies and build plugins

Every version is exact. Each was checked on Maven Central on 10 November 2026 and against the OSV database on the same day, with no known vulnerability at the pinned version. The versions live in `gradle/libs.versions.toml`, and `gradle.lockfile` pins the transitive ones. There is no new runtime library: result4j and H2 were pinned in earlier modules, and the connection pool is written by hand because the pool is what the module teaches.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | 10 Nov 2026 | Apache-2.0 | The JDK has no success-or-typed-error type, so a bad settings file would have to be an exception. |
| `com.h2database:h2` | 2.5.252 | 10 Nov 2026 | MPL 2.0 or EPL 1.0 | The JDK has no database; the conditional `UPDATE` that keeps one owner per seat runs in H2, in memory only. |
| `org.junit:junit-bom` and `org.junit.jupiter:junit-jupiter` | 6.1.3 | 10 Nov 2026 | EPL-2.0 | The test engine. |
| `org.assertj:assertj-core` | 3.27.7 | 10 Nov 2026 | Apache-2.0 | Fluent assertions with clear failure messages. |
| `org.awaitility:awaitility` | 4.3.0 | 10 Nov 2026 | Apache-2.0 | Bounded waits for a state in tests, instead of `Thread.sleep`. It brings Hamcrest 2.1 (BSD-3-Clause) into the test classpath; OSV lists nothing for it. |
| `com.diffplug.spotless` (Gradle plugin) | 8.11.0 | 10 Nov 2026 | Apache-2.0 | Runs the formatter in the build. |
| `com.google.googlejavaformat:google-java-format` (used by Spotless) | 2.0.0 | 10 Nov 2026 | Apache-2.0 | One enforced code style. |
| `org.jacoco:org.jacoco.agent` and the JaCoCo Gradle plugin | 0.8.16 | 10 Nov 2026 | EPL-2.0 | Measures line coverage and enforces the floor. |

`org.junit.platform:junit-platform-launcher` is also on the test runtime path; its version comes from the JUnit BOM.

## Build and test

```
./gradlew clean check
```

This checks formatting with Spotless, compiles with `-Xlint:all -Werror`, runs the tests and fails if line coverage drops below 0.80. Run `./gradlew spotlessApply` first to fix formatting. The tests never use `Thread.sleep` to synchronize: they use latches, Awaitility with bounds and a fake clock. A few tests of the benchmark and the demos use the real clock, with wide bounds. The measuring runs are separate tasks, so `check` stays fast.

Result on 10 November 2026 (Temurin 25.0.2, macOS): `BUILD SUCCESSFUL` in 6 seconds, 60 tests, 0 failures, line coverage 83.5 percent (1,190 of 1,425 lines). The untested lines are mostly the `main` methods of the command-line classes, which print and call `System.exit`.

## The measuring machine

Every number below came from one machine: an Apple M2 Max Mac with 12 logical processors and 32 GiB of memory, on macOS 27.2, with Eclipse Temurin 25.0.2 and other programs running. The course's reference machine is 4 vCPU and 16 GB; the holds here use about 1 percent of the CPU, so the thread and pool results should carry over, but **your numbers will differ**. Each benchmark is one run per mode, after a 3-second warm-up, with 10-second steps. Everything listens and connects on the loopback address only; nothing is sent to any other host.

## Size a node with Little's Law

```
./gradlew -q sizing --args='config/sizing.properties'
```

```
HOLDS
  In flight (Little's Law): 500 holds/s x 0.040 s = 20.0 holds
  Connection pool, ceiling 80 %: ceiling of 20.0 / 0.80 = 25 connections; the database allows 32: fits
  Capacity of 25 connections: 25 / 0.040 s = 625 holds/s; at 500 holds/s the pool is 80 % busy
  Platform worker pool, same rule: 25 threads, each blocked for the 40 ms of every hold
  Virtual threads: one per hold, 20.0 alive on average; the pool, not the threads, sets the limit
SEAT MAPS
  CPU busy (Little's Law on cores): 2,000 seat maps/s x 0.005 s = 10.0 vCPUs busy; the node has 4
  Utilization: 10.0 / 4 = 250 %: one node cannot serve this; more threads add no CPU
  One node's CPU knee: 4 / 0.005 s = 800 seat maps/s; safe at 70 %: 560 seat maps/s
  Nodes for seat maps: ceiling of 2,000 / 560 = 4 nodes
WAITING FACTOR u / (1 - u), one server
   50 %: 1.0  80 %: 4.0  90 %: 9.0  95 %: 19.0  99 %: 99.0
```

## Run the benchmark

```
./gradlew -q benchmark --args='config/simple.properties build/bench'
./gradlew -q benchmark --args='config/wide-pool.properties build/bench'
./gradlew -q benchmark --args='config/production.properties build/bench'
```

Each run takes two to three minutes (129, 170 and 128 seconds on the measuring machine) and writes `build/bench/<name>.csv`, `<name>-p99.svg` and `<name>-errors.svg`. The SVG files are drawn by the program itself and open in any browser. SIMPLE, SeatWave version 0's 64 threads and 16 connections:

```
Machine: Mac OS X 27.2 on aarch64, 12 logical processors, Java 25.0.2
Node: 64 platform workers, queue bound 120, virtual in-flight bound 184, 16 connections, borrow limit 500 ms, queue wait limit 300 ms, database wait 40 ms
Load: open model, Poisson arrivals, seed 20261110, warm-up 3000 ms, steps of 10000 ms at [200, 320, 360, 380, 396, 440] holds/s, client timeout 2000 ms

mode      offered achieved  p50 ms   p99 ms  errors%  503q  503p  late  threads  platform  waiters  waitp99  busy%  cpu%
platform      200    200.0    44.3     49.5     0.00     0     0     0       64        71        4      3.1   53.4   1.4
platform      320    320.0    45.7     76.3     0.00     0     0     0       64        71       14     32.4   85.6   1.4
platform      360    360.0    61.4    121.5     0.00     0     0     0       64        71       32     77.2   96.3   0.8
platform      380    379.7   167.5    453.4     0.08     0     3     0       64        71       51    379.4   99.3   0.6
platform      396    385.3   317.0    597.9     2.70    92    15     0       64        71       50    384.0   99.6   0.7
platform      440    386.9   456.6    709.8    12.07   519    12     0       64        71       52    384.4   99.3   0.8
virtual       200    200.0    41.4     46.2     0.00     0     0     0       19        20        3      0.8   50.8   0.9
virtual       320    320.0    41.5     63.0     0.00     0     0     0       26        21       10     21.4   80.7   1.0
virtual       360    360.0    45.9     93.8     0.00     0     0     0       41        21       24     52.2   91.0   0.9
virtual       380    380.0    61.9    148.0     0.00     0     0     0       62        21       46    106.6   96.4   0.8
virtual       396    395.7   119.8    370.0     0.08     0     3     0      143        21      127    326.6   99.0   1.0
virtual       440    400.2   447.3    488.2     9.05   390     8     0      184        21      168    446.7   99.6   0.7

Thread census at 440 holds/s, platform mode (platform threads only):
     47  seatwave-worker-   TIMED_WAITING  in ConnectionPool.borrow
     16  seatwave-worker-   TIMED_WAITING  in HoldEndpoint.waitForRemoteDatabase
      1  seatwave-worker-   RUNNABLE       in ConnectionPool.borrow

Thread census at 440 holds/s, virtual mode (platform threads only):
     11  ForkJoinPool-      WAITING        in Unsafe.park
      2  ForkJoinPool-      TIMED_WAITING  in Unsafe.park

p99 against offered load, platform threads
   200 holds/s | ###                                                | p99     49.5 ms, errors   0.00%
   320 holds/s | #####                                              | p99     76.3 ms, errors   0.00%
   360 holds/s | #########                                          | p99    121.5 ms, errors   0.00%
   380 holds/s | ################################                   | p99    453.4 ms, errors   0.08%
   396 holds/s | ##########################################         | p99    597.9 ms, errors   2.70%
   440 holds/s | ################################################## | p99    709.8 ms, errors  12.07%
p99 against offered load, virtual threads
   200 holds/s | ###                                                | p99     46.2 ms, errors   0.00%
   320 holds/s | ####                                               | p99     63.0 ms, errors   0.00%
   360 holds/s | #######                                            | p99     93.8 ms, errors   0.00%
   380 holds/s | ##########                                         | p99    148.0 ms, errors   0.00%
   396 holds/s | ##########################                         | p99    370.0 ms, errors   0.08%
   440 holds/s | ##################################                 | p99    488.2 ms, errors   9.05%

platform: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 360 holds/s
virtual: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 380 holds/s

Wrote build/bench/simple.csv, build/bench/simple-p99.svg, build/bench/simple-errors.svg
```

The wide pool (64 threads, 128 connections) and PRODUCTION (25 connections for 500 holds per second) end with these lines, wide pool first:

```
platform: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 1200 holds/s
virtual: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 3200 holds/s
platform: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 594 holds/s
virtual: last step inside the target (p99 at most 300 ms, errors at most 0.05%): 594 holds/s
```

`config/unbounded.properties` opens every limit wide, to show what an unbounded queue does under overload (Module 4, Section 9).

**The columns.** `offered`: the rate sent. `achieved`: good replies (201 and 409) per second. `p50 ms`, `p99 ms`: from each hold's planned send time, over good replies. `errors%`: holds without a good reply in time. `503q`: refusals at the door or for the queue wait. `503p`: borrow timeouts. `late`: replies after the 2-second client timeout. `threads`: the pool size (platform) or virtual threads in flight (virtual). `platform`: every platform thread in the JVM. `waiters`: most borrowers waiting for a connection at one reading. `waitp99`: p99 of the wait for a connection. `busy%`: the pool's average utilization. `cpu%`: process CPU over all 12 processors.

## What waiting threads cost, and carrier pinning

```
./gradlew -q threadCost --args='config/thread-cost.properties'
./gradlew -q pinning
```

```
Java 25.0.2, 12 logical processors, heap limit 8192 MiB
kind      threads  start ms  heap growth KiB  heap per thread bytes  platform threads
platform     1000      29.9              298                    305              1006
platform     4000     198.1             1069                    273              4006
virtual      1000      59.3             1722                   1763                20
virtual      4000      11.1             8366                   2141                20
virtual     10000      14.8            13718                   1404                20
virtual    100000      89.0            93693                    959                20
12 carriers; 12 virtual threads each block for 500 ms, then an unrelated virtual task is started.
blocked in Thread.sleep (unmounts):    the unrelated task waited    0.8 ms to start
blocked in a class initializer (pins): the unrelated task waited  504.4 ms to start
```

The JVM's default stack per platform thread on this machine: `java -XX:+PrintFlagsFinal -version | grep ' ThreadStackSize '` printed `intx ThreadStackSize = 2048` (KiB).

## Lab 3: the event loop

```
./gradlew -q eventLoop --args='config/event-loop.properties'
```

```
10 clients send one hold each at the same moment; each hold blocks for 40 ms.
blocking_in_loop  waits in ms, shortest to longest: 48, 92, 135, 181, 226, 268, 314, 357, 401, 444
offloaded         waits in ms, shortest to longest: 52, 53, 53, 53, 53, 53, 53, 53, 53, 53
```

## The settings

Every program reads one settings file in `config/`, and every key is required; there are no defaults in code. A missing or invalid key stops the program with one line per problem and exit status 2. The benchmark keys:

| Key | Meaning |
|---|---|
| `executor.modes` | `platform`, `virtual` or both, comma-separated; the same load runs once per mode |
| `platform.workers`, `platform.queue.bound` | The platform pool's size and how many holds may wait for a thread |
| `virtual.max.in.flight` | How many holds may be in flight at once on virtual threads |
| `queue.wait.limit.millis` | The longest from arrival to the start of a hold's work |
| `pool.connections`, `pool.borrow.limit.millis` | The connection pool's size and the longest wait for a connection |
| `database.wait.millis` | How long each hold keeps its connection, standing for a database on another machine |
| `database.statement.timeout.seconds`, `database.url` | The `UPDATE`'s query timeout, and an H2 in-memory URL (any other is refused) |
| `load.steps.rps`, `load.warmup.millis`, `load.step.millis`, `load.random.seed` | The offered rates, the warm-up, each step's length and the seed of the arrivals |
| `client.timeout.millis`, `sampler.interval.millis`, `census.at.rps` | The client's patience, the sampler's period and the step that takes a thread census |
| `target.p99.millis`, `target.max.error.percent` | The target a step must meet to count as healthy (Module 2: 300 ms and 0.05 percent) |

## Project structure

```
seatwave-concurrency/
  build.gradle.kts            Build: Java 25, -Werror, Spotless, JaCoCo floor, one task per program
  settings.gradle.kts         Project name and the single repository (Maven Central)
  gradle/                     Wrapper files and libs.versions.toml, the one place for versions
  gradle.lockfile             Pinned transitive versions (settings-gradle.lockfile does the same for settings)
  config/                     Every setting of every program; no defaults in code
  tech/                       Beginner notes: one file per technology, plus line-by-line walkthroughs
  src/main/java/io/plainround/seatwave/concurrency/
    config/                   SettingsReader
    node/                     The node: settings, two executors, the pool, the hold endpoint
    bench/                    Load generator, census, CSV, SVG chart, report, the benchmark command
    sizing/                   Little's Law and the sizing report
    eventloop/                Lab 3: the java.nio event-loop server and its clients
    threads/                  Thread cost and the carrier-pinning demo
  src/test/java/io/plainround/seatwave/concurrency/
    config/ node/ bench/ sizing/ eventloop/ threads/   Tests for each package
```

`ARCHITECTURE.md` explains the packages, one hold step by step, the decisions and the invariant.

## Technology notes

The `tech/` folder explains every technology this repository uses, from zero: Java, threads and executors, Gradle and its wrapper scripts, the version catalog, dependency locking, the formatter, the coverage tool, the test libraries, H2 and JDBC, the properties, CSV and SVG formats, Docker Compose, EditorConfig and Git. Seven more files walk through the main code and the tests line by line. Start at `tech/README.md`, which gives a reading order.

## What is simplified for teaching

- The database is H2 in memory, and a database on another machine is modelled by keeping the connection for `database.wait.millis`. The conditional `UPDATE` still runs, so the one-owner-per-seat rule is real.
- The hold operation is called in the same process; there is no HTTP layer. Module 5 measures the network.
- The load generator runs in the same JVM as the node, so the CPU column includes it.
- The connection pool is hand-written; production code uses HikariCP.
- Lab 3's server accepts any number of connections; a production server also caps connections and closes idle ones.
- Each benchmark result is one run per mode; a capacity plan repeats runs and reads the spread.

## Continuous integration

There is no CI workflow in this repository on purpose. `./gradlew check` is the gate: run it before every commit.

## Docker Compose (optional)

`compose.yaml` runs `./gradlew --no-daemon check` inside `eclipse-temurin:25.0.5_8-jdk-noble`, with the repository mounted at `/workspace` and a named volume for `/root/.gradle`. The image tag was checked on Docker Hub on 10 November 2026.

```
docker compose up
```

[not run] Docker was not running on the verification machine, so this path was not tested. It would also use JDK 25.0.5, which is newer than the 25.0.2 used for every result above. Do not run the benchmark inside the container to describe your machine: a container adds its own scheduler limits.

## License

MIT. Copyright 2026 Sumit Srivastava. See `LICENSE`.
