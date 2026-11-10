package io.plainround.seatwave.concurrency.eventloop;

/** The blocking part of one request, such as a database call. */
@FunctionalInterface
public interface BlockingWork {

  /**
   * Holds one seat and returns the reply line.
   *
   * @param seatLabel the seat named in the request
   * @return the reply, without the line end
   * @throws InterruptedException if the waiting thread is interrupted
   */
  String hold(String seatLabel) throws InterruptedException;
}
