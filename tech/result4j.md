# result4j 1.2.0 (a library for results that are values)

## What it is

result4j is a small Java library. Its main type is `Result<R, E>`. A **result type** is an object that stands for "either this worked and here is the value, or it failed and here is the reason". `R` is the type of the good value and `E` the type of the reason. A `Result` is exactly one of two things: `Result.Success`, which holds a good value, or `Result.Error`, which holds an error value. The caller must look at which one it got, and the Java compiler can check that the caller handled both.

The Java standard library has no such type. `Optional` says "a value or nothing" but cannot say why there is nothing. An exception says why, but it jumps out of the normal flow of the program. `Result` fills the gap.

## Why this repository uses it

A settings file is typed by a person. A person can write `load.steps.rps=200,abc`, or forget `pool.connections`. These are **expected failures**: they should come back as ordinary values that the program can list, count and print, not as exceptions that unwind it. One run must list every problem so the person fixes them all before the next run. So the readers of settings files (`NodeSettings.from`, `BenchmarkSettings.from`, `SizingInputs.from`) return a `Result<Settings, List<String>>`: the settings, or one text line per problem.

**Exceptions are kept for programmer errors**: a null argument, a record built with a value its constructor forbids, a negative duration. Those mean the code is wrong, not the user. The line is: if a person can cause it by typing, it is a `Result`; if only a bug can cause it, it is an exception.

The dependency is declared in `gradle/libs.versions.toml` as `result4j = "1.2.0"`, used in `build.gradle.kts` as `implementation(libs.result4j)` and pinned in `gradle.lockfile` (`com.github.sviperll:result4j:1.2.0`). `implementation` means the library is needed to compile and run the main code. The Maven coordinates are `com.github.sviperll:result4j`. The license is Apache-2.0. The class files in the 1.2.0 jar are Java class-file version 65, which is Java 21, so the library runs on the Java 25 used here.

## Where it appears

| File | What it does with `Result` |
|---|---|
| `node/NodeSettings.java` | `from` returns `Result<NodeSettings, List<String>>`; uses `Result.error` twice and `Result.success` once. |
| `sizing/SizingInputs.java` | `from` returns `Result<SizingInputs, List<String>>`; uses `Result.error` and `Result.success`. |
| `bench/BenchmarkSettings.java` | `from` returns `Result<BenchmarkSettings, List<String>>`; switches over the node's `Result` with record patterns and uses `Result.error` and `Result.success`. |
| `bench/BenchmarkCli.java` | Switches over the `Result` of `BenchmarkSettings.from`. |
| `sizing/SizingCli.java` | Switches over the `Result` of `SizingInputs.from`. |

No other class imports the library. `ThreadCost` has a record that is also called `Result`, but it is `ThreadCost.Result`, a different type with no connection to the library. `EventLoopCli` and `ThreadCostCli` read their settings directly from the `SettingsReader` and check `problems()`, and do not use `Result`.

## How it works underneath

`Result<R, E>` is a **sealed interface** with two permitted types, both **records**. I ran `javap` on the jar and confirmed that `Result$Success` and `Result$Error` are `final` classes that extend `java.lang.Record`, and that `Result` has a `PermittedSubclasses` entry. A sealed interface lists every type that may implement it, so the compiler knows the full list (see `java.md`).

- `Result.Success<R, E>` is a record with one component, `result`. Its `toString` prints `Success[result=7]`.
- `Result.Error<R, E>` is a record with one component, `error`. Its `toString` prints `Error[error=[q must be a whole number]]`.

Both have the same two type parameters. A `Success` never uses `E`, and an `Error` never uses `R`. That is why a pattern is written `Result.Success<NodeSettings, List<String>>(NodeSettings nodeSettings)`: both type parameters are spelled out.

Everything is in memory. A `Result` is an ordinary object on the heap. There is no thread, no network and no file. Making one costs one small object.

**The calls this repository uses:**

- `Result.success(value)` makes a `Success`.
- `Result.error(reason)` makes an `Error`.
- A `switch` over the `Result` with the record patterns `Result.Success<...>(value)` and `Result.Error<...>(error)`. The compiler knows there are only two cases, so no `default` is needed. If a library update added a third case, the switch would stop compiling.

The library offers more (`map`, `flatMap`, `andThen`, `mapError`, `isError`, `recoverError`, `orOnErrorThrow`, `throwError`, `peekError`, `peekSuccess`, `ifSuccess`, `discardError`, `fromOptional` and others). This repository uses none of them. I listed the public methods with `javap -public` to know.

I ran a small program with the 1.2.0 jar on the class path. It parses `42` and `x`, and returns a `Result<Integer, List<String>>`:

```text
got 42
problems [x must be a whole number]
Success[result=7] | Error[error=[q must be a whole number]]
true
```

The last line is `Result.success(7)` compared to a parsed 7 with `equals`: records compare by content.

## Walkthrough of this repository's use

### Building results: `NodeSettings.from`

```java
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


- The return type `Result<NodeSettings, List<String>>` says in one line what the method gives: the settings, or a list of problem texts. Both cases are values, so one call reports every bad key.
- Every key is read first. The reader collects a problem line for each bad one and returns a harmless stand-in, so the reads never stop early.
- `Result.error(reader.problems())` is returned **before** the record is built. This is what makes the stand-ins harmless: the record is never made from them.
- `Result.success(new NodeSettings(...))` returns the settings.
- The `catch (IllegalArgumentException inconsistent)` turns the constructor's exception into one more problem line, `Result.error(List.of(inconsistent.getMessage()))`. An exception from the record's constructor means the keys are valid alone but not together (here, a URL that is not `jdbc:h2:mem:`). From a settings file, that is still the person's mistake, so it is a `Result`.

### Building results: `SizingInputs.from`

```java
  public static Result<SizingInputs, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int holds = reader.intBetween("hold.rate.per.second", 1, MAX_RATE);
    Duration holdTime = reader.millisBetween("hold.time.millis", 1, MAX_MILLIS);
    int connections = reader.intBetween("database.max.connections", 1, MAX_COUNT);
    int seatMaps = reader.intBetween("seatmap.rate.per.second", 1, MAX_RATE);
    Duration seatMapCpu = reader.millisBetween("seatmap.cpu.millis", 1, MAX_MILLIS);
    int vcpus = reader.intBetween("node.vcpus", 1, MAX_COUNT);
    int poolCeiling = reader.intBetween("pool.utilization.ceiling.percent", 1, MAX_PERCENT);
    int cpuTarget = reader.intBetween("cpu.utilization.target.percent", 1, MAX_PERCENT);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new SizingInputs(
            holds, holdTime, connections, seatMaps, seatMapCpu, vcpus, poolCeiling, cpuTarget));
  }
}
```


The same shape. There is no `try` here, because every check that the constructor does is already covered by the range of the `intBetween` calls: a value in range cannot make the constructor throw.

### Examining a result: `BenchmarkSettings.from`

```java
    Result<NodeSettings, List<String>> node = NodeSettings.from(reader, firstMode);
    return switch (node) {
      case Result.Error<NodeSettings, List<String>>(List<String> problems) ->
          Result.error(problems);
      case Result.Success<NodeSettings, List<String>>(NodeSettings nodeSettings) -> {
        try {
          yield Result.success(
              new BenchmarkSettings(
                  modes,
                  rates,
                  warmup,
                  step,
                  seed,
                  clientTimeout,
                  sample,
                  census,
                  targetP99,
                  maxErrors,
                  nodeSettings));
        } catch (IllegalArgumentException inconsistent) {
          // Each key was valid alone, but together they break a rule, such as a census rate that is
          // not a step.
          yield Result.error(List.of(inconsistent.getMessage()));
        }
      }
    };
  }
```


- `Result<NodeSettings, List<String>> node = NodeSettings.from(reader, firstMode)` keeps the result in a variable.
- `return switch (node) {...}` examines it. `case Result.Error<NodeSettings, List<String>>(List<String> problems) -> Result.error(problems)` passes an error on. The type of the `Error` is `Result<BenchmarkSettings, ...>` here, so `Result.error(problems)` builds a new `Error` of the right type with the same list.
- `case Result.Success<...>(NodeSettings nodeSettings) -> {...}` builds the benchmark settings from the node settings. The `yield` gives the value of the block. See `java.md` for `yield`.

### Examining a result: the two commands

```java
      Result<BenchmarkSettings, List<String>> read =
          BenchmarkSettings.from(new SettingsReader(SettingsReader.load(settingsFile)));
      switch (read) {
        case Result.Success<BenchmarkSettings, List<String>>(BenchmarkSettings settings) ->
            run(settings, runName(settingsFile), Path.of(arguments[1]));
        case Result.Error<BenchmarkSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

```java
    try {
      switch (SizingInputs.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<SizingInputs, List<String>>(SizingInputs inputs) ->
            System.out.print(SizingReport.render(inputs));
        case Result.Error<SizingInputs, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```


- The commands are the only code that prints and exits. On `Success`, they run. On `Error`, they print every problem line as `invalid setting: ...` to the error stream and exit with status 2, the shell convention for incorrect use.
- The two `case` lines cover everything a `Result` can be. The compiler would refuse the switch if one were missing.

## Try it yourself

Run these from the repository folder.

**1. See an error value become output.** I made a copy of `config/sizing.properties` without the `node.vcpus` line and with `hold.time.millis=abc`, and ran `./gradlew -q sizing --args='<path of my copy>'`. The reader found both problems and `SizingCli` printed them, in the order the keys are read, and the process exited with status 2:

```text
invalid setting: hold.time.millis must be a whole number, but was abc
invalid setting: node.vcpus is missing
```

**2. Find the library in the lock file.** `grep result4j gradle.lockfile` printed `com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath`.

**3. Find every use.** `grep -rn "Result\." src/main` lists the calls of `Result.success`, `Result.error` and the two switch patterns: `NodeSettings`, `SizingInputs`, `BenchmarkSettings`, `BenchmarkCli` and `SizingCli`.

**4. The tests of the settings records.** `./gradlew test --tests '*NodeValuesTest' --console=plain` runs the tests that build settings and check both the success and the error paths.

## Mistakes people make

- **Using an exception for a typo in a file.** The first typo would stop the run and hide the second. A `Result` carries all the problems.
- **Ignoring the error case.** A `Result` that is never examined is a bug that compiles. The `switch` with both patterns makes the compiler check.
- **Building the value from stand-ins.** `from` returns the error before it builds the record.
- **Using `Result` for a bug.** A null argument or a negative duration is a programmer error and throws. It does not belong in a `Result`.
- **Putting a null in a `Success`.** The library does not forbid it (I ran `Result.success(null)` and got `Success[result=null]`). This repository never passes null.

## Check yourself

1. **What are the two cases of `Result`?** `Success`, holding a good value, and `Error`, holding an error value.
2. **Why do the settings readers return `List<String>` as the error type?** One run can then report every problem at once: one text per missing or invalid key.
3. **Why does `NodeSettings.from` return the error before it builds the record?** The reader returns a harmless stand-in for a bad key. Building the record from stand-ins would make a record that looks valid and is not.
4. **Why is there no `default` in the `switch` over a `Result`?** `Result` is sealed with exactly two cases, so the compiler knows the switch is complete. If a case were added, the switch would stop compiling.
5. **When is an exception right and a `Result` wrong?** When only a bug can cause the failure: a null, a negative time, an impossible record.

## Further reading

Official project page and source of the library at the pinned version 1.2.0: https://github.com/sviperll/result4j

For the Java features it relies on, official documentation for JDK 25: sealed classes (https://openjdk.org/jeps/409), records (https://openjdk.org/jeps/395) and pattern matching for `switch` (https://openjdk.org/jeps/441).
