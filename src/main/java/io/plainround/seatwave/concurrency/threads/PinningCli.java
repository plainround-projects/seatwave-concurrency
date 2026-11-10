package io.plainround.seatwave.concurrency.threads;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Command-line entry point of the carrier-pinning demo. It takes no settings: the delay it shows
 * comes from the blockers, which are part of the demo, not a tunable. Exit status 1 means the demo
 * failed.
 */
public final class PinningCli {

  private static final int EXIT_RUN_FAILED = 1;
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  private static final double NANOS_PER_MILLI = 1e6;

  private PinningCli() {}

  /**
   * Runs the demo: first with blockers that unmount, then with blockers that pin.
   *
   * @param arguments none
   */
  public static void main(String[] arguments) {
    try {
      int carriers = Runtime.getRuntime().availableProcessors();
      System.out.printf(
          Locale.ROOT,
          "%d carriers; %d virtual threads each block for %d ms, then an unrelated virtual task"
              + " is started.%n",
          carriers,
          carriers,
          CarrierPinning.BLOCKS_FOR.toMillis());
      print("blocked in Thread.sleep (unmounts)", CarrierPinning.delayWhileSleeping(WAIT_LIMIT));
      print("blocked in a class initializer (pins)", CarrierPinning.delayWhilePinned(WAIT_LIMIT));
    } catch (ExecutionException | TimeoutException failed) {
      System.err.println("the demo failed: " + failed);
      System.exit(EXIT_RUN_FAILED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the demo was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }

  private static void print(String blockers, Duration delay) {
    System.out.printf(
        Locale.ROOT,
        "%-38s the unrelated task waited %6.1f ms to start%n",
        blockers + ":",
        delay.toNanos() / NANOS_PER_MILLI);
  }
}
