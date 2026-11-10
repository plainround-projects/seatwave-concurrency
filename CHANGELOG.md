# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-10

### Added

- A SeatWave node whose thread model is chosen by settings: a fixed pool of platform threads with a
  bounded queue, or one virtual thread per hold with a bound on holds in flight.
- A connection pool with a bounded borrow wait and saturation counters: busy connections, waiting
  borrowers and borrow timeouts.
- The invariant that no hold waits without bound: the executor bound, the queue wait limit and the
  borrow limit each answer 503 instead of waiting.
- An open-model benchmark with fixed-count Poisson arrivals, a warm-up, p50 and p99 per step, a
  thread census, and a CSV file with two SVG charts written by the program itself.
- Settings files for SeatWave version 0 (`simple`), a wide pool (`wide-pool`) and the Module 4
  walkthrough (`production`), plus the Little's Law sizing program.
- Lab 3: an event-loop server on `java.nio` with the blocking call inside the loop, and with it moved
  to virtual threads.
- Measurements of what waiting threads cost, and a demo of a virtual thread pinning its carrier on
  JDK 25.
- Tests with JUnit Jupiter, AssertJ and Awaitility; deterministic, with latches and a fake clock.
- Build with a pinned Gradle wrapper, dependency lock files, `-Xlint:all -Werror`, Spotless with
  google-java-format, a JaCoCo line-coverage floor of 0.80 and an XML coverage report.
- Optional Docker Compose file that runs the build in a JDK 25 container.
