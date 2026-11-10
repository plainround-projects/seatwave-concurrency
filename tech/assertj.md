# AssertJ 3.27.7

## What it is

An **assertion** is a check inside a test: "this value must be that value". AssertJ is a library of assertions with a **fluent** style: you write `assertThat(actual)` and then chain method calls that read like a sentence, each returning the object so the next call can follow. When a check fails, AssertJ prints the expected and the actual value in a clear message.

## Why this repository uses it

Tests are the requirements here, so they should read like requirements. `assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(...))` is easier to read than a hand-written loop with an `if` and a `throw`. The README gives the reason: "Fluent assertions with clear failure messages." In a concurrency test the message matters even more, because a failure often shows a count (how many holds won, how many threads waited) and the count is the clue.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `gradle/libs.versions.toml` | `assertj = "3.27.7"` and `assertj-core`. |
| `build.gradle.kts` | `testImplementation(libs.assertj.core)`. |
| `gradle.lockfile` | Locks `assertj-core` and its dependency `byte-buddy` 1.18.3 (`dependency-locking.md`). |
| `src/test/java/**` | `assertThat` is imported in 13 files, `assertThatThrownBy` in 6 files and `within` in 1 file. There are 135 `assertThat(` calls and 21 `assertThatThrownBy(` calls in all. |

## How it works underneath

1. `assertThat(x)` looks at the type of `x` and returns the matching **assertion object**: one for a `String`, one for a `List`, one for a `Duration`, one for a `boolean`, one for a `Map`, one for a `Path`, one for any other object. Each has the methods that make sense for that type.
2. Each method (`isEqualTo`, `contains`, `hasSize`) checks the value. If the check holds, it returns the same assertion object, so another call can follow. If not, it throws an `AssertionError` with a message, and JUnit marks the test as failed (`junit.md`).
3. For exceptions, `assertThatThrownBy(() -> code)` runs the code in a try block, catches whatever is thrown and lets you check its type and message. The `() -> code` part is a **lambda**: a small unnamed function that is handed over and run later, inside AssertJ, so AssertJ can catch what it throws.
4. Because the checks are chained on one `assertThat`, several conditions on the same value come in one statement.
5. An assertion runs once, on the thread that calls it, at that moment. It does not wait. That is why a test that checks a value another thread is still changing needs Awaitility first (`awaitility.md`).

## Walkthrough of this repository's use

The numbers in this section come from `grep -rhoE` over `src/test` and count each method call.

### `isEqualTo` for text, numbers, records and enums

```java
    assertThat(request.seatLabel()).isEqualTo("T-1000");
```

`isEqualTo` compares with `equals`. For text it compares every character. Java **records** (such as `PoolSnapshot` and `LatencySummary`) get a correct `equals` automatically, so two records with the same contents are equal. That is how a whole record is compared in one line:

```java
      assertThat(pool.snapshot()).isEqualTo(new PoolSnapshot(1, 1, 0, 1));
```

An **enum** value such as `HoldOutcome.CREATED` is compared the same way. `isEqualTo` is the most used method here (53 calls). For numbers the compiler decides the type: `isEqualTo(1)` compares `int` values and `isEqualTo(1L)` compares `long` values, which is why `Map.of(HoldOutcome.CREATED, 1L, ...)` writes the `L`.

### `isCloseTo` and `within`: decimals with a tolerance

```java
    assertThat(LittlesLaw.capacityPerSecond(25, FORTY_MILLIS)).isCloseTo(625, within(TOLERANCE));
```

A `double` carries rounding error, so `0.1 + 0.2` is not exactly `0.3`. `isCloseTo(625, within(TOLERANCE))` accepts any value whose distance from 625 is at most `TOLERANCE`, here `1e-9` (one billionth). `within` is a static method imported from AssertJ. `LittlesLawTest` uses it 5 times because Little's Law divides and multiplies decimal numbers.

### Lists: `hasSize`, `containsExactly`, `contains`, `isEmpty`, `isNotEmpty`

```java
    assertThat(((Result.Error<SizingInputs, List<String>>) read).error())
        .containsExactly("node.vcpus is missing");
```

`containsExactly` checks that the list holds exactly these elements, in this order, and no others (8 calls). `contains` checks only that the given elements are present; with several arguments it checks all of them (9 calls, many of them on text). `hasSize(n)` checks the length (9 calls). `isEmpty()` and `isNotEmpty()` check that a list, an `Optional` or a text is empty or not.

For a map, `containsExactlyInAnyOrderEntriesOf` compares the whole map and ignores the order of the entries:

```java
      assertThat(outcomes)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(HoldOutcome.CREATED, 1L, HoldOutcome.SEAT_TAKEN, RACING_FANS - 1L));
```

### `extracting`: check one field of every element

```java
    assertThat(all)
        .extracting(StepResult::mode)
```

`extracting(StepResult::mode)` first turns each element into one of its fields, then checks the resulting list. `StepResult::mode` is a **method reference**, a short way to write "call `mode()` on each element".

### Text: `contains`, `startsWith`

```java
    assertThat(BenchmarkRun.header(settings)).contains("Node: 4 platform workers", "seed 20261110");
```

On text these check a part: `contains("a", "b")` requires every listed piece to be somewhere in the text, and `startsWith("...")` checks the beginning (4 calls). `asString()` turns an object into a text assertion so these methods apply.

### Optionals

```java
    assertThat(first).isPresent();
    assertThat(second).isEmpty();
```

An `Optional` is a box that holds a value or nothing. `isPresent()` checks that the box holds a value (4 calls), and `isEmpty()` checks that it holds nothing. `assertThat(BenchmarkReport.lastHealthyRate(platform, TARGET, BUDGET_PERCENT)).contains(200)` checks that the box holds exactly 200.

### Conditions on every element: `allSatisfy`, `anySatisfy`, `filteredOn`, `isSorted`

```java
    assertThat(written).hasSize(FILES).allSatisfy(path -> assertThat(path).isRegularFile());
```

`allSatisfy` runs a check on every element (5 calls). `anySatisfy` requires at least one element to pass the check (1 call, in `OpenModelLoadTest`). `filteredOn(...)` keeps only the elements that match a condition before the next check. `isSorted()` checks that a list is in ascending order; `EventLoopServerTest` uses it on the measured waits (2 calls). `singleElement()` checks the list has exactly one element and continues the chain on that element. `isRegularFile()` is a check for a `Path`: the path exists and is an ordinary file.

### Numbers, booleans and null

`isTrue()` and `isFalse()` check a `boolean` (8 and 5 calls). `isZero()` checks that a number is zero (10 calls), and `isPositive()` checks that it is above zero (2 calls). `isGreaterThanOrEqualTo(x)` and `isLessThan(x)` compare two values of one type, including `Duration` values (3 and 2 calls). `isNotNull()` and `isNull()` check that a value exists or does not.

### Exceptions: `assertThatThrownBy`

```java
    assertThatThrownBy(() -> new ConnectionPool(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
```

`assertThatThrownBy(() -> ...)` runs the lambda and expects it to throw. `.isInstanceOf(IllegalArgumentException.class)` checks the type of the exception (21 calls). This repository checks the type but not the message, because the value types all refuse bad input in the same way: the constructor throws `IllegalArgumentException`.

### Results that are errors: a cast

```java
    assertThat(((Result.Error<NodeSettings, List<String>>) read).error())
        .containsExactly(
            "platform.workers must be a whole number, but was zero", "pool.connections is missing");
```

`read` is a `Result` from the result4j library (`result4j.md`). It is either a success or an error. The test casts it to the error case, `Result.Error<...>`, and reads its `error()` list. A cast is the Java way to say "I know this object is really of this more specific type". If `read` were a success, the cast would throw `ClassCastException` and the test would fail, which is the right outcome.

## Try it yourself

1. See which assertion methods are imported and how often:

```
grep -rhn "import static org.assertj" src/test | sed 's/.*Assertions\.//' | sort | uniq -c
```

   Expected: `13 assertThat;`, `6 assertThatThrownBy;` and `1 within;`.

2. Count the uses of `isEqualTo`:

```
grep -rho "\.isEqualTo(" src/test | wc -l
```

   Expected: `53`.

3. Look at AssertJ in the dependency tree:

```
./gradlew -q dependencies --configuration testRuntimeClasspath | grep -A1 '^+--- org.assertj:assertj-core:3.27.7'
```

   Expected: `+--- org.assertj:assertj-core:3.27.7` followed by `|    \--- net.bytebuddy:byte-buddy:1.18.3`.

4. Run the tests that use `isCloseTo` and `within`:

```
./gradlew cleanTest test --tests '*LittlesLawTest'
```

   Expected: seven lines ending in `PASSED`.

## Mistakes people make

- **Comparing decimals with `isEqualTo`.** A `double` result such as `20.000000000000004` is not equal to `20`. Use `isCloseTo(20, within(TOLERANCE))`, as `LittlesLawTest` does.
- **Forgetting the final assertion.** `assertThat(x)` alone checks nothing. Only the chained call does.
- **Checking only the exception type when the message matters.** Here the type is the whole contract; if a message were part of the contract, add `hasMessage(...)`.
- **Putting code outside the lambda in `assertThatThrownBy`.** The exception would escape before AssertJ can catch it.
- **Asserting on a value that another thread is still changing.** The assertion runs once, at once. Wait for the condition first with Awaitility, or join the thread, or count down a latch (`awaitility.md`).
- **Asserting on text that includes line breaks without care.** The report tests use `contains` rather than the whole text where only a part matters.

## Check yourself

1. *What does `assertThat(a).isEqualTo(b)` do when `a` and `b` differ?* Throws an `AssertionError` that shows both values; JUnit marks the test failed.
2. *Why use `isCloseTo(625, within(TOLERANCE))` for a decimal result?* A `double` carries rounding error, so an exact `isEqualTo` could fail for a correct result.
3. *What does `assertThatThrownBy(() -> f()).isInstanceOf(X.class)` prove?* That `f()` throws an exception of type `X`.
4. *What does `containsExactly` check?* The elements and their order, and that there are no others.
5. *Why can an assertion on a value that another thread changes fail even when the code is right?* The assertion runs once and does not wait, so it can run before the other thread has finished.

## Further reading

- AssertJ core documentation: <https://assertj.github.io/doc/>
- AssertJ 3.27.7 Javadoc: <https://javadoc.io/doc/org.assertj/assertj-core/3.27.7/index.html>
