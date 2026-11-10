# Technology notes for seatwave-concurrency

These notes teach every technology this repository uses, from zero, through this repository's own files. They assume you have never seen a terminal or Java. Each file follows the same nine headings: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of this repository's use, things to try, common mistakes, questions to check yourself, and official reading.

The repository is the hands-on project of Module 4, "Inside the box: threads, I/O and queues", of a system design course. It builds one node of a ticket-booking service called SeatWave, with a bounded executor and a small connection pool, in two thread models (a fixed pool of platform threads, and one virtual thread per hold). It holds a benchmark that compares the two models under an open-model load and writes a CSV file and SVG charts, a pool-sizing calculator based on Little's Law, an event-loop lab and a thread-cost demo. It is written in Java 25 and built with Gradle.

## The files

**The language, its library and its data formats**

| File | Role |
|---|---|
| `java.md` | Java 25 from zero: class, package, method, exception, records, sealed interfaces, enums, `switch` with patterns, `Optional`, streams, immutable collections, `Duration` and `Clock`, `String.format` with `Locale.ROOT`, try-with-resources, Javadoc, and the minimum Java version of each feature. |
| `java-concurrency.md` | Threads from zero: platform and virtual threads, carriers and pinning, executors, bounded queues and rejection, `Semaphore`, `CountDownLatch`, futures, atomic counters, `volatile`, interruption, timeouts on every blocking call, and why executors are closed with try-with-resources. |
| `result4j.md` | The result4j 1.2.0 library: a `Result` that is either a value or an error, and why expected failures are values here and exceptions are kept for bugs. |
| `h2-and-jdbc.md` | The embedded H2 database and JDBC: every SQL statement of the repository, the conditional `UPDATE` that makes a hold atomic, and why connections are pooled. |
| `java-properties.md` | The `.properties` format and all seven files under `config/`, key by key. |
| `csv-and-svg-output.md` | The CSV and SVG files the benchmark writes, with real lines from a run, and how to open them. |

**The build tool and its files**

| File | Role |
|---|---|
| `gradle.md` | Gradle: the build tool, `build.gradle.kts`, `settings.gradle.kts` and the tasks `benchmark`, `sizing`, `eventLoop`, `threadCost` and `pinning`. |
| `gradle-wrapper.md` | The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) that downloads the right Gradle. |
| `posix-shell.md` | The `gradlew` script, which is a POSIX shell script. |
| `windows-batch.md` | The `gradlew.bat` script, a Windows batch file. |
| `toml-version-catalog.md` | `gradle/libs.versions.toml`, the one place for dependency versions. |
| `dependency-locking.md` | `gradle.lockfile` and `settings-gradle.lockfile`, which pin exact versions. |
| `spotless-google-java-format.md` | The formatter that the build enforces. |
| `jacoco.md` | The line-coverage tool and its 80 percent floor. |

**The code walkthroughs**

| File | Role |
|---|---|
| `java-node-walkthrough.md` | Every file of the `node` package, line by line: the path of one hold through the executor queue, a worker, the pool wait and the connection pool, and where each limit refuses with 503. |
| `java-bench-walkthrough.md` | Every file of the `bench` package, line by line: the open-model load, warm-up, percentiles, the thread census, the CSV and the SVG chart the program draws by itself. |
| `java-eventloop-walkthrough.md` | Every file of the `eventloop` package, line by line: the selector, non-blocking sockets, buffers, and why blocking in the loop stalls every client. |
| `java-sizing-threads-config-walkthrough.md` | The `sizing`, `threads` and `config` packages, line by line: Little's Law as code, the utilization curve, what a thread costs, carrier pinning, and how settings are read with no defaults. |

**The tests**

| File | Role |
|---|---|
| `junit.md` | JUnit: how the test engine finds and runs the tests. |
| `assertj.md` | AssertJ: the fluent assertions used in the tests. |
| `awaitility.md` | Awaitility: bounded waits for a state in a test, instead of `Thread.sleep`. |
| `tests-node-walkthrough.md` | The tests of the `node` package, line by line. |
| `tests-bench-walkthrough.md` | The tests of the `bench` package, line by line. |
| `tests-other-packages-walkthrough.md` | The tests of `config`, `sizing`, `eventloop` and `threads`, line by line. |

**Everything else**

| File | Role |
|---|---|
| `docker-compose.md` | `compose.yaml`, the optional way to run the build in a container. |
| `editorconfig.md` | `.editorconfig`, the editor settings. |
| `git.md` | Git: the version control that tracks the files. |

There is no `instancio.md`: no test in this repository uses Instancio.

## Reading order

1. **The language first.** `java.md`, then `java-concurrency.md`. Do not skip them: every walkthrough relies on them. Then `result4j.md` and `h2-and-jdbc.md`.
2. **The data formats.** `java-properties.md` and `csv-and-svg-output.md`: the settings the programs read and the files the benchmark writes.
3. **The build tool.** `gradle.md`, `gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`, `toml-version-catalog.md`, `dependency-locking.md`, `spotless-google-java-format.md`, `jacoco.md`.
4. **The code walkthroughs.** `java-node-walkthrough.md` first, because every other package drives or reuses the node. Then `java-bench-walkthrough.md`, `java-sizing-threads-config-walkthrough.md` and `java-eventloop-walkthrough.md`.
5. **The tests.** `junit.md`, `assertj.md`, `awaitility.md`, then `tests-node-walkthrough.md`, `tests-bench-walkthrough.md` and `tests-other-packages-walkthrough.md`.
6. **The rest.** `docker-compose.md`, `editorconfig.md`, `git.md`.

## If you have ten minutes

1. `java-concurrency.md`: read "How it works underneath" for threads, carriers, bounded queues, semaphores and the interrupt flag, which everything else uses.
2. `java-node-walkthrough.md`: read "How it works underneath" for the path of one hold and the four places where a hold is refused with 503, then the `PlatformWorkers`, `VirtualWorkers` and `ConnectionPool` sections.
3. `java-bench-walkthrough.md`: read "How it works underneath" for the open model, coordinated omission, warm-up and the check that no hold outlives its limits.

## How to run what the notes describe

Every command in these notes was run in this repository, on Java 25.0.2 and Gradle 9.9.1 through `./gradlew`, on 10 November 2026, on a Mac with an ARM processor (`aarch64`) and 12 logical processors. Benchmark and timing numbers differ on every run and on every machine: compare the shape, not the digits. Start with the quickest ones:

```text
./gradlew -q sizing --args='config/sizing.properties'
./gradlew -q pinning
./gradlew -q eventLoop --args='config/event-loop.properties'
./gradlew -q threadCost --args='config/thread-cost.properties'
```

The first prints the Little's Law arithmetic (`500 holds/s x 0.040 s = 20.0 holds` on its first line). The second shows a virtual thread that frees its carrier (0.8 ms) against one that pins it (501.6 ms). The third shows ten clients waiting in a staircase when the blocking call runs in the event loop (47 to 437 ms) and all together when it is moved off it (48 ms each). The fourth shows what 100,000 waiting virtual threads cost compared with 4,000 platform threads. Then run the shortest benchmark, which takes about 30 seconds and writes its files to `build/bench`:

```text
./gradlew -q benchmark --args='config/unbounded.properties build/bench'
```

The three full benchmarks of the repository `README.md` (`simple`, `wide-pool` and `production`) take two to three minutes each.

## How these notes were checked

Each walkthrough quotes the source line by line, copied by a script and not retyped. The command `python3 tools/techcov.py <report.json> seatwave-concurrency`, run from the folder that holds all the repositories, counts the non-blank, non-comment lines of every source, script and configuration file and checks that each appears in these notes. Every line under `src/main/java/` and under `config/` is quoted.
