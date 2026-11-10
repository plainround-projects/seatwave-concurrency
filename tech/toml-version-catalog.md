# TOML and the Gradle version catalog

## What it is

**TOML** (Tom's Obvious Minimal Language) is a plain-text file format for settings. It is built to be read by people first. A TOML file is a list of `key = value` pairs, grouped under headings written in square brackets. Gradle uses one TOML file, `gradle/libs.versions.toml`, as a **version catalog**: a single list of every library, plugin and version the build uses, each with a short name. The build file refers to entries by name, so the version numbers live in exactly one place.

## Why this repository uses it

The README says "Every version is exact" and "The versions live in `gradle/libs.versions.toml`". One file means one place to read, one place to change and one place to check against the vulnerability database. The build file then contains no version number at all.

## Where it appears

| Path | What it does with TOML |
| --- | --- |
| `gradle/libs.versions.toml` | The catalog: 8 versions, 7 libraries, 1 plugin. |
| `build.gradle.kts` | Reads it through `libs.xxx` names (`gradle.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | The locked, resolved result of what the catalog asks for (`dependency-locking.md`). |
| `.editorconfig` | Gives `*.toml` files a two-space indent (`editorconfig.md`). |

## How it works underneath

1. Gradle looks for `gradle/libs.versions.toml` automatically. The file name decides the catalog name: `libs`.
2. It parses the three tables and, for each entry, creates a typed accessor that the Kotlin build file can call.
3. Nothing is downloaded when the catalog is read. It is only a list of names. Downloads start when a task needs a classpath.
4. `settings-gradle.lockfile` has a line for the internal configuration that Gradle uses to read the catalog (`dependency-locking.md`).

**TOML syntax in six rules.**
- `#` starts a comment that runs to the end of the line.
- `[name]` on its own line starts a **table** (a named group). Everything below it belongs to it, until the next table heading.
- `key = "text"`: a key, an equals sign and a value. Text is always in double quotes.
- `{ a = "x", b = "y" }` is an **inline table**: a small group written on one line.
- `version.ref = "junit"` is a **dotted key**: the key `version` holds a table whose key `ref` is `"junit"`. It is the same as `version = { ref = "junit" }`.
- Keys are case sensitive. Blank lines do not matter.

## Walkthrough of this repository's use

### The whole file

```toml
# Every version is exact. Each was checked on Maven Central or the Gradle Plugin Portal on 10 November 2026.
[versions]
result4j = "1.2.0"
h2 = "2.5.252"
junit = "6.1.3"
assertj = "3.27.7"
awaitility = "4.3.0"
spotless = "8.11.0"
googleJavaFormat = "2.0.0"
jacoco = "0.8.16"

[libraries]
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
h2 = { module = "com.h2database:h2", version.ref = "h2" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
awaitility = { module = "org.awaitility:awaitility", version.ref = "awaitility" }

[plugins]
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

### Line 1: the comment

The `#` line says every version is exact and was checked on 10 November 2026. Exact means there are no ranges such as `3.+`: a range would let a new release appear without any change to the repository.

### Lines 2 to 10: `[versions]`

A table of named versions. The name is yours to choose; the value is a string.
- `result4j = "1.2.0"` the result library.
- `h2 = "2.5.252"` the in-memory database (`h2-and-jdbc.md`).
- `junit = "6.1.3"` the version of the whole JUnit family. It is used by the BOM entry below.
- `assertj = "3.27.7"` the assertion library.
- `awaitility = "4.3.0"` the library that waits for a condition in a test (`awaitility.md`).
- `spotless = "8.11.0"` the version of the Spotless Gradle plugin.
- `googleJavaFormat = "2.0.0"` the version of the formatter that Spotless runs. It is read directly by the build file.
- `jacoco = "0.8.16"` the coverage tool version. Also read directly by the build file.

### Lines 12 to 19: `[libraries]`

Each line is an alias, an equals sign and an inline table.
- `module = "group:name"` is the library's address on Maven Central: the group, a colon and the artifact name. For example `com.github.sviperll:result4j`.
- `version.ref = "result4j"` says "use the entry called `result4j` from `[versions]`". The reference is by name, not by number.
- `junit-jupiter` and `junit-platform-launcher` have **no version**. That is intentional: the build file brings in `junit-bom` first (`platform(libs.junit.bom)`), and the BOM's list supplies the version for every JUnit module. So `junit-bom` is the only JUnit library that carries `version.ref = "junit"`.

### Lines 21 and 22: `[plugins]`

`spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }` names a Gradle plugin. `id` is the plugin's identifier and `version.ref` points at the version `8.11.0`. In the build file it is switched on with `alias(libs.plugins.spotless)`.

### How a catalog name becomes `libs.something`

The part before the first dot is the catalog name (`libs`). After it comes the table. Dashes, underscores and dots in an alias all become dots in the accessor.

| Catalog line | In `build.gradle.kts` | Meaning |
| --- | --- | --- |
| `result4j = { module = ... }` in `[libraries]` | `libs.result4j` | The library `com.github.sviperll:result4j:1.2.0`. |
| `h2 = { ... }` | `libs.h2` | The H2 database 2.5.252. |
| `junit-bom = { ... }` | `libs.junit.bom` | The JUnit bill of materials 6.1.3. |
| `junit-jupiter = { ... }` | `libs.junit.jupiter` | The Jupiter bundle, version from the BOM. |
| `junit-platform-launcher = { ... }` | `libs.junit.platform.launcher` | The launcher, version from the BOM. |
| `assertj-core = { ... }` | `libs.assertj.core` | AssertJ 3.27.7. |
| `awaitility = { ... }` | `libs.awaitility` | Awaitility 4.3.0. |
| `spotless = { id = ... }` in `[plugins]` | `libs.plugins.spotless` | The Spotless plugin 8.11.0. |
| `googleJavaFormat = "2.0.0"` in `[versions]` | `libs.versions.googleJavaFormat.get()` | The text `2.0.0`. |
| `jacoco = "0.8.16"` in `[versions]` | `libs.versions.jacoco.get()` | The text `0.8.16`. |

A library entry used as a dependency is turned into a full address with a version. A version entry used as a value has to be read with `.get()`, which returns its text.

## Try it yourself

1. See which catalog names the build uses:

```
grep -n "libs\." build.gradle.kts
```

   Expected: lines for `libs.plugins.spotless`, `libs.result4j`, `libs.h2`, `libs.junit.bom`, `libs.junit.jupiter`, `libs.assertj.core`, `libs.awaitility`, `libs.junit.platform.launcher`, `libs.versions.googleJavaFormat` and `libs.versions.jacoco`.

2. Ask Gradle which version AssertJ resolved to and why:

```
./gradlew -q dependencyInsight --dependency assertj-core --configuration testRuntimeClasspath
```

   The output starts with `org.assertj:assertj-core:3.27.7` and includes `By constraint: Dependency version enforced by Dependency Locking`.

3. See the BOM at work:

```
./gradlew -q dependencies --configuration testRuntimeClasspath | grep "junit-jupiter "
```

   Expected: a line with `org.junit.jupiter:junit-jupiter -> 6.1.3`. The version came from the BOM, not from the catalog entry.

4. Count the entries:

```
grep -c " = " gradle/libs.versions.toml
```

   Expected: `16` (8 versions, 7 libraries, 1 plugin).

## Mistakes people make

- **Single quotes or no quotes around text.** TOML text uses double quotes (single quotes are literal strings, rarely needed).
- **Writing a version where `version.ref` is meant.** `version = "1.2.0"` inside a library entry works but defeats the one-place rule.
- **Using a range** such as `"3.+"`. This repository pins exact versions on purpose.
- **Mixing up the dash and the dot.** The alias `junit-jupiter` is spelled `libs.junit.jupiter` in Kotlin.
- **Forgetting `.get()`** on a `libs.versions...` value.
- **Giving the BOM members their own versions.** That would fight the BOM. They are left without one here.

## Check yourself

1. *What are the three tables in this file?* `[versions]`, `[libraries]` and `[plugins]`.
2. *What does `version.ref = "assertj"` mean?* "Use the version named `assertj` from the `[versions]` table."
3. *Why does `junit-jupiter` have no version?* The BOM (`junit-bom`) provides it.
4. *How is `junit-platform-launcher` written in the build file?* `libs.junit.platform.launcher`.
5. *Why exact versions?* So no new release can change the build without a visible change to this file.

## Further reading

- Version catalogs in Gradle 9.9.1: <https://docs.gradle.org/9.9.1/userguide/version_catalogs.html>
- The TOML 1.0.0 specification: <https://toml.io/en/v1.0.0>
- Platforms and BOMs in Gradle: <https://docs.gradle.org/9.9.1/userguide/platforms.html>
