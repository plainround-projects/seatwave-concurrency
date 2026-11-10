package io.plainround.seatwave.concurrency.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.concurrency.node.TestNodes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The event loop with a blocking call inside it, and with the call moved off it. The blocking call
 * waits on a latch the test controls, so the tests never depend on how long anything takes.
 */
class EventLoopServerTest {

  private static final String BLOCKED_SEAT = "A-1";
  private static final Duration QUIET_PERIOD = Duration.ofMillis(200);
  private static final int ONE_IN_FLIGHT = 1;
  private static final int MANY_IN_FLIGHT = 10;
  private static final int SOCKET_TIMEOUT_MILLIS = 5000;

  @Test
  void aBlockingCallInsideTheLoopStallsEveryOtherConnection() throws Exception {
    Gate gate = new Gate();
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.BLOCKING_IN_LOOP, gate::hold, MANY_IN_FLIGHT, TestNodes.LONG_WAIT);
        Client blocked = new Client(server);
        Client other = new Client(server)) {
      blocked.send("HOLD " + BLOCKED_SEAT);
      gate.awaitStarted();

      other.send("HOLD B-2");

      await().during(QUIET_PERIOD).atMost(TestNodes.LONG_WAIT).until(() -> !other.hasReply());
      gate.open();
      assertThat(blocked.reply()).isEqualTo("201 " + BLOCKED_SEAT);
      assertThat(other.reply()).isEqualTo("201 B-2");
    }
  }

  @Test
  void movingTheCallOffTheLoopKeepsOtherConnectionsAnswered() throws Exception {
    Gate gate = new Gate();
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.OFFLOADED, gate::hold, MANY_IN_FLIGHT, TestNodes.LONG_WAIT);
        Client blocked = new Client(server);
        Client other = new Client(server)) {
      blocked.send("HOLD " + BLOCKED_SEAT);
      gate.awaitStarted();

      other.send("HOLD B-2");

      assertThat(other.reply()).isEqualTo("201 B-2");
      assertThat(blocked.hasReply()).isFalse();
      gate.open();
      assertThat(blocked.reply()).isEqualTo("201 " + BLOCKED_SEAT);
    }
  }

  @Test
  void refusesWith503WhenTheOffloadBoundIsReached() throws Exception {
    Gate gate = new Gate();
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.OFFLOADED, gate::hold, ONE_IN_FLIGHT, TestNodes.LONG_WAIT);
        Client blocked = new Client(server);
        Client other = new Client(server)) {
      blocked.send("HOLD " + BLOCKED_SEAT);
      gate.awaitStarted();

      other.send("HOLD B-2");

      assertThat(other.reply()).isEqualTo("503 busy");
      gate.open();
      assertThat(blocked.reply()).isEqualTo("201 " + BLOCKED_SEAT);
    }
  }

  @Test
  void answersAnUnknownCommandWith400() throws Exception {
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.BLOCKING_IN_LOOP,
                seat -> "201 " + seat,
                ONE_IN_FLIGHT,
                TestNodes.LONG_WAIT);
        Client client = new Client(server)) {
      client.send("DROP TABLE seats");

      assertThat(client.reply()).isEqualTo("400 bad request");
    }
  }

  @Test
  void closesAConnectionThatSendsALineLongerThanTheLimit() throws Exception {
    try (EventLoopServer server =
            new EventLoopServer(
                LoopMode.BLOCKING_IN_LOOP,
                seat -> "201 " + seat,
                ONE_IN_FLIGHT,
                TestNodes.LONG_WAIT);
        Client client = new Client(server)) {
      client.sendWithoutLineEnd("H".repeat(EventLoopServer.MAX_LINE_BYTES + 1));

      assertThat(client.reply()).isNull();
    }
  }

  @Test
  void theLabMeasuresOneWaitPerClient() throws Exception {
    EventLoopLab.Settings settings =
        new EventLoopLab.Settings(3, Duration.ZERO, TestNodes.LONG_WAIT, MANY_IN_FLIGHT);

    List<Duration> blocking = EventLoopLab.run(LoopMode.BLOCKING_IN_LOOP, settings);
    List<Duration> offloaded = EventLoopLab.run(LoopMode.OFFLOADED, settings);

    assertThat(blocking).hasSize(3).isSorted();
    assertThat(offloaded).hasSize(3).isSorted();
  }

  /** Blocking work that waits until the test opens it, and says when it has started. */
  private static final class Gate {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch opened = new CountDownLatch(1);

    String hold(String seatLabel) throws InterruptedException {
      if (BLOCKED_SEAT.equals(seatLabel)) {
        started.countDown();
        if (!opened.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("the test never opened the gate");
        }
      }
      return "201 " + seatLabel;
    }

    void awaitStarted() throws InterruptedException {
      assertThat(started.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    void open() {
      opened.countDown();
    }
  }

  /** One blocking test client on the loopback address. Used by one test thread. */
  private static final class Client implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;

    Client(EventLoopServer server) throws IOException {
      socket = new Socket();
      socket.connect(server.address(), SOCKET_TIMEOUT_MILLIS);
      socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
      in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    void send(String line) throws IOException {
      sendWithoutLineEnd(line + "\n");
    }

    void sendWithoutLineEnd(String text) throws IOException {
      socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
    }

    boolean hasReply() throws IOException {
      return in.ready();
    }

    String reply() throws IOException {
      return in.readLine();
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
