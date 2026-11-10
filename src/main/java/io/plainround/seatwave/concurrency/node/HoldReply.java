package io.plainround.seatwave.concurrency.node;

import java.util.Objects;

/**
 * The answer to one hold, with where its time went inside the node.
 *
 * @param outcome how the hold ended
 * @param queueWaitNanos time from arrival to the start of the work: the executor queue
 * @param poolWaitNanos time spent waiting for a database connection; 0 if it never borrowed
 * @param workNanos time holding the connection: the statement and the database's own wait
 */
public record HoldReply(
    HoldOutcome outcome, long queueWaitNanos, long poolWaitNanos, long workNanos) {

  /**
   * Creates the reply.
   *
   * @throws IllegalArgumentException if a duration is negative
   */
  public HoldReply {
    Objects.requireNonNull(outcome, "outcome");
    if (queueWaitNanos < 0 || poolWaitNanos < 0 || workNanos < 0) {
      throw new IllegalArgumentException("a duration is never negative");
    }
  }

  /**
   * Creates a refusal that spent no time in the node.
   *
   * @param outcome the refusal
   * @return the reply
   */
  public static HoldReply refusedAtTheDoor(HoldOutcome outcome) {
    return new HoldReply(outcome, 0, 0, 0);
  }
}
