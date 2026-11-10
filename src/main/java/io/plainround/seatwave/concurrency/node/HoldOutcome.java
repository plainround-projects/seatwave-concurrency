package io.plainround.seatwave.concurrency.node;

/** Every way a hold can end, each with the HTTP status SeatWave answers with. */
public enum HoldOutcome {
  /** The seat is now held for this fan. */
  CREATED(201),
  /** Another fan holds or owns the seat. A correct, expected answer. */
  SEAT_TAKEN(409),
  /** The executor's bound was reached: refused at the door, before any work. */
  QUEUE_FULL(503),
  /** The hold waited longer than the queue wait limit before a worker started it. */
  QUEUE_WAIT_EXCEEDED(503),
  /** No database connection became free within the borrow limit. */
  POOL_TIMEOUT(503),
  /** The database failed or timed out; the seat state is unknown, so the hold is refused. */
  DATABASE_FAILED(503),
  /** The worker was interrupted, which happens only while the node shuts down. */
  INTERRUPTED(503);

  private final int httpStatus;

  HoldOutcome(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  /**
   * Returns the HTTP status for this outcome.
   *
   * @return 201, 409 or 503
   */
  public int httpStatus() {
    return httpStatus;
  }

  /**
   * Tells whether the answer is a correct one in Module 2's sense: 201, or 409 for a seat taken.
   *
   * @return true for {@link #CREATED} and {@link #SEAT_TAKEN}
   */
  public boolean isGood() {
    return this == CREATED || this == SEAT_TAKEN;
  }
}
