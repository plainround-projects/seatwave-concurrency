package io.plainround.seatwave.concurrency.node;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The seats table of SeatWave version 0 and its one conditional update.
 *
 * <p>The rule "a seat is held by at most one fan" lives in the {@code WHERE} clause of one {@code
 * UPDATE}: the database checks and changes the row in one atomic step, so two racing holds can
 * never both see "1 row updated". Stateless apart from its settings; safe to share.
 */
public final class SeatStore {

  /** The demo event, as in SeatWave version 0. */
  public static final long DEMO_EVENT_ID = 1L;

  /** Sections A to T, as in SeatWave version 0. */
  public static final int SECTION_COUNT = 20;

  /** Seats in each section, as in SeatWave version 0: 20,000 seats in all. */
  public static final int SEATS_PER_SECTION = 1000;

  // A hold lasts 300 seconds (Module 1 and Module 2).
  private static final Duration HOLD_DURATION = Duration.ofSeconds(300);
  private static final int SEED_BATCH_SIZE = 1000;
  private static final char FIRST_SECTION = 'A';

  private static final String HOLD_SQL =
      """
      UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?
      WHERE event_id = ? AND seat_label = ?
        AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
      """;

  private final Clock clock;
  private final int statementTimeoutSeconds;

  /**
   * Creates the store.
   *
   * @param clock the source of "now" for hold expiry; a fixed clock in tests
   * @param statementTimeoutSeconds the JDBC query timeout of every statement
   */
  public SeatStore(Clock clock, int statementTimeoutSeconds) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (statementTimeoutSeconds < 1) {
      throw new IllegalArgumentException("the statement timeout must be 1 second or more");
    }
    this.statementTimeoutSeconds = statementTimeoutSeconds;
  }

  /**
   * Returns the label of one seat by its position, so a load generator can pick seats.
   *
   * @param seatIndex from 0 to 19,999
   * @return the label, such as {@code A-1} for 0 and {@code T-1000} for 19,999
   */
  public static String seatLabel(int seatIndex) {
    if (seatIndex < 0 || seatIndex >= SECTION_COUNT * SEATS_PER_SECTION) {
      throw new IllegalArgumentException("no such seat: " + seatIndex);
    }
    char section = (char) (FIRST_SECTION + seatIndex / SEATS_PER_SECTION);
    return String.format(Locale.ROOT, "%c-%d", section, seatIndex % SEATS_PER_SECTION + 1);
  }

  /**
   * Creates the tables and the demo event with 20,000 available seats.
   *
   * @param connection an open connection to an empty database
   * @throws SQLException if a statement fails
   */
  public void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
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
    try (PreparedStatement event =
        connection.prepareStatement(
            "INSERT INTO events (event_id, name, starts_at) VALUES (?, ?, ?)")) {
      event.setLong(1, DEMO_EVENT_ID);
      event.setString(2, "Arena Night");
      event.setLong(3, clock.millis());
      event.executeUpdate();
    }
    seedSeats(connection);
  }

  /**
   * Tries to hold one seat.
   *
   * @param connection a borrowed connection
   * @param request the validated request
   * @return {@link HoldOutcome#CREATED} or {@link HoldOutcome#SEAT_TAKEN}
   * @throws SQLException if the database fails or the statement times out
   */
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
}
