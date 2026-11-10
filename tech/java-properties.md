# The `.properties` format and the settings files under `config/`

## What it is

A **properties file** is a plain text file of settings. Each setting is one line with a **key** (the name) and a **value**, joined by an equals sign: `platform.workers=64`. The format comes from Java. The JDK class `java.util.Properties` reads it into a **map** from text to text. A map is a collection of pairs where you look a value up by its key. The file ends in `.properties` by habit, but the reader does not care about the name: the program is told which file to open.

Everything in a properties file is text. The program decides that `64` is a number and converts it. If the text is not a number, the conversion fails, and the program must say so.

## Why this repository uses it

Every program in the repository reads its numbers from a file in `config/` and not from the code. Three reasons. A person can change a number without compiling anything. Every number has a comment that says why it has that value, with the arithmetic. And there are **no defaults in code**: a missing key is an error, so two machines can never run two different experiments by accident. The benchmark needs 19 keys, and the file `config/simple.properties` holds all of them with their reasons.

## Where it appears

| File | Read by | What it sets |
|---|---|---|
| `config/simple.properties` | `BenchmarkCli` | SIMPLE: SeatWave version 0's thread model (64 threads, 16 connections). |
| `config/production.properties` | `BenchmarkCli` | PRODUCTION: one node sized by Little's Law (25 threads, 25 connections). |
| `config/wide-pool.properties` | `BenchmarkCli` | Lab 2: the same 64 threads with 128 connections. |
| `config/unbounded.properties` | `BenchmarkCli` | Every limit opened wide, to show what an unbounded queue does. |
| `config/sizing.properties` | `SizingCli` | The numbers a node is sized from. |
| `config/thread-cost.properties` | `ThreadCostCli` | How many threads of each kind to start. |
| `config/event-loop.properties` | `EventLoopCli` | Lab 3: the clients and the work time. |
| `SettingsReader.java` | all of the above | Loads the file as UTF-8 and reads typed values. See `java-sizing-threads-config-walkthrough.md`. |

## How it works underneath

**The format.** The reader goes through the file line by line. I ran a small program to check each rule on JDK 25.

- A line that starts with `#` or `!` is a **comment**. It is ignored. These files use `#`.
- A blank line is ignored.
- A line like `key=value` sets a key. The separator may be `=`, `:` or white space. These files use `=`. I loaded `colon: value` and got the value `value`.
- White space around the key and before the value is dropped. White space at the **end** of a value is kept. I loaded `name = hello world ` and got `hello world ` with a trailing space. That is why `SettingsReader.text` calls `strip()`.
- A `#` in the middle of a line is **not** a comment: it is part of the value. I loaded `h=x # not a comment` and got `x # not a comment`. So a comment must be on its own line, as in every file here.
- If a key appears twice, the last one wins. I loaded `e=1` then `e=2` and got `2`.
- A backslash starts an escape: `\t` is a tab. I loaded `g=C:\temp` and got `C:`, a tab, and `emp`. A backslash at the end of a line continues the value on the next line. These files have no backslashes.
- Dots in a key mean nothing to the reader. `pool.borrow.limit.millis` is just that name. The dots are a naming habit that groups keys.
- A lookup of a key that is not there returns `null`. `SettingsReader.text` turns that into the problem line `<key> is missing`.

**What the program does with it.** `SettingsReader.load` opens the file with `Files.newBufferedReader(path, StandardCharsets.UTF_8)` and passes the reader to `properties.load(reader)`. A `Reader` gives characters decoded as UTF-8. Passing a byte stream instead would decode the file in an older encoding. Then each typed read converts the text: `Integer.parseInt` for a whole number, `Double.parseDouble` for a decimal, `split(",")` for a list. `Integer.parseInt("2,000")` throws `NumberFormatException: For input string: "2,000"`, and `Integer.parseInt("2147483648")` throws the same, because the number is too big for an `int`. The reader catches both and records a problem line.

**Lists.** A list is written as comma-separated text: `load.steps.rps=200,320,360`. `items(key)` splits at commas and strips each item, so `200, 320 ,360` is three items (I loaded it that way). `"1,2,".split(",", -1)` gives `[1, 2, ]` (three items, the last one empty, which the reader reports), while `split(",")` would drop the empty one without a word.

**Units in the name.** A key ends in `.millis` or `.seconds` or `.percent` so the unit is in the name. `pool.borrow.limit.millis=500` is 500 milliseconds. A key with the wrong unit would be a silent factor-of-1,000 error. A thousand milliseconds is one second.

**Required, with ranges.** Each key is read with a range, such as 1 to 100,000. A value outside it records `<key> must be from <min> to <max>, but was <value>`. The program prints every problem and exits with status 2 before it starts any work.

## Walkthrough of this repository's use

The first file explains the nineteen benchmark keys. The next three files have the same keys with different values, so they are shown with what changed and why.

### `config/simple.properties`

SIMPLE is SeatWave version 0's thread model from Module 1: 64 platform threads and 16 database connections. It is the file to read first. Every line below is quoted from it.

**Lines 1 to 2**

```properties
# SIMPLE: SeatWave version 0's thread model (Module 1): 64 platform threads and 16 database connections.
# Every key is required; the benchmark has no defaults in code.
```

- The two comment lines name the case and say every key is required: the benchmark has no defaults in code. `SettingsReader` makes each key a problem line if it is missing.

**Lines 3 to 5**

```properties
# The thread models to run, in order. The same load runs once per mode.
executor.modes=platform,virtual
```

- `executor.modes=platform,virtual` is a list of thread models, read by `reader.enumList("executor.modes", ExecutorMode.class)`. The words are the enum values in lower case. The benchmark runs the whole load once for each mode, in this order. `platform` alone, or `virtual` alone, runs one mode (`unbounded.properties` does that). A word that is not a mode, such as `fiber`, gives `executor.modes has an unknown value: fiber`.

**Lines 6 to 8**

```properties
# Module 1: seatwave.workerThreads=64.
platform.workers=64
```

- `platform.workers=64` is the number of threads in the fixed platform pool (Module 1: `seatwave.workerThreads=64`). Range 1 to 100,000. More workers let more holds run at once, but each hold also needs a connection, so beyond the connection count the extra threads only wait in `ConnectionPool.borrow`. Fewer workers lower the throughput: 64 threads, each busy 40 ms per hold, can serve at most 64 / 0.040 = 1,600 holds per second.

**Lines 9 to 10**

```properties
# 300 ms (Module 2's hold p99 target) x 400 holds/s (16 connections / 0.040 s) = 120 holds may wait.
platform.queue.bound=120
```

- `platform.queue.bound=120` is how many accepted holds may wait for a free thread. The comment gives the sum: 300 ms (the p99 target) times 400 holds per second (what 16 connections can serve) is 120 holds. A bigger bound accepts more holds before refusing, and each waits longer. A bound of 1 refuses almost everything that does not find a free thread.

**Lines 11 to 12**

```properties
# The same door as platform mode: 64 running + 120 waiting, so both modes refuse at the same count.
virtual.max.in.flight=184
```

- `virtual.max.in.flight=184` is the cap in virtual mode: the semaphore's permits. The comment: 64 running plus 120 waiting is 184, so both modes refuse at the same count and the comparison is fair. It is not used in `platform` mode.

**Lines 13 to 14**

```properties
# A hold that has waited the whole p99 budget (300 ms) before its work starts is refused.
queue.wait.limit.millis=300
```

- `queue.wait.limit.millis=300` is the longest a hold may wait from arrival until a worker starts it. Past it the hold is refused with `QUEUE_WAIT_EXCEEDED` and costs no database work. Range 0 to 60,000. With 0, any hold that waited at all would be refused. With 60,000 (`unbounded.properties`) the limit never acts.

**Lines 15 to 17**

```properties
# Module 1: seatwave.dbConnections=16 and seatwave.connectionWaitMillis=500.
pool.connections=16
```

- `pool.connections=16` is the size of the connection pool (Module 1: `seatwave.dbConnections=16`). Each connection is held 40 ms per hold, so the pool serves at most 16 / 0.040 = 400 holds per second. This number, not the thread count, is the node's capacity here.

**Lines 18 to 18**

```properties
pool.borrow.limit.millis=500
```

- `pool.borrow.limit.millis=500` is the longest a hold waits for a connection (Module 1: `seatwave.connectionWaitMillis=500`). Past it, `POOL_TIMEOUT`. A lower value refuses sooner and shows up in the `503p` column. 0 means "do not wait at all".

**Lines 19 to 20**

```properties
# Each hold keeps its connection 40 ms: the remote database of the Module 4 walkthrough.
database.wait.millis=40
```

- `database.wait.millis=40` is how long each hold keeps its connection after the statement, standing for the remote database. It is the `Thread.sleep` in `HoldEndpoint.waitForRemoteDatabase`. Double it and the pool's capacity halves. 0 skips the wait.

**Lines 21 to 21**

```properties
database.statement.timeout.seconds=2
```

- `database.statement.timeout.seconds=2` is the JDBC query timeout of the hold statement, 1 to 60 seconds. A statement that takes longer fails and the hold answers `DATABASE_FAILED`.

**Lines 22 to 23**

```properties
# In memory only; the benchmark adds the mode name, so each run has its own database.
database.url=jdbc:h2:mem:seatwave-simple-
```

- `database.url=jdbc:h2:mem:seatwave-simple-` is the database. It must start with `jdbc:h2:mem:` or `NodeSettings` refuses it. It ends with a hyphen because `BenchmarkRun` appends the mode name: `...-platform` and `...-virtual` are two separate in-memory databases.

**Lines 24 to 26**

```properties
# 16 connections / 0.040 s = 400 holds/s. The steps are 50, 80, 90, 95, 99 and 110 percent of it.
load.steps.rps=200,320,360,380,396,440
```

- `load.steps.rps=200,320,360,380,396,440` are the offered rates, in holds per second, one step each, in order. The comment: they are 50, 80, 90, 95, 99 and 110 percent of the 400 holds per second that the pool serves. Each is 1 to 100,000. Keep them rising: `lastHealthyRate` stops at the first step that misses the target. This is the list that sets the x axis of the chart.

**Lines 27 to 27**

```properties
load.warmup.millis=3000
```

- `load.warmup.millis=3000` is the warm-up: the first rate runs for 3 seconds and its numbers are thrown away, so the JIT compiler has compiled the hot path.

**Lines 28 to 28**

```properties
load.step.millis=10000
```

- `load.step.millis=10000` is the length of each step. A step at 380 holds per second sends 380 x 10 = 3,800 holds. A longer step makes p99 steadier and the run slower.

**Lines 29 to 29**

```properties
load.random.seed=20261110
```

- `load.random.seed=20261110` is the seed of the arrival moments and seat choices (the seed plus the rate). The same seed gives the same holds at the same moments. 1 to 2,147,483,647.

**Lines 30 to 31**

```properties
# Module 1's load generator gave up after 2 seconds.
client.timeout.millis=2000
```

- `client.timeout.millis=2000` is how long a client waits for a reply (Module 1's load generator gave up after 2 seconds). A reply later than this counts as a client timeout, the `late` column.

**Lines 32 to 32**

```properties
sampler.interval.millis=10
```

- `sampler.interval.millis=10` is how often the sampler reads the pool counters. A smaller value reads more often and uses a little more CPU.

**Lines 33 to 33**

```properties
census.at.rps=440
```

- `census.at.rps=440` is the step at which the thread census is taken. It **must be one of the steps**: `BenchmarkSettings` refuses a value that is not in `load.steps.rps`.

**Lines 34 to 36**

```properties
# Module 2: hold p99 under 300 ms, and 99.95 percent good requests (0.05 percent may fail).
target.p99.millis=300
```

- `target.p99.millis=300` is the p99 that a healthy step must stay at or under (Module 2). It draws the dashed target line on the p99 chart.

**Lines 37 to 37**

```properties
target.max.error.percent=0.05
```

- `target.max.error.percent=0.05` is the share of holds that may fail in a healthy step: 99.95 percent must be good. It is read as a decimal, 0 to 100.

### The three other benchmark files: what changes

They have the same nineteen keys. Only the values and the reasons differ.

| Key | simple | production | wide-pool | unbounded |
|---|---|---|---|---|
| `executor.modes` | platform,virtual | platform,virtual | platform,virtual | platform |
| `platform.workers` | 64 | 25 | 64 | 64 |
| `platform.queue.bound` | 120 | 187 | 480 | 100000 |
| `virtual.max.in.flight` | 184 | 10000 | 10000 | 184 |
| `queue.wait.limit.millis` | 300 | 300 | 300 | 60000 |
| `pool.connections` | 16 | 25 | 128 | 16 |
| `pool.borrow.limit.millis` | 500 | 100 | 500 | 60000 |
| `load.steps.rps` | 200 to 440 | 250 to 650 | 800 to 3600 | 440, 600 |
| `census.at.rps` | 440 | 500 | 2000 | 600 |

### `config/production.properties`

PRODUCTION: one node sized by Little's Law (Module 4, Section 7, and Lab 1): 500 holds per second at 40 ms each, and a database that allows 32 connections.

**Lines 1 to 2**

```properties
# PRODUCTION, one node (Module 4, Section 7, and Lab 1): 500 holds/s at 40 ms each,
# a database that allows 32 connections. Every key is required.
```

- The header says what the file models. The 500 holds per second and the 32 connections are the numbers of `config/sizing.properties`.

**Lines 3 to 4**

```properties
executor.modes=platform,virtual
```

- Both modes run, as in SIMPLE.

**Lines 5 to 7**

```properties
# Little's Law: 500 holds/s x 0.040 s = 20 in flight; at an 80 percent ceiling, 20 / 0.8 = 25.
platform.workers=25
```

- `platform.workers=25`: Little's Law says 500 x 0.040 = 20 holds in flight, and at an 80 percent ceiling 20 / 0.8 = 25 threads. The `sizing` program prints the same 25.

**Lines 8 to 9**

```properties
# 300 ms x 625 holds/s (25 / 0.040 s) = 187 holds may wait for a platform thread.
platform.queue.bound=187
```

- `platform.queue.bound=187`: 300 ms x 625 holds per second (25 / 0.040) is 187 holds that may wait for a thread.

**Lines 10 to 10**

```properties
virtual.max.in.flight=10000
```

- `virtual.max.in.flight=10000`: a high cap, because virtual threads are cheap. The pool, not this cap, is the limit.

**Lines 11 to 11**

```properties
queue.wait.limit.millis=300
```

- `queue.wait.limit.millis=300`: the whole p99 budget.

**Lines 12 to 14**

```properties
# The same Little's Law size, 25 of the 32 connections the database allows.
pool.connections=25
```

- `pool.connections=25`: the same Little's Law size, 25 of the 32 connections the database allows.

**Lines 15 to 17**

```properties
# 300 ms p99 budget - 40 ms of work, with room for a queue wait: a hold that cannot get a
# connection in 100 ms is refused while the fan still has time to retry.
pool.borrow.limit.millis=100
```

- `pool.borrow.limit.millis=100`: the comment explains it: the 300 ms budget minus 40 ms of work, with room for a queue wait; a hold that cannot get a connection in 100 ms is refused while the fan still has time to retry. A shorter borrow limit than SIMPLE's 500 ms.

**Lines 18 to 18**

```properties
database.wait.millis=40
```

- `database.wait.millis=40`: as in SIMPLE.

**Lines 19 to 19**

```properties
database.statement.timeout.seconds=2
```

- `database.statement.timeout.seconds=2`: as in SIMPLE.

**Lines 20 to 20**

```properties
database.url=jdbc:h2:mem:seatwave-production-
```

- `database.url=jdbc:h2:mem:seatwave-production-`: its own name, with the mode appended.

**Lines 21 to 23**

```properties
# 25 / 0.040 s = 625 holds/s. The steps are 40, 64, 80, 90, 95 and 104 percent of it.
load.steps.rps=250,400,500,563,594,650
```

- `load.steps.rps=250,400,500,563,594,650`: 25 / 0.040 s = 625 holds per second is the capacity, and the steps are 40, 64, 80, 90, 95 and 104 percent of it. The last is just over the capacity. In the repository `README.md`, both modes' last healthy step is 594: when the pool is the limit, the thread model does not matter.

**Lines 24 to 24**

```properties
load.warmup.millis=3000
```

- `load.warmup.millis=3000`, as in SIMPLE.

**Lines 25 to 25**

```properties
load.step.millis=10000
```

- `load.step.millis=10000`, as in SIMPLE.

**Lines 26 to 26**

```properties
load.random.seed=20261110
```

- `load.random.seed=20261110`, as in SIMPLE.

**Lines 27 to 27**

```properties
client.timeout.millis=2000
```

- `client.timeout.millis=2000`, as in SIMPLE.

**Lines 28 to 28**

```properties
sampler.interval.millis=10
```

- `sampler.interval.millis=10`, as in SIMPLE.

**Lines 29 to 29**

```properties
census.at.rps=500
```

- `census.at.rps=500`: the on-sale's planned rate, which is one of the steps.

**Lines 30 to 31**

```properties
target.p99.millis=300
```

- `target.p99.millis=300`, as in SIMPLE.

**Lines 32 to 32**

```properties
target.max.error.percent=0.05
```

- `target.max.error.percent=0.05`, as in SIMPLE.

### `config/wide-pool.properties`

Lab 2: the same 64 platform threads, but a database that allows 128 connections. Platform threads now run out first; virtual threads run until the pool does.

**Lines 1 to 3**

```properties
# Lab 2: the same 64 platform threads, but a database that allows 128 connections.
# Platform threads now run out first (64 / 0.040 s = 1,600 holds/s); virtual threads
# run until the pool does (128 / 0.040 s = 3,200 holds/s). Every key is required.
```

- The header gives the two ceilings: 64 / 0.040 s = 1,600 holds per second for the threads, and 128 / 0.040 s = 3,200 for the pool. So the 64 platform threads are the limit in platform mode, and the pool in virtual mode.

**Lines 4 to 5**

```properties
executor.modes=platform,virtual
```

- Both modes run.

**Lines 6 to 7**

```properties
platform.workers=64
```

- `platform.workers=64`: the same as SIMPLE.

**Lines 8 to 9**

```properties
# 300 ms x 1,600 holds/s = 480 holds may wait for a platform thread.
platform.queue.bound=480
```

- `platform.queue.bound=480`: 300 ms x 1,600 holds per second is 480 holds that may wait for a thread.

**Lines 10 to 11**

```properties
# PRODUCTION's on-sale: up to 10,000 holds in flight, each on its own virtual thread.
virtual.max.in.flight=10000
```

- `virtual.max.in.flight=10000`: PRODUCTION's on-sale, up to 10,000 holds in flight, each on its own virtual thread.

**Lines 12 to 12**

```properties
queue.wait.limit.millis=300
```

- `queue.wait.limit.millis=300`.

**Lines 13 to 14**

```properties
pool.connections=128
```

- `pool.connections=128`: eight times SIMPLE's pool. This is the change that moves the limit from the pool to the threads.

**Lines 15 to 15**

```properties
pool.borrow.limit.millis=500
```

- `pool.borrow.limit.millis=500`.

**Lines 16 to 16**

```properties
database.wait.millis=40
```

- `database.wait.millis=40`.

**Lines 17 to 17**

```properties
database.statement.timeout.seconds=2
```

- `database.statement.timeout.seconds=2`.

**Lines 18 to 18**

```properties
database.url=jdbc:h2:mem:seatwave-wide-
```

- `database.url=jdbc:h2:mem:seatwave-wide-`.

**Lines 19 to 20**

```properties
load.steps.rps=800,1200,1600,2000,2400,2800,3200,3600
```

- `load.steps.rps=800,1200,1600,2000,2400,2800,3200,3600`: eight steps that span both ceilings, 1,600 and 3,200. The repository `README.md` says the last healthy step was 1,200 for platform and 3,200 for virtual.

**Lines 21 to 21**

```properties
load.warmup.millis=3000
```

- `load.warmup.millis=3000`.

**Lines 22 to 22**

```properties
load.step.millis=10000
```

- `load.step.millis=10000`: at 3,600 holds per second each step sends 36,000 holds.

**Lines 23 to 23**

```properties
load.random.seed=20261110
```

- `load.random.seed=20261110`.

**Lines 24 to 24**

```properties
client.timeout.millis=2000
```

- `client.timeout.millis=2000`.

**Lines 25 to 25**

```properties
sampler.interval.millis=10
```

- `sampler.interval.millis=10`.

**Lines 26 to 26**

```properties
census.at.rps=2000
```

- `census.at.rps=2000`: between the two ceilings, where platform threads are saturated and virtual threads are not.

**Lines 27 to 28**

```properties
target.p99.millis=300
```

- `target.p99.millis=300`.

**Lines 29 to 29**

```properties
target.max.error.percent=0.05
```

- `target.max.error.percent=0.05`.

### `config/unbounded.properties`

Section 9, failure mode 3: SIMPLE with its limits opened so wide that nothing is refused. This is the file that shows what an unbounded queue does. It is also the shortest run (about 30 seconds).

**Lines 1 to 3**

```properties
# Section 9, failure mode 3: SIMPLE with its limits opened so wide that nothing is refused.
# The queue bound is the largest the settings allow, and the waits are a minute long,
# so overload turns into waiting instead of 503. Every key is required.
```

- The header says what it models: the queue bound is the largest the settings allow, and the waits are a minute long, so overload turns into waiting instead of 503.

**Lines 4 to 5**

```properties
executor.modes=platform
```

- `executor.modes=platform`: only the platform pool runs, to keep the run short.

**Lines 6 to 7**

```properties
platform.workers=64
```

- `platform.workers=64`.

**Lines 8 to 9**

```properties
# Near unbounded: 100,000 holds may wait for a thread.
platform.queue.bound=100000
```

- `platform.queue.bound=100000`: near unbounded. 100,000 is `NodeSettings.MAX_COUNT`, the largest the settings accept. A queue of 100,000 holds is far more than a 12-second step can send.

**Lines 10 to 10**

```properties
virtual.max.in.flight=184
```

- `virtual.max.in.flight=184`: unused, because only platform mode runs. It must still be present: every key is required.

**Lines 11 to 12**

```properties
# A minute: in practice no limit for a 10-second step.
queue.wait.limit.millis=60000
```

- `queue.wait.limit.millis=60000`: a minute, in practice no limit for a 10-second step. This is the maximum the reader accepts (`MAX_WAIT_MILLIS`).

**Lines 13 to 14**

```properties
pool.connections=16
```

- `pool.connections=16`: as in SIMPLE.

**Lines 15 to 15**

```properties
pool.borrow.limit.millis=60000
```

- `pool.borrow.limit.millis=60000`: a hold would wait a minute for a connection.

**Lines 16 to 16**

```properties
database.wait.millis=40
```

- `database.wait.millis=40`.

**Lines 17 to 17**

```properties
database.statement.timeout.seconds=2
```

- `database.statement.timeout.seconds=2`.

**Lines 18 to 18**

```properties
database.url=jdbc:h2:mem:seatwave-unbounded-
```

- `database.url=jdbc:h2:mem:seatwave-unbounded-`.

**Lines 19 to 21**

```properties
# 440 and 600 holds/s: 110 and 150 percent of the 400 holds/s that 16 connections serve.
load.steps.rps=440,600
```

- `load.steps.rps=440,600`: 110 and 150 percent of the 400 holds per second that 16 connections serve. Both are overload.

**Lines 22 to 22**

```properties
load.warmup.millis=3000
```

- `load.warmup.millis=3000`.

**Lines 23 to 23**

```properties
load.step.millis=10000
```

- `load.step.millis=10000`.

**Lines 24 to 24**

```properties
load.random.seed=20261110
```

- `load.random.seed=20261110`.

**Lines 25 to 25**

```properties
client.timeout.millis=2000
```

- `client.timeout.millis=2000`: the client still gives up after 2 seconds, so the late holds show as `late` and not as refusals.

**Lines 26 to 26**

```properties
sampler.interval.millis=10
```

- `sampler.interval.millis=10`.

**Lines 27 to 27**

```properties
census.at.rps=600
```

- `census.at.rps=600`: the heavier step.

**Lines 28 to 29**

```properties
target.p99.millis=300
```

- `target.p99.millis=300`.

**Lines 30 to 30**

```properties
target.max.error.percent=0.05
```

- `target.max.error.percent=0.05`.

### `config/sizing.properties`

The numbers of the Module 4 walkthrough (Section 7), read by `SizingInputs.from`. Every key is required.

**Lines 1 to 1**

```properties
# The numbers of the Module 4 walkthrough (Section 7). Every key is required.
```

- The header says it holds the numbers of the walkthrough and that every key is required.

**Lines 2 to 5**

```properties
# The on-sale: 500 holds per second reach this node, and each waits about 40 ms on the database.
hold.rate.per.second=500
hold.time.millis=40
```

- `hold.rate.per.second=500` is the hold rate at the busiest moment (1 to 1,000,000). `hold.time.millis=40` is how long each hold keeps a thread and a connection (1 to 60,000 ms). Together they give 500 x 0.040 = 20 holds in flight.

**Lines 6 to 7**

```properties
# The database allows this node 32 connections.
database.max.connections=32
```

- `database.max.connections=32` is the number of connections the database allows this node. The report says whether the pool "fits" under it.

**Lines 8 to 10**

```properties
# 2,000 seat-map reads per second, each about 5 ms of CPU (Module 1's estimate).
seatmap.rate.per.second=2000
seatmap.cpu.millis=5
```

- `seatmap.rate.per.second=2000` and `seatmap.cpu.millis=5`: 2,000 seat-map reads per second, each about 5 ms of CPU (Module 1's estimate). They give 2,000 x 0.005 = 10 vCPUs busy.

**Lines 11 to 12**

```properties
# The reference machine (Module 1): 4 vCPU.
node.vcpus=4
```

- `node.vcpus=4` is the reference machine's processors. The seat maps need 10 and the node has 4, so the report says one node cannot serve this.

**Lines 13 to 14**

```properties
# A pool above 80 percent busy on average is too close to its limit (this module's alert line).
pool.utilization.ceiling.percent=80
```

- `pool.utilization.ceiling.percent=80`: a pool above 80 percent busy on average is too close to its limit. It is the divisor in the pool size: 20 / 0.80 = 25. Range 1 to 100.

**Lines 15 to 16**

```properties
# Modules 2 and 3 plan the CPU at 70 percent of the knee.
cpu.utilization.target.percent=70
```

- `cpu.utilization.target.percent=70`: Modules 2 and 3 plan the CPU at 70 percent of the knee: 800 x 0.70 = 560 seat maps per second per node, so 2,000 / 560 rounds up to 4 nodes.

### `config/thread-cost.properties`

What many waiting threads cost, read by `ThreadCostCli`. Every key is required.

**Lines 1 to 2**

```properties
# What many waiting threads cost. Every key is required.
# Platform counts stay below macOS's limit of 8,192 threads per process on the verification machine.
```

- The header says every key is required. The second comment says the platform counts stay below macOS's limit of 8,192 threads per process on the verification machine. I read that limit as `kern.num_taskthreads: 8192`.

**Lines 3 to 3**

```properties
platform.thread.counts=1000,4000
```

- `platform.thread.counts=1000,4000` is a list of how many platform threads to start, one measurement for each. Each is 1 to 6,000 (`MAX_PLATFORM_THREADS` in `ThreadCostCli`). A number above that is refused with a problem line, so the program cannot be told to hit the system's limit.

**Lines 4 to 4**

```properties
virtual.thread.counts=1000,4000,10000,100000
```

- `virtual.thread.counts=1000,4000,10000,100000` is the same for virtual threads, each 1 to 1,000,000.

**Lines 5 to 6**

```properties
# No start, wait or join may take longer than this.
wait.limit.millis=60000
```

- `wait.limit.millis=60000` is the longest any start, wait or join may take, 1 to 120,000 ms. If it passes, the measurement fails with a message and exit status 1 instead of hanging.

### `config/event-loop.properties`

Lab 3: one event-loop thread, and a burst of fans who each send one hold at the same moment, read by `EventLoopCli`.

**Lines 1 to 2**

```properties
# Lab 3: one event-loop thread, and a burst of fans who each send one hold at the same moment.
# Every key is required.
```

- The header describes the lab. Every key is required.

**Lines 3 to 5**

```properties
# Ten fans, so the stall is easy to read: in the broken mode the last one waits ten holds.
clients=10
```

- `clients=10` is how many fans connect and send at once (1 to 1,000). Ten is enough to see the staircase: in the broken mode the last fan waits for ten holds in a row. A bigger number makes the staircase longer.

**Lines 6 to 7**

```properties
# Each hold blocks for 40 ms, the database wait of this module.
work.millis=40
```

- `work.millis=40` is how long the blocking part of each request takes (0 to 60,000). It is the `Thread.sleep` that stands for the database call. I ran the lab with 80 and the staircase's step doubled from about 44 ms to about 85 ms.

**Lines 8 to 9**

```properties
# No socket call may block longer than this.
socket.timeout.millis=5000
```

- `socket.timeout.millis=5000` is the longest any socket call may block (1 to 60,000). A call that passes it throws an `IOException`, so the lab fails with a message instead of hanging.

**Lines 10 to 11**

```properties
# How many holds may run off the loop at once in the fixed mode; more are refused with 503.
offload.max.in.flight=1000
```

- `offload.max.in.flight=1000` is how many holds may run off the loop at once in the fixed mode. More are answered `503 busy` (1 to 1,000).

## Try it yourself

Run these from the repository folder.

**1. Print a file and its keys.** `cat config/event-loop.properties` shows the four keys, and `grep -c '^[a-z]' config/simple.properties` counts the lines that start with a letter, which are the setting lines and not the comments: it printed `19`, the nineteen keys. (I ran both.)

**2. Break a file.** I copied `config/simple.properties`, changed the step list to `200,abc` and deleted `pool.connections`, then ran `./gradlew -q benchmark --args='<path of my copy> build/bench'`. It printed every problem at once, and nothing started:

```text
invalid setting: load.steps.rps must list whole numbers, but one was abc
invalid setting: pool.connections is missing
```

**3. Break the census rule.** `census.at.rps` must be one of the steps. `BenchmarkSettings` refuses a file where it is not, with `census.at.rps must be one of the steps: <value>`. (`OpenModelLoadTest` checks it: it expects `census.at.rps must be one of the steps: 999`.)

**4. Run the shortest benchmark.** `./gradlew -q benchmark --args='config/unbounded.properties build/bench'` takes about 30 seconds. See `csv-and-svg-output.md` for its files.

## Mistakes people make

- **A trailing space.** `platform.workers=64 ` keeps the space in the raw value. `SettingsReader.text` strips it, so it is harmless here.
- **A comment at the end of a line.** `pool.connections=16 # sixteen` makes the value `16 # sixteen`, which is not a number. Put comments on their own lines.
- **A thousands separator.** `2,000` is not a number to `Integer.parseInt`. Write `2000`. (In a list, a comma separates items.)
- **A wrong unit.** `database.wait.millis=40` is milliseconds. 40 seconds would be `40000`. The unit is in the key.
- **A missing key.** There is no default. The program prints `<key> is missing` and exits with 2.
- **A census rate that is not a step.** The census would never be taken, so the settings are refused.
- **Copying a file and forgetting the database name.** Two files with the same `database.url` run on separate databases anyway (the mode is appended), but keep names distinct for clarity.

## Check yourself

1. **What does `#` do in the middle of a line?** Nothing special: it is part of the value. A comment must be on its own line.
2. **What happens if a key is missing?** The reader records `<key> is missing` and the program exits with status 2 after printing every problem.
3. **Why does `database.url` end with a hyphen?** The benchmark appends the mode name, so each mode has its own in-memory database.
4. **Where does 187 in `production.properties` come from?** 300 ms x 625 holds per second (25 connections / 0.040 s) = 187.5, rounded down to 187.
5. **Why must `census.at.rps` be one of the steps?** The census is taken during that step. If no step has that rate, no census would be taken.

## Further reading

Official documentation only, for JDK 25:

- `java.util.Properties`, with the exact rules of the format in the `load(Reader)` method: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
