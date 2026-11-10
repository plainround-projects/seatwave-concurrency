# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers one question: when the tests ran, which lines of the program were executed at least once? A line that no test ever ran is a line whose behavior nobody has checked. **JaCoCo** (Java Code Coverage) is the tool that measures it for Java. It does not tell you whether the tests are good, only which code they touched.

JaCoCo works with a **Java agent**: a small jar that the Java Virtual Machine (JVM) loads before the program starts. The agent watches classes as they are loaded and records which parts run.

## Why this repository uses it

The build has a measured floor: line coverage must be at least 0.80, and the build fails below it. That keeps the tests honest as the code grows. The README reports "line coverage 83.5 percent (1,190 of 1,425 lines)".

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`jacoco`), pins its version, wires the report after the tests, sets the 0.80 rule and adds it to `check`. It also switches on the XML report. |
| `gradle/libs.versions.toml` | `jacoco = "0.8.16"`. |
| `gradle.lockfile` | Locks `jacocoAgent` and `jacocoAnt` (`dependency-locking.md`). |
| `build/jacoco/test.exec` | The raw recording of one test run (a binary file). |
| `build/reports/jacoco/test/html/index.html` | The readable report. |
| `build/reports/jacoco/test/jacocoTestReport.xml` | The same numbers as XML, written because of `xml.required = true`. |

## How it works underneath

1. `./gradlew test` starts a new JVM for the tests and passes it the option `-javaagent:` with the JaCoCo agent jar (the `jacocoAgent` configuration). Gradle does this by itself once the `jacoco` plugin is on.
2. As each class loads, the agent inserts small recording instructions into its bytecode, in memory only. The files on disk do not change.
3. When the JVM ends, the agent writes everything it recorded to `build/jacoco/test.exec`.
4. The task `jacocoTestReport` (`finalizedBy` on `test`, so it always runs afterwards) reads `test.exec` plus the compiled classes in `build/classes/java/main` and writes the HTML report and, here, the XML report. It uses the `jacocoAnt` configuration.
5. The task `jacocoTestCoverageVerification` reads the same data and applies the rules. If a rule is violated, the task fails and so does `check`.

JaCoCo counts several things. The four that matter here:
- **Instructions**: single bytecode instructions. The most fine-grained number.
- **Branches**: each outcome of an `if`, a `switch` or a `&&`. A branch is covered when both outcomes were taken.
- **Lines**: a source line counts as covered if at least one of its instructions ran. This is the counter the build's rule uses.
- **Methods** and **classes**: counted as covered if any part of them ran.

Only code under `src/main/java` is measured. The test classes themselves are not.

## Walkthrough of this repository's use

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`toolVersion = libs.versions.jacoco.get()` fixes JaCoCo at 0.8.16. Without it, Gradle would use its own default.

```kotlin
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}
```

`finalizedBy(tasks.jacocoTestReport)` is what writes the report after every test run.

```kotlin
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

The task `jacocoTestReport` writes the HTML report by default. `xml.required = true` adds an XML file, `build/reports/jacoco/test/jacocoTestReport.xml`. The comment above the block in the build file says why: the XML gives the exact covered and missed line counts that the README quotes. Near its end the XML holds one element per counter: `<counter type="LINE" missed="235" covered="1190"/>` (the last such element is the total of the whole project; the ones before it belong to single packages, classes and methods).

```kotlin
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

`counter = "LINE"` picks the line counter. `minimum = "0.80".toBigDecimal()` is the lowest accepted ratio of covered lines to all lines. The comment above it names the untested remainder: the command-line entry points. `tasks.check { dependsOn(...) }` makes the rule part of `check`. When I raised the minimum to 0.95 in a scratch copy, the build failed with `Rule violated for bundle seatwave-concurrency: lines covered ratio is 0.83, but expected minimum is 0.95`.

### The real numbers (Temurin 25.0.2, 60 tests)

I ran `./gradlew clean check` in a scratch copy and read `build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report. The last row of the HTML table, "Total", reads:

| Counter | Missed | Total | Covered |
| --- | --- | --- | --- |
| Instructions | 1,020 | 7,172 | 85 percent (6,152 covered) |
| Branches | 108 | 397 | 72 percent (289 covered) |
| Lines | 235 | 1,425 | 1,190 lines, 83.5 percent |
| Methods | 17 | 196 | 179 |
| Classes | 5 | 52 | 47 |
| Complexity | 111 | 397 | 286 |

The rule needs 0.80 of 1,425 lines, which is 1,140, so at least 1,140 covered lines. The build has 1,190. **The margin is 50 lines.**

Per package, the missed lines are:

| Package | Missed lines | Total lines | Why |
| --- | --- | --- | --- |
| `sizing` | 20 | 128 | 15 are `SizingCli` (the `main` method, which no test calls). The other 5 are failure branches: 4 in `SizingInputs` and 1 in `SizingReport`. |
| `threads` | 69 | 126 | 40 are `ThreadCostCli` and 20 are `PinningCli` (the `main` methods). The other 9 are 6 in `ThreadCost` (the failure branches for a start or join that takes too long), 2 in the nested class `CarrierPinning$SlowToInitialize` and 1 in `CarrierPinning`. |
| `config` | 2 | 80 | Two failure branches of `SettingsReader`. |
| `bench` | 55 | 568 | 33 are `BenchmarkCli`. The other 22 are 12 in `OpenModelLoad` (error and timeout branches), 4 in `BenchmarkSettings`, 3 in `OpenModelLoad$Tally` and 1 each in `LoadChartSvg`, `StepResult` and `Saturation`. |
| `node` | 24 | 292 | No `main` method here. The 24 are failure branches: 10 in `SeatWaveNode`, 6 in `HoldEndpoint`, 5 in `ConnectionPool`, 2 in `NodeSettings` and 1 in `SeatStore`. |
| `eventloop` | 65 | 231 | 31 are `EventLoopCli`. The other 34 are 26 in `EventLoopServer` (error paths of the socket code), 7 in `EventLoopLab` and 1 in `EventLoopLab$Settings`. |

The five `main` classes add up to 139 of the 235 missed lines (15 + 40 + 20 + 33 + 31). `Classes` shows 5 missed of 52: exactly those five classes, `SizingCli`, `ThreadCostCli`, `PinningCli`, `BenchmarkCli` and `EventLoopCli`. The other 96 missed lines are error paths that a test would have to break something on purpose to reach. The `$` in a JVM class name joins a nested class to its outer class.

### Reading the report

Open `index.html`. Each row is a package; click it for its classes, click a class for its methods, click a method for the source with colors: green lines ran, red lines never ran, yellow lines ran only partly (a branch outcome was never taken).

## Try it yourself

1. Run the gate and the report:

```
./gradlew clean check
```

   Expected: 60 `PASSED` lines, `> Task :jacocoTestCoverageVerification`, `BUILD SUCCESSFUL in 6s` and `8 actionable tasks: 8 executed`.

2. Print the totals from the HTML report:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  1,020 of 7,172  85%  108 of 397  72%  111  397  235  1,425  17  196  5  52`.

3. Print the total line counter from the XML report:

```
grep -o '<counter type="LINE"[^>]*>' build/reports/jacoco/test/jacocoTestReport.xml | tail -1
```

   Expected: `<counter type="LINE" missed="235" covered="1190"/>`.

4. Print the totals of the `node` package:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/io.plainround.seatwave.concurrency.node/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  82 of 1,392  94%  13 of 82  84%  13  95  24  292  1  54  0  14`. The `node` package has 292 lines, 24 of them missed, and no missed class.

5. See the recording and the report folders:

```
ls build/jacoco build/reports/jacoco/test build/reports/jacoco/test/html
```

   Expected: `test.exec`; `html` and `jacocoTestReport.xml`; and `index.html`, `jacoco-sessions.html`, `jacoco-resources` and one folder per package (`io.plainround.seatwave.concurrency.bench`, `.config`, `.eventloop`, `.node`, `.sizing` and `.threads`).

6. Open the report in your browser (on a Mac): `open build/reports/jacoco/test/html/index.html`.

## Mistakes people make

- **Treating 80 percent as a goal.** Coverage shows which lines never ran. A line that ran can still be unchecked. The tests here also assert results.
- **Chasing 100 percent for `main` methods.** The build documents them as the untested remainder instead (here `SizingCli`, `ThreadCostCli`, `PinningCli`, `BenchmarkCli` and `EventLoopCli`).
- **Running only `./gradlew test` and expecting the rule to be checked.** The rule runs in `jacocoTestCoverageVerification`, which `check` runs.
- **Reading an old report.** The report is rewritten after each `test` run; run the tests first.
- **Forgetting the margin.** There are 50 lines of headroom now, but every untested line uses some of it, so new code still needs tests in the same change.
- **Deleting a test to make a number look right.** The check then fails for the right reason.

## Check yourself

1. *What does the line counter measure?* The share of source lines in which at least one instruction ran during the tests.
2. *Which task checks the rule and which writes the reports?* `jacocoTestCoverageVerification` checks; `jacocoTestReport` writes the HTML and the XML.
3. *Why is coverage 83.5 percent and not 100?* The `main` methods of the five command-line classes are never called by a test (139 missed lines), and about 96 lines of error handling (for example a database connection that fails to open) are not provoked.
4. *What makes the rule part of `check`?* `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }`.
5. *How many lines of headroom are there?* 50: 1,190 covered against 1,140 required.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/>
- JaCoCo counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- Gradle JaCoCo plugin (9.9.1): <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
