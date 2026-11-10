package io.plainround.seatwave.concurrency.node;

/**
 * How a SeatWave node runs each hold: on a fixed pool of platform threads, or on its own virtual
 * thread.
 */
public enum ExecutorMode {
  /** A fixed pool of operating-system threads with a bounded queue in front of it. */
  PLATFORM,
  /** One new virtual thread per hold, with a bound on how many may be in flight. */
  VIRTUAL
}
