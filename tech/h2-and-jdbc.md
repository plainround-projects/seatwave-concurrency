# H2 and JDBC: the embedded database and the way Java talks to it

## What it is

A **database** is a program that stores data in tables and answers questions about it in a language called **SQL**. A **table** is a grid: each **row** is one record and each **column** is one kind of fact. SQL (Structured Query Language) has statements such as `CREATE TABLE`, `INSERT`, `UPDATE` and `SELECT`.

**H2** is a database written in Java. It is **embedded**: it runs inside your own program, as a library, instead of as a separate server. It can keep its data in memory only, so nothing is written to disk and everything vanishes when the program ends.

**JDBC** (Java Database Connectivity) is the standard set of Java interfaces for talking to any database: `Connection`, `Statement`, `PreparedStatement`, `SQLException`. Code written against JDBC does not care which database is behind it. A **driver** is the library that implements these interfaces for one database. H2 2.5.252 ships its own driver, found automatically when the library is on the class path.

## Why this repository uses it

The node must keep the rule of Module 1: **a seat is held by at most one fan**. That rule must hold even when many holds race for the same seat on many threads. The repository enforces it in the database, with one conditional `UPDATE`, as a real node would. H2 gives a real SQL database with no installation and no server to start, so the benchmark runs anywhere. It is used in memory only: `NodeSettings` refuses any URL that does not start with `jdbc:h2:mem:`, so the benchmark never writes a file and never reaches another host.

A real SeatWave database would be on another machine, and every hold would wait for the network, the disk and locks. Embedded H2 answers in well under a millisecond, so the node models the remote database by keeping the connection for `database.wait.millis` (40 ms) after the statement. The statement still runs, so the rule is still enforced by the database and tested.

The dependency is declared as `h2 = "2.5.252"` in `gradle/libs.versions.toml` and `implementation(libs.h2)` in `build.gradle.kts`. The license is MPL 2.0 or EPL 1.0.

## Where it appears

| File | What it does with JDBC or H2 |
|---|---|
| `node/SeatStore.java` | Creates the two tables, seeds 20,000 seats, runs the conditional `UPDATE`. Every SQL statement of the repository is here. |
| `node/ConnectionPool.java` | Lends out a fixed set of `Connection` objects with a bounded wait, and closes them. |
| `node/SeatWaveNode.java` | Opens the connections with `DriverManager.getConnection(url)`. |
| `node/HoldEndpoint.java` | Borrows a connection, calls `SeatStore.hold`, catches `SQLException`. |
| `node/NodeSettings.java` | Holds the URL and refuses anything that is not `jdbc:h2:mem:`. |
| `config/*.properties` | `database.url=jdbc:h2:mem:seatwave-simple-` and its siblings, and `database.statement.timeout.seconds=2`. |

## How it works underneath

**A connection.** To talk to a database, a program opens a **connection**: a session with its own state. A connection can run one statement at a time. With a database on another machine, opening a connection is slow (a network handshake, a login), so programs open some at the start and **pool** them: keep them open and lend them out. A **connection pool** also limits how many statements run at once, which is what a database can bear. `ConnectionPool` in this repository is that pool, written by hand. A hold borrows a connection, uses it, and gives it back.

**The URL.** `jdbc:h2:mem:seatwave-simple-platform` has three parts. `jdbc:h2:` says "JDBC, the H2 driver". `mem:` says "in memory". `seatwave-simple-platform` is the **name** of the database. A named in-memory database is shared by every connection in the same JVM that uses the same name, and it lives as long as at least one connection is open. When the last connection closes, the database is gone. `SeatWaveNode.start` opens all the pool's connections with the same URL, so they see one database, and `BenchmarkRun` appends the mode name so the `platform` node and the `virtual` node have separate databases.

**A statement and a prepared statement.** A `Statement` runs SQL text as written. A `PreparedStatement` takes SQL with holes, written `?`, and you fill each hole with a value (`setString(2, customer)`). The values travel separately from the SQL text, so a customer name like `x'; DROP TABLE seats; --` is only text and can never become SQL. This protects against **SQL injection**. It also lets the database parse the statement once.

**A transaction and atomic.** A **transaction** is a group of changes that the database applies all together or not at all. **Atomic** means "indivisible": no other connection can see it half done. By default a JDBC connection is in **auto-commit** mode, so every statement is its own transaction: it takes effect when it completes. One `UPDATE` is therefore atomic.

**Why one conditional `UPDATE` is the whole hold.** Look at the wrong way first: read the seat (`SELECT`), see it is available in Java, then write (`UPDATE`). Between the read and the write another thread can do the same, and both believe they won. That is a **race**. The right way is to put the condition in the `UPDATE` itself:

```text
UPDATE seats SET status = 'HELD', ... WHERE seat is this one AND (it is available OR its hold has expired)
```

The database checks the condition and changes the row in one step, and reports **how many rows it changed**. 1 means this statement took the seat. 0 means the condition was false: someone else has it. No two holds can both see 1. The Java code only reads the number.

**The query timeout.** `setQueryTimeout(2)` tells the driver to cancel the statement and throw `SQLException` if it runs longer than 2 seconds. It is the fourth limit of the node: a hold that cannot get a row lock in time fails instead of waiting without limit.

## Walkthrough of this repository's use

### The two tables

```java
      statement.execute(
          """
          CREATE TABLE events (event_id BIGINT PRIMARY KEY, name VARCHAR(120) NOT NULL,
            starts_at BIGINT NOT NULL)\
          """);
      // The status column is the single source of truth for a seat. Java never decides alone.
      statement.execute(
          """
          CREATE TABLE seats (event_id BIGINT NOT NULL, seat_label VARCHAR(16) NOT NULL,
            section VARCHAR(4) NOT NULL, status VARCHAR(12) NOT NULL, hold_id VARCHAR(40),
            hold_customer VARCHAR(64), hold_expires_at BIGINT, booking_id VARCHAR(40),
            PRIMARY KEY (event_id, seat_label))\
          """);
    }
```


- `statement.execute(sql)` runs a statement that is not a query. The SQL sits in a **text block** (`"""`), so it can span lines. The `\` at the end of the last SQL line joins it to the closing `"""` without adding a newline.
- `CREATE TABLE events (event_id BIGINT PRIMARY KEY, name VARCHAR(120) NOT NULL, starts_at BIGINT NOT NULL)` creates a table with three columns. `BIGINT` is a 64-bit whole number. `VARCHAR(120)` is text up to 120 characters. `PRIMARY KEY` says the column is unique and identifies a row, and the database keeps an index on it. `NOT NULL` forbids an empty value.
- `CREATE TABLE seats (...)` has one row per seat. `event_id` and `seat_label` say which seat. `section` is the letter. `status` is `AVAILABLE` or `HELD` (and `BOOKED` in the full SeatWave). `hold_id`, `hold_customer` and `hold_expires_at` describe the current hold and may be empty (no `NOT NULL`). `booking_id` is for a later module. `PRIMARY KEY (event_id, seat_label)` makes the **pair** unique, and gives the database an index to find a seat's row fast, which the `WHERE` of the hold uses.
- The comment above the second statement says the status column is the single source of truth for a seat. Java never decides alone.

### The demo event

```java
    try (PreparedStatement event =
        connection.prepareStatement(
            "INSERT INTO events (event_id, name, starts_at) VALUES (?, ?, ?)")) {
      event.setLong(1, DEMO_EVENT_ID);
      event.setString(2, "Arena Night");
      event.setLong(3, clock.millis());
      event.executeUpdate();
    }
```


- `INSERT INTO events (event_id, name, starts_at) VALUES (?, ?, ?)` adds one row. The three `?` are filled by `setLong(1, ...)`, `setString(2, "Arena Night")` and `setLong(3, clock.millis())`. The numbers count from **1**, not 0. `executeUpdate()` runs it and returns the number of rows changed (1).
- `try (PreparedStatement event = ...)` closes the statement at the end of the block.

### Filling the seats

```java
  private static void seedSeats(Connection connection) throws SQLException {
    try (PreparedStatement seat =
        connection.prepareStatement(
            "INSERT INTO seats (event_id, seat_label, section, status) VALUES (?, ?, ?, 'AVAILABLE')")) {
      int seatCount = SECTION_COUNT * SEATS_PER_SECTION;
      for (int seatIndex = 0; seatIndex < seatCount; seatIndex++) {
        String label = seatLabel(seatIndex);
        seat.setLong(1, DEMO_EVENT_ID);
        seat.setString(2, label);
        seat.setString(3, label.substring(0, 1));
        seat.addBatch();
        // Batches keep the seeding to a few round trips without one huge statement in memory.
        if ((seatIndex + 1) % SEED_BATCH_SIZE == 0) {
          seat.executeBatch();
        }
      }
      seat.executeBatch();
    }
  }
```


- `INSERT INTO seats (event_id, seat_label, section, status) VALUES (?, ?, ?, 'AVAILABLE')` adds one seat. The status is written in the SQL because it is the same for every seat, and the other columns stay empty.
- `seat.addBatch()` stores the filled statement, and `executeBatch()` sends all stored ones together. 20,000 seats go in 20 batches of 1,000. The comment says why: a few round trips and no huge statement in memory. With a database on another machine, 20,000 separate round trips would be slow.

### The hold

```java
  private static final String HOLD_SQL =
      """
      UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?
      WHERE event_id = ? AND seat_label = ?
        AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
      """;
```

```java
  public HoldOutcome hold(Connection connection, HoldRequest request) throws SQLException {
    long nowMillis = clock.millis();
    try (PreparedStatement update = connection.prepareStatement(HOLD_SQL)) {
      // A hold that cannot get its row lock in time fails instead of waiting without limit.
      update.setQueryTimeout(statementTimeoutSeconds);
      update.setString(1, UUID.randomUUID().toString());
      update.setString(2, request.customer());
      update.setLong(3, nowMillis + HOLD_DURATION.toMillis());
      update.setLong(4, request.eventId());
      update.setString(5, request.seatLabel());
      update.setLong(6, nowMillis);
      return update.executeUpdate() == 1 ? HoldOutcome.CREATED : HoldOutcome.SEAT_TAKEN;
    }
  }
```


- `UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?` changes the seat's status and fills the three hold columns. The three `?` are the new hold id (`UUID.randomUUID()`, a random 128-bit identifier), the customer, and the expiry (now plus 300,000 ms, five minutes).
- `WHERE event_id = ? AND seat_label = ?` selects the one seat.
- `AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))` is the rule. The row matches only if the seat is available, or it is held and the hold has already expired (its expiry is at or before now). A seat that is held by a live hold, or booked, does not match, so the `UPDATE` changes nothing.
- `update.executeUpdate() == 1` is the answer: one row changed means `CREATED` (HTTP 201), zero means `SEAT_TAKEN` (409).
- `update.setQueryTimeout(statementTimeoutSeconds)` sets the 2-second limit.
- The six `set...` calls match the six `?` in order: hold id, customer, expiry, event, seat label, now.

**What I ran to check the rule.** In a scratch program (not part of the repository) I created the `seats` table from the statement above, inserted one seat `A-1` as `AVAILABLE`, and ran the hold statement three times:

```text
first hold  : rows updated = 1
second hold : rows updated = 0
after expiry: rows updated = 1
```

The first hold got the seat. The second found it held and changed nothing. The third, run at a time 300 seconds after the first, found the hold expired and took the seat over: the row then showed `HELD fan_3`.

The repository's own test for the race is `HoldEndpointTest.letsExactlyOneOfManyRacingHoldsWinOneSeat`: 50 holds race for one seat on many threads, and exactly one gets 201. `letsAnotherFanHoldASeatOnceTheFirstHoldExpired` is the third case above.

### Opening connections and pooling them

```java
  public static SeatWaveNode start(NodeSettings settings, Clock clock) throws SQLException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(clock, "clock");
    List<Connection> connections = new ArrayList<>();
    try {
      for (int connectionIndex = 0;
          connectionIndex < settings.poolConnections();
          connectionIndex++) {
        connections.add(DriverManager.getConnection(settings.databaseUrl()));
      }
      new SeatStore(clock, settings.statementTimeoutSeconds()).createSchema(connections.getFirst());
    } catch (SQLException failed) {
      closeQuietlyAfter(failed, connections);
      throw failed;
    }
    ConnectionPool pool = new ConnectionPool(connections);
    Workers workers =
        switch (settings.mode()) {
          case PLATFORM ->
              new PlatformWorkers(settings.platformWorkers(), settings.executorQueueBound());
          case VIRTUAL -> new VirtualWorkers(settings.virtualMaxInFlight());
        };
    return new SeatWaveNode(settings, workers, pool, clock);
  }
```


- `DriverManager.getConnection(settings.databaseUrl())` opens one connection from the URL. It is called once per pool connection (`pool.connections`, 16 in `config/simple.properties`).
- `createSchema(connections.getFirst())` creates the tables once, through the first connection. The others see the same in-memory database because the URL has the same name.
- If any step fails, the connections already opened are closed, and the `SQLException` goes on.
- `new ConnectionPool(connections)` hands the open connections to the pool.

How a hold uses the pool (`HoldEndpoint.serve`):

```java
    try {
      Optional<ConnectionPool.Lease> borrowed = pool.borrow(limits.poolBorrowLimit());
      long borrowedNanos = nanoClock.getAsLong();
      long poolWaitNanos = borrowedNanos - startedNanos;
      if (borrowed.isEmpty()) {
        return new HoldReply(HoldOutcome.POOL_TIMEOUT, queueWaitNanos, poolWaitNanos, 0);
      }
      try (ConnectionPool.Lease lease = borrowed.get()) {
        HoldOutcome outcome = seatStore.hold(lease.connection(), request);
        waitForRemoteDatabase();
        long workNanos = nanoClock.getAsLong() - borrowedNanos;
        return new HoldReply(outcome, queueWaitNanos, poolWaitNanos, workNanos);
      }
    } catch (SQLException databaseFailed) {
      // The seat's state is unknown, so the only safe answer is a refusal the fan can retry.
      return new HoldReply(HoldOutcome.DATABASE_FAILED, queueWaitNanos, 0, 0);
    } catch (InterruptedException interrupted) {
      // Only a shutdown interrupts a worker: keep the flag for the executor and stop this hold.
      Thread.currentThread().interrupt();
      return new HoldReply(HoldOutcome.INTERRUPTED, queueWaitNanos, 0, 0);
    }
```


`pool.borrow(limit)` waits at most the borrow limit. `try (ConnectionPool.Lease lease = borrowed.get())` returns the connection at the end of the block. `catch (SQLException databaseFailed)` handles any database failure with one answer: the seat's state is unknown, so the hold is refused with 503 and the fan can retry. See `java-node-walkthrough.md` for every line.

### Closing

`ConnectionPool.close` closes every connection, and keeps closing if one fails, attaching the later failures to the first with `addSuppressed`. When the last connection to an in-memory H2 database closes, the database is gone: nothing was written anywhere.

### The URL and the timeout in the settings

```properties
database.statement.timeout.seconds=2
database.url=jdbc:h2:mem:seatwave-simple-
```

`database.url` ends in a hyphen because `BenchmarkRun` appends the mode name. `NodeSettings` refuses a value that does not start with `jdbc:h2:mem:`. See `java-properties.md`.

## Try it yourself

Run these from the repository folder.

**1. The race test.** `./gradlew test --tests '*HoldEndpointTest' --console=plain` printed six lines ending in `PASSED`, among them `letsExactlyOneOfManyRacingHoldsWinOneSeat()`, `letsAnotherFanHoldASeatOnceTheFirstHoldExpired()` and `answers503WhenTheDatabaseFails()`. It ended with `BUILD SUCCESSFUL in 2s`.

**2. The URL rule.** `./gradlew test --tests '*NodeValuesTest' --console=plain` printed eight `PASSED` lines, among them `refusesADatabaseThatIsNotInMemory()` and `labelsTheFirstAndLastSeat()`.

**3. See the pool's effect in the benchmark.** `./gradlew -q benchmark --args='config/unbounded.properties build/bench'` ends with a census where 16 workers are `in HoldEndpoint.waitForRemoteDatabase` (holding a connection) and 48 are `in ConnectionPool.borrow` (waiting for one). That is 16 connections for 64 threads.

**4. Which statements exist.** `grep -rn "prepareStatement\|createStatement" src/main` printed four lines, all in `SeatStore`: one `createStatement` (it runs the two `CREATE TABLE`s) and three `prepareStatement` calls (the events insert, the hold and the seats insert). That is every SQL statement in the repository.

## Mistakes people make

- **Read, decide in Java, write.** Two threads can both read "available". The conditional `UPDATE` makes the check and the change one atomic step.
- **Building SQL by joining text.** `"... WHERE customer = '" + name + "'"` lets a name become SQL. `PreparedStatement` keeps values apart.
- **A statement with no timeout.** A lock wait can last for ever. The hold sets `setQueryTimeout`.
- **Opening a connection per request.** With a real database it is slow. Open a few, lend them out.
- **Not giving a connection back.** A leaked connection shrinks the pool for good. The `Lease` is closed by try-with-resources.
- **An unbounded wait for a connection.** The pool waits at most `pool.borrow.limit.millis`.
- **Counting JDBC columns from 0.** Parameter numbers start at 1.
- **Expecting an in-memory database to survive.** It disappears when its last connection closes. Here that is the intention.

## Check yourself

1. **What does `executeUpdate()` return for the hold, and what do 0 and 1 mean?** The number of rows changed. 1: this hold took the seat (201). 0: the `WHERE` did not match, so the seat is held or booked by someone else (409).
2. **Why is the hold one statement and not a read followed by a write?** Between a read and a write another thread could take the seat. One statement makes the check and the change atomic.
3. **What does the `?` do, and what attack does it prevent?** It is a hole filled with a value that travels apart from the SQL text, so the value cannot become SQL. It prevents SQL injection.
4. **When does a named in-memory H2 database disappear?** When the last connection to it closes.
5. **Why does `HoldEndpoint` keep the connection for `database.wait.millis` after the statement?** To model a database on another machine, whose round trip and lock waits embedded H2 does not have. Keeping the connection borrowed is what makes the pool the limit.

## Further reading

Official documentation only:

- H2 features (in-memory databases, the URL, `DB_CLOSE_DELAY`), version 2.5.252: https://h2database.com/html/features.html
- H2 SQL grammar (`UPDATE`, `CREATE TABLE`): https://h2database.com/html/grammar.html
- The JDBC interfaces in the Java SE 25 API documentation, `PreparedStatement`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/PreparedStatement.html
