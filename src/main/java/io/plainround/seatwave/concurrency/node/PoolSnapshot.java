package io.plainround.seatwave.concurrency.node;

import java.util.Locale;

/**
 * The saturation counters of a connection pool at one moment.
 *
 * @param size connections in the pool
 * @param busy connections lent out now
 * @param waitingBorrowers threads waiting for a connection now
 * @param borrowTimeouts borrows that gave up since the pool started
 */
public record PoolSnapshot(int size, int busy, int waitingBorrowers, long borrowTimeouts) {

  /**
   * Creates the snapshot.
   *
   * @throws IllegalArgumentException if a count is negative or more connections are busy than exist
   */
  public PoolSnapshot {
    if (size < 1 || busy < 0 || busy > size || waitingBorrowers < 0 || borrowTimeouts < 0) {
      throw new IllegalArgumentException(
          String.format(
              Locale.ROOT,
              "impossible pool counters: size %d, busy %d, waiting %d, timeouts %d",
              size,
              busy,
              waitingBorrowers,
              borrowTimeouts));
    }
  }
}
