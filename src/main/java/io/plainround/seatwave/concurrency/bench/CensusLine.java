package io.plainround.seatwave.concurrency.bench;

import java.util.Objects;

/**
 * One line of a thread census: how many threads of one group are in one state at one place.
 *
 * @param group the thread-name prefix, such as {@code seatwave-worker-}
 * @param state the thread state, such as {@code TIMED_WAITING}
 * @param waitingIn the first SeatWave method on the stack, or the top frame if there is none
 * @param threads how many threads match
 */
public record CensusLine(String group, String state, String waitingIn, int threads) {

  /** Creates the line. */
  public CensusLine {
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(waitingIn, "waitingIn");
    if (threads < 1) {
      throw new IllegalArgumentException("a census line counts at least one thread");
    }
  }
}
