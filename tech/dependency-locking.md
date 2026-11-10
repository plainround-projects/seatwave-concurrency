# Dependency locking

## What it is

Your project depends on libraries. Those libraries depend on other libraries. A library you did not ask for but that comes along is a **transitive dependency**. For example this project asks for AssertJ, and AssertJ needs `net.bytebuddy:byte-buddy`; byte-buddy is a transitive dependency.

If a build resolved "the newest compatible version" every time, two builds a week apart could use different jars and give different results. **Dependency locking** stops that. Gradle writes the exact version it chose for every library into a **lockfile**, commits it with the code, and from then on insists on those exact versions.

A **version** like `3.27.7` has a major part, a minor part and a patch part. A **coordinate** is the full address of a library: `group:name:version`, for example `org.assertj:assertj-core:3.27.7`.

## Why this repository uses it

The README says "`gradle.lockfile` pins the transitive ones". The version catalog (`toml-version-catalog.md`) pins the libraries this project names; the lockfile pins everything else. Together they make the build reproducible: the same libraries on every machine, today and next year, and no surprise from a new release of a library deep in the tree. This matters for a repository whose results are numbers: a benchmark that changes because a database library changed its version would be hard to explain.

## Where it appears

| Path | What it does |
| --- | --- |
| `build.gradle.kts` | `dependencyLocking { lockAllConfigurations() }` turns it on (`gradle.md`). |
| `gradle.lockfile` | The locked versions of every configuration of the project: 4 comment lines and 25 data lines. |
| `settings-gradle.lockfile` | The same for the settings phase: 3 comment lines and one data line. |

## How it works underneath

1. A **configuration** is a named list of dependencies for one purpose (`compileClasspath`, `testRuntimeClasspath`, ...). Each has its own lock entries.
2. When a configuration is resolved, Gradle reads `gradle.lockfile`. For every library it finds there, it treats the locked version as a strict requirement. If the build's own requirements cannot be met with that version, resolution fails.
3. A library that is on a classpath but is **not** in the lockfile also fails the build, so nothing slips in unlocked.
4. To change the lock, you ask Gradle to recompute and rewrite the file: `./gradlew :dependencies --write-locks`. You commit the new file, so the change is visible in review.
5. Lines in the file are sorted, and each line says which configurations use that library. A line that ends in names after `=` means "these configurations use this exact version".

I tested step 2 in a scratch copy: after changing `awaitility:4.3.0` to `awaitility:4.2.2` in the lockfile only, `compileTestJava` failed with `Could not resolve org.awaitility:awaitility:4.3.0.` and `Cannot find a version of 'org.awaitility:awaitility' that satisfies the version constraints`, and the constraint line read `'org.awaitility:awaitility:{strictly 4.2.2}' because of the following reason: Dependency version enforced by Dependency Locking`. The real lockfile was not touched.

## Walkthrough of this repository's use

### `gradle.lockfile`, line by line

```text
# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
# To regenerate this file, run: ./gradlew :dependencies --write-locks
```

Lines 1 to 4 are comments that Gradle writes. They say the file is generated, hand edits are risky, it belongs in source control, and the command that regenerates it. The command is `./gradlew :dependencies --write-locks`; `:dependencies` names the root project's `dependencies` task.

Each line below has the shape `group:name:version=configuration,configuration,...`.

```text
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.h2database:h2:2.5.252=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

Two libraries of the main code, both used by all four compile and runtime classpaths: `compileClasspath` and `runtimeClasspath` (the main code) and `testCompileClasspath` and `testRuntimeClasspath` (the test code, which also sees the main code's libraries).
- `com.github.sviperll:result4j:1.2.0` is the result type used by the settings readers (`result4j.md`).
- `com.h2database:h2:2.5.252` is the database. It is new compared with the sibling repository `seatwave-estimates`. It is a direct dependency declared with `implementation`, so it is in the main classpaths and the test ones (`h2-and-jdbc.md`). It brings no libraries of its own: no other line in the file belongs to it.

```text
net.bytebuddy:byte-buddy:1.18.3=testCompileClasspath,testRuntimeClasspath
org.apiguardian:apiguardian-api:1.1.2=testCompileClasspath
org.assertj:assertj-core:3.27.7=testCompileClasspath,testRuntimeClasspath
org.awaitility:awaitility:4.3.0=testCompileClasspath,testRuntimeClasspath
org.hamcrest:hamcrest:2.1=testCompileClasspath,testRuntimeClasspath
```

- `net.bytebuddy:byte-buddy:1.18.3` is a transitive dependency of AssertJ (a library that creates classes at run time). Only the test classpaths use it.
- `org.apiguardian:apiguardian-api:1.1.2` holds annotations that JUnit uses to mark its public API. It appears only on `testCompileClasspath`: it is needed to compile against JUnit but not to run it.
- `org.assertj:assertj-core:3.27.7` is the assertion library from the catalog.
- `org.awaitility:awaitility:4.3.0` is the library that waits for a condition in a test (`awaitility.md`). It is new compared with the sibling repository.
- `org.hamcrest:hamcrest:2.1` is a transitive dependency of Awaitility: a library for describing conditions as objects. This repository never calls it. It is new compared with the sibling repository and appears on the two test classpaths. The README lists it too, with its license.

```text
org.jacoco:org.jacoco.agent:0.8.16=jacocoAgent,jacocoAnt
org.jacoco:org.jacoco.ant:0.8.16=jacocoAnt
org.jacoco:org.jacoco.core:0.8.16=jacocoAnt
org.jacoco:org.jacoco.report:0.8.16=jacocoAnt
```

These four are JaCoCo (`jacoco.md`) at 0.8.16, and they use two other configurations:
- `jacocoAgent` is the configuration for the **agent**, a small jar attached to the test JVM that counts which lines run. Only `org.jacoco:org.jacoco.agent` is in it (and also in `jacocoAnt`).
- `jacocoAnt` is the configuration for the Ant-based tasks that read the counts, write the report and check the rule: `org.jacoco.ant`, `org.jacoco.core`, `org.jacoco.report` and the agent.

```text
org.jspecify:jspecify:1.0.0=testCompileClasspath
org.junit.jupiter:junit-jupiter-api:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.jupiter:junit-jupiter-engine:6.1.3=testRuntimeClasspath
org.junit.jupiter:junit-jupiter-params:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.jupiter:junit-jupiter:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.platform:junit-platform-commons:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.platform:junit-platform-engine:6.1.3=testRuntimeClasspath
org.junit.platform:junit-platform-launcher:6.1.3=testRuntimeClasspath
org.junit:junit-bom:6.1.3=testCompileClasspath,testRuntimeClasspath
org.opentest4j:opentest4j:1.3.0=testCompileClasspath,testRuntimeClasspath
```

- `org.jspecify:jspecify:1.0.0` carries nullness annotations (`@Nullable`, "this value may be null") that JUnit's API classes use. It is on `testCompileClasspath` only: the compiler needs to read the annotations, but nothing needs them at run time, so `testRuntimeClasspath` does not list it. `./gradlew -q dependencyInsight --dependency jspecify --configuration testCompileClasspath` shows it was chosen `By ancestor` (it came from `junit-jupiter-api`) and fixed by the lock. The sibling repository has version 1.0.1 on both test classpaths because its test-data library asks for a newer one; this repository has no such library.
- Four `org.junit.jupiter` lines: `junit-jupiter-api` (the annotations such as `@Test`), `junit-jupiter-engine` (finds and runs tests; only at run time, so only `testRuntimeClasspath`), `junit-jupiter-params` (parameterized tests, which no test here uses), and `junit-jupiter` (the bundle that brings them together). All at 6.1.3.
- Three `org.junit.platform` lines: `junit-platform-commons`, `junit-platform-engine` (run time only) and `junit-platform-launcher` (run time only, the one the build file adds with `testRuntimeOnly`).
- `org.junit:junit-bom:6.1.3` is the bill of materials from `platform(...)`.
- `org.opentest4j:opentest4j:1.3.0` defines the `AssertionFailedError` that tests throw when a check fails.

```text
org.ow2.asm:asm-commons:9.11.1=jacocoAnt
org.ow2.asm:asm-tree:9.11.1=jacocoAnt
org.ow2.asm:asm:9.11.1=jacocoAnt
```

- Three `org.ow2.asm` lines at 9.11.1 (`asm`, `asm-commons`, `asm-tree`): a library that reads and changes Java bytecode. JaCoCo's Ant tasks use it, so the configuration is `jacocoAnt` only.

```text
empty=annotationProcessor,testAnnotationProcessor
```

`empty=` lists configurations that resolved to nothing: `annotationProcessor` and `testAnnotationProcessor`. An **annotation processor** is a compiler plug-in; this project has none, so both are empty. Recording them proves they were locked too, so adding one later is noticed. The sibling repository's file has one more line than this one, for `org.slf4j:slf4j-api`; this file has none, because no library here needs it.

### The meaning of each configuration name after `=`

| Name | What it holds |
| --- | --- |
| `compileClasspath` | Everything needed to compile the main code. |
| `runtimeClasspath` | Everything needed to run the main code. |
| `testCompileClasspath` | Everything needed to compile the tests. |
| `testRuntimeClasspath` | Everything needed to run the tests. |
| `jacocoAgent` | The JaCoCo agent that measures the tests. |
| `jacocoAnt` | The JaCoCo tools that write the report and check the floor. |
| `annotationProcessor`, `testAnnotationProcessor` | Compiler plug-ins for main and test code (empty here). |

### `settings-gradle.lockfile`

```text
# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
empty=incomingCatalogForLibs0
```

The three comment lines are the same as in the other file, without the regeneration line. The one data line, `empty=incomingCatalogForLibs0`, is the lock of an internal configuration Gradle creates for the catalog named `libs`; it holds no dependencies, so it is listed as empty. It exists because dependency locking also covers the settings phase, where catalogs are read.

### Regenerating the files

`./gradlew :dependencies --write-locks` recomputes both `gradle.lockfile` and `settings-gradle.lockfile`. In a scratch copy I deleted both files and ran the command: they were written again, byte for byte identical to the committed ones. I also ran it in this repository: it ended with exit status 0 and `git status --short` showed no change to either file, which proves the committed files are exactly what Gradle resolves. When you change a version in the catalog, run it, then commit both files.

## Try it yourself

1. Look at the locked versions:

```
cat gradle.lockfile
```

   Expected: the 4 comment lines and the 25 lines quoted above.

2. Prove the committed lock is current:

```
./gradlew dependencies --write-locks -q > /dev/null; git status --short
```

   Expected: no line for `gradle.lockfile` or `settings-gradle.lockfile` (the `tech/` folder may be listed while these notes are new).

3. See a version being enforced:

```
./gradlew -q dependencyInsight --dependency awaitility --configuration testRuntimeClasspath
```

   Expected: `org.awaitility:awaitility:4.3.0`, then a line `By constraint: Dependency version enforced by Dependency Locking`.

4. See the whole tree and compare with the lock:

```
./gradlew -q dependencies --configuration testRuntimeClasspath
```

   Expected: `org.assertj:assertj-core:3.27.7` with `net.bytebuddy:byte-buddy:1.18.3` below it, and `org.awaitility:awaitility:4.3.0` with `org.hamcrest:hamcrest:2.1` below it. There is no `jspecify` in this tree: it is only on the compile classpath.

5. Prove that `jspecify` is only a compile-time library:

```
./gradlew -q dependencyInsight --dependency jspecify --configuration testRuntimeClasspath
```

   Expected: `No dependencies matching given input were found in configuration ':testRuntimeClasspath'`.

## Mistakes people make

- **Editing the lockfile by hand.** The header warns about it. Use `--write-locks`.
- **Changing a version in the catalog and forgetting to regenerate.** The build then fails with "Dependency version enforced by Dependency Locking".
- **Not committing the lockfile.** The lock is only useful if everyone gets the same file.
- **Thinking the lock replaces the catalog.** The catalog says what you ask for; the lock records what Gradle found for it, including everything transitive.
- **Locking only some configurations.** `lockAllConfigurations()` locks them all, including the JaCoCo ones.
- **Ignoring a large lockfile diff.** Review it: it shows exactly which library versions moved.

## Check yourself

1. *What is a transitive dependency?* A library your dependencies need, which you did not ask for directly.
2. *Which command rewrites the lock?* `./gradlew :dependencies --write-locks`.
3. *What does the text after `=` on a lockfile line mean?* The configurations that use that exact version.
4. *Why is `junit-jupiter-engine` only on `testRuntimeClasspath`?* Tests need the engine only to run, not to compile.
5. *Which lock line did Awaitility add besides its own?* `org.hamcrest:hamcrest:2.1`, a transitive dependency on both test classpaths.

## Further reading

- Locking dependency versions in Gradle 9.9.1: <https://docs.gradle.org/9.9.1/userguide/dependency_locking.html>
- Declaring dependencies and configurations: <https://docs.gradle.org/9.9.1/userguide/declaring_dependencies.html>
- Viewing the dependency tree: <https://docs.gradle.org/9.9.1/userguide/viewing_debugging_dependencies.html>
