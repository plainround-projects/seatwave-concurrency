# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and all 60 run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks, and Awaitility (`awaitility.md`) gives bounded waits for things that happen on other threads. The module teaches threads, so most of the interesting tests start threads of their own; JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform()`. |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 13 test classes with 60 `@Test` methods, and 2 helper classes that hold no tests (`TestNodes` and `StepResults`). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test`.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the method, and throws the instance away. State cannot leak from one test to the next. This is why `ReportsTest` can keep a field `private final List<StepResult> results = List.of(...)` and every test still starts from a fresh one.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 13 here), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
6. The classes and methods are **package-private** (no `public`). JUnit 5 and 6 allow that, which keeps tests out of the public surface. The helper `TestNodes` is the exception on purpose: it is `public` because tests in the `bench`, `eventloop` and `threads` packages use it too.

JUnit does not promise an order between tests, and no test here depends on one. By default JUnit also runs the tests one after the other on one thread, and Gradle uses one test JVM, so two tests never overlap in time. That matters for this repository: several tests measure how fast a thread starts, and another test running next to them would disturb the result.

## Walkthrough of this repository's use

### `@Test`: one behavior, one sentence

```java
  @Test
  void refusesAPoolWithoutConnections() {
    assertThatThrownBy(() -> new ConnectionPool(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

`@Test` marks the method as a test. The name is a sentence about behavior, so a failure reads like a broken requirement: "refuses a pool without connections". The body has three steps that most tests here follow: **arrange** (build the input), **act** (call the code), **assert** (check the outcome). Short tests such as this one fold the three steps into one statement. In the longer concurrency tests the three parts are separated by a blank line, with the act part alone, for example in `countsAWaitingBorrowerUntilAConnectionComesBack`.

Most test methods declare `throws Exception`. That is allowed: JUnit calls the method and treats any exception it throws as an error. It saves the test from catching checked exceptions such as `SQLException` or `InterruptedException` that would be noise in a test.

### `@TempDir`: a folder for one test

```java
  void runsEveryModeOnItsOwnNodeAndWritesTheCsvAndCharts(@TempDir Path folder) throws Exception {
```

```java
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
```

`@TempDir` on a method parameter asks JUnit to create a new empty temporary folder, pass it in as `folder`, and delete it, with everything inside, after the test. `BenchmarkRunTest` uses it so the benchmark writes its CSV and two charts into a folder that disappears, not into the repository. `SettingsReaderTest` uses it to write a small properties file and read it back. These two methods are the only places that use `@TempDir`: I searched the test sources and found `@Test` 60 times and `@TempDir` twice.

### What this repository does not use

There is no `@ParameterizedTest`, `@ValueSource`, `@EnumSource`, `@BeforeEach`, `@AfterEach`, `@Nested`, `@DisplayName`, `@Timeout` or `@ExtendWith`. Where several inputs need one check, a test lists the checks one after another (`rejectsHoldRequestsOutsideTheAllowLists`) or uses an ordinary `for` loop (`neverLendsOneConnectionToTwoBorrowersAtOnce` loops over its 200 borrows, and `ThreadCensusTest` loops over its threads). Each test builds what it needs with ordinary helper classes and methods (`TestNodes`, `StepResults`, and the private `properties()` and `endpoint(...)` methods; see `tests-node-walkthrough.md`, `tests-bench-walkthrough.md` and `tests-other-packages-walkthrough.md`). That keeps each test readable from top to bottom. A helper class holds no `@Test`, so JUnit ignores it.

There is also no `@Timeout`. A test that waits for another thread never relies on JUnit to cut it off. Each wait carries its own bound instead: an Awaitility `atMost`, a `CountDownLatch.await` with a time limit, or a socket timeout (`awaitility.md`). A bound inside the wait gives a clear message such as "the test never released the worker", where an outside timeout would only say that the test took too long.

### Static imports and the assertion style

All tests start with lines such as `import static org.assertj.core.api.Assertions.assertThat;`. A **static import** lets you write `assertThat(...)` instead of `Assertions.assertThat(...)`. The checks come from AssertJ, not from JUnit's own `Assertions` class (`assertj.md`). The waiting comes from `import static org.awaitility.Awaitility.await;`.

### Where tests run from

The tests that need a file make it themselves in a `@TempDir`. No test reads a path relative to the project folder. Gradle would run the tests with the project folder as the working directory anyway.

### The build wiring

`testImplementation(libs.junit.jupiter)` makes the annotations and the engine available. `testRuntimeOnly(libs.junit.platform.launcher)` adds the launcher. `useJUnitPlatform()` tells the `test` task to use the Platform. The BOM `platform(libs.junit.bom)` fixes every JUnit library at 6.1.3 (`gradle.md`).

## Try it yourself

1. Run all tests and count the results:

```
./gradlew cleanTest test | grep -c PASSED
```

   Expected: `60`.

2. Run one class:

```
./gradlew cleanTest test --tests '*LittlesLawTest'
```

   Expected: seven lines ending in `PASSED`, then `BUILD SUCCESSFUL in 1s` and `5 actionable tasks: 3 executed, 2 up-to-date`.

3. Run one method:

```
./gradlew cleanTest test --tests '*NodeValuesTest.mapsEveryOutcomeToItsStatus'
```

   Expected: one `PASSED` line, `NodeValuesTest > mapsEveryOutcomeToItsStatus() PASSED`.

4. Count the annotations:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Expected: `1 @Override` and `60 @Test`. (`@TempDir` sits inside a method signature, so this pattern does not show it; `grep -rn "@TempDir" src/test` finds the two uses.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 13 files has a `tests="..."` count after a full run: 1, 3, 8, 4, 1, 8, 6, 7, 3, 6, 4, 7 and 2 (in the order Gradle lists the files). They add up to 60.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own input, and each node test uses its own database (`TestNodes.uniqueUrl()`).
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*sizing*'` matches the package name `sizing` as well as class names, so it runs every test in that package. Use the full class name when you want one class.
- **Using `Thread.sleep` to wait for another thread.** The wait is too long on a fast machine and too short on a slow one. The tests here wait on a latch or with Awaitility (`awaitility.md`).
- **Starting a thread and never stopping it.** A thread left running after a test can break the next one. The tests close every node, pool and server in a `try` block that closes it automatically (`tests-node-walkthrough.md`).

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, passes it as the parameter, and deletes it afterwards.
4. *How do 60 `@Test` methods give 60 results?* Each method runs once; this repository has no parameterized tests.
5. *Why does a test method name read like a sentence?* So a failing test reads like a broken requirement.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>
