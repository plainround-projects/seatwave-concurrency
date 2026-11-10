package io.plainround.seatwave.concurrency.eventloop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lab 3's client side: many fans connect, then all send one hold at the same moment, and each fan's
 * wait is measured. Stateless; each call opens and closes its own server and sockets.
 */
public final class EventLoopLab {

  private EventLoopLab() {}

  /**
   * The settings of one lab run.
   *
   * @param clients how many fans connect and send at once
   * @param workTime how long the blocking part of each request takes
   * @param socketTimeout the longest any socket call may block
   * @param offloadMaxInFlight how many requests may run off the loop at once
   */
  public record Settings(
      int clients, Duration workTime, Duration socketTimeout, int offloadMaxInFlight) {

    /** Creates the settings. */
    public Settings {
      Objects.requireNonNull(workTime, "workTime");
      Objects.requireNonNull(socketTimeout, "socketTimeout");
      if (clients < 1 || offloadMaxInFlight < 1 || socketTimeout.isZero()) {
        throw new IllegalArgumentException("clients, bound and timeout must be positive");
      }
    }
  }

  /**
   * Runs one mode: every client sends at once, and the time to each reply is measured.
   *
   * @param mode where the server runs the blocking work
   * @param settings the client count and the times
   * @return each client's wait from the shared start, sorted from shortest to longest
   * @throws IOException if a socket fails or times out
   * @throws InterruptedException if interrupted while waiting
   */
  public static List<Duration> run(LoopMode mode, Settings settings)
      throws IOException, InterruptedException {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(settings, "settings");
    BlockingWork databaseCall =
        seatLabel -> {
          // The blocking part: stands for a database call that takes the work time.
          Thread.sleep(settings.workTime());
          return "201 " + seatLabel;
        };
    try (EventLoopServer server =
            new EventLoopServer(
                mode, databaseCall, settings.offloadMaxInFlight(), settings.socketTimeout());
        ExecutorService fans = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch connected = new CountDownLatch(settings.clients());
      CountDownLatch startGate = new CountDownLatch(1);
      List<Future<Duration>> waits = new ArrayList<>();
      for (int clientIndex = 0; clientIndex < settings.clients(); clientIndex++) {
        String seatLabel = "A-" + (clientIndex + 1);
        waits.add(
            fans.submit(() -> oneFan(server.address(), seatLabel, settings, connected, startGate)));
      }
      if (!connected.await(settings.socketTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
        throw new IOException("not every client connected within the socket timeout");
      }
      startGate.countDown();
      return collect(waits, settings);
    }
  }

  private static Duration oneFan(
      InetSocketAddress address,
      String seatLabel,
      Settings settings,
      CountDownLatch connected,
      CountDownLatch startGate)
      throws IOException, InterruptedException {
    int timeoutMillis = Math.toIntExact(settings.socketTimeout().toMillis());
    try (Socket socket = new Socket()) {
      socket.connect(address, timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      socket.setTcpNoDelay(true);
      OutputStream out = socket.getOutputStream();
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      connected.countDown();
      if (!startGate.await(settings.socketTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
        throw new IOException("the start signal did not come within the socket timeout");
      }
      long sentNanos = System.nanoTime();
      out.write(("HOLD " + seatLabel + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      String reply = in.readLine();
      if (reply == null || !reply.startsWith("201 ")) {
        throw new IOException("unexpected reply: " + reply);
      }
      return Duration.ofNanos(System.nanoTime() - sentNanos);
    }
  }

  private static List<Duration> collect(List<Future<Duration>> waits, Settings settings)
      throws IOException, InterruptedException {
    List<Duration> measured = new ArrayList<>();
    for (Future<Duration> wait : waits) {
      try {
        measured.add(wait.get(settings.socketTimeout().toNanos(), TimeUnit.NANOSECONDS));
      } catch (ExecutionException failed) {
        throw new IOException("a client failed", failed.getCause());
      } catch (TimeoutException tooSlow) {
        throw new IOException("a client got no reply within the socket timeout", tooSlow);
      }
    }
    measured.sort(null);
    return List.copyOf(measured);
  }
}
