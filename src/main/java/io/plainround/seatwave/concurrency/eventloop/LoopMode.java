package io.plainround.seatwave.concurrency.eventloop;

/** Where the event-loop server runs the blocking part of each request. */
public enum LoopMode {
  /** On the loop thread itself: the mistake Lab 3 reproduces. */
  BLOCKING_IN_LOOP,
  /** On a virtual thread, with the reply handed back to the loop: the fix. */
  OFFLOADED
}
