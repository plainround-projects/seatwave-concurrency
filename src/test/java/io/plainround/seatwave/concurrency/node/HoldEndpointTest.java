package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HoldEndpointTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
  private static final int RACING_FANS = 50;
  private static final Duration SHORT_LIMIT = Duration.ofMillis(20);
  private static final Duration QUEUE_WAIT_LIMIT = Duration.ofMillis(300);
  private static final int SERVICE_UNAVAILABLE = 503;
  // One second past the 300-second hold.
  private static final Duration JUST_AFTER_EXPIRY = Duration.ofSeconds(301);

  @Test
  void letsExactlyOneOfManyRacingHoldsWinOneSeat() throws Exception {
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.roomy(ExecutorMode.VIRTUAL), FIXED_CLOCK)) {
      List<CompletableFuture<HoldReply>> replies =
          IntStream.range(0, RACING_FANS)
              .mapToObj(fan -> node.endpoint().submit(new HoldRequest(1, "C-42", "fan_" + fan)))
              .toList();

      Map<HoldOutcome, Long> outcomes =
          replies.stream()
              .map(CompletableFuture::join)
              .collect(Collectors.groupingBy(HoldReply::outcome, Collectors.counting()));

      assertThat(outcomes)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(HoldOutcome.CREATED, 1L, HoldOutcome.SEAT_TAKEN, RACING_FANS - 1L));
    }
  }

  @Test
  void refusesWith503WhenNoConnectionFreesWithinTheBorrowLimit() throws Exception {
    NodeSettings settings = TestNodes.tiny(ExecutorMode.VIRTUAL);
    NodeSettings shortBorrow =
        new NodeSettings(
            settings.mode(),
            settings.platformWorkers(),
            settings.executorQueueBound(),
            settings.virtualMaxInFlight(),
            settings.queueWaitLimit(),
            settings.poolConnections(),
            SHORT_LIMIT,
            settings.databaseWait(),
            settings.statementTimeoutSeconds(),
            settings.databaseUrl());
    try (SeatWaveNode node = SeatWaveNode.start(shortBorrow, FIXED_CLOCK);
        ConnectionPool.Lease taken = node.pool().borrow(SHORT_LIMIT).orElseThrow()) {

      HoldReply reply = node.endpoint().submit(new HoldRequest(1, "A-1", "fan_1")).join();

      assertThat(reply.outcome()).isEqualTo(HoldOutcome.POOL_TIMEOUT);
      assertThat(reply.outcome().httpStatus()).isEqualTo(SERVICE_UNAVAILABLE);
      assertThat(reply.poolWaitNanos()).isGreaterThanOrEqualTo(SHORT_LIMIT.toNanos());
      assertThat(taken.connection()).isNotNull();
    }
  }

  @Test
  void refusesAtTheDoorWhenTheWorkerAndTheQueueAreTaken() throws Exception {
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.tiny(ExecutorMode.PLATFORM), FIXED_CLOCK)) {
      ConnectionPool.Lease taken = node.pool().borrow(SHORT_LIMIT).orElseThrow();
      CompletableFuture<HoldReply> first =
          node.endpoint().submit(new HoldRequest(1, "A-1", "fan_1"));
      await()
          .atMost(TestNodes.LONG_WAIT)
          .until(() -> node.pool().snapshot().waitingBorrowers() == 1);
      CompletableFuture<HoldReply> second =
          node.endpoint().submit(new HoldRequest(1, "A-2", "fan_2"));

      HoldReply third = node.endpoint().submit(new HoldRequest(1, "A-3", "fan_3")).join();

      assertThat(third).isEqualTo(HoldReply.refusedAtTheDoor(HoldOutcome.QUEUE_FULL));
      taken.close();
      assertThat(first.join().outcome()).isEqualTo(HoldOutcome.CREATED);
      assertThat(second.join().outcome()).isEqualTo(HoldOutcome.CREATED);
    }
  }

  @Test
  void refusesAHoldThatWaitedInTheQueueLongerThanTheLimit() throws Exception {
    AtomicLong fakeNanos = new AtomicLong();
    CountDownLatch release = new CountDownLatch(1);
    try (PlatformWorkers workers = new PlatformWorkers(1, 1);
        ConnectionPool pool = new ConnectionPool(seededConnections())) {
      HoldEndpoint endpoint = endpoint(workers, pool, fakeNanos::get);
      workers.tryExecute(() -> awaitQuietly(release));
      // Wait until the worker has taken the blocker, so the one queue slot is free for the hold.
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.queued() == 0);
      CompletableFuture<HoldReply> queued = endpoint.submit(new HoldRequest(1, "A-1", "fan_1"));

      fakeNanos.addAndGet(QUEUE_WAIT_LIMIT.toNanos() + 1);
      release.countDown();

      HoldReply reply = queued.join();
      assertThat(reply.outcome()).isEqualTo(HoldOutcome.QUEUE_WAIT_EXCEEDED);
      assertThat(reply.queueWaitNanos()).isEqualTo(QUEUE_WAIT_LIMIT.toNanos() + 1);
      assertThat(pool.snapshot().busy()).isZero();
    }
  }

  @Test
  void answers503WhenTheDatabaseFails() throws Exception {
    List<Connection> connections = TestNodes.connections(1);
    connections.getFirst().close();
    try (VirtualWorkers workers = new VirtualWorkers(1);
        ConnectionPool pool = new ConnectionPool(connections)) {
      HoldEndpoint endpoint = endpoint(workers, pool, System::nanoTime);

      HoldReply reply = endpoint.submit(new HoldRequest(1, "A-1", "fan_1")).join();

      assertThat(reply.outcome()).isEqualTo(HoldOutcome.DATABASE_FAILED);
      assertThat(pool.snapshot().busy()).isZero();
    }
  }

  @Test
  void letsAnotherFanHoldASeatOnceTheFirstHoldExpired() throws Exception {
    List<Connection> connections = seededConnections();
    Clock afterExpiry = Clock.offset(FIXED_CLOCK, JUST_AFTER_EXPIRY);
    HoldRequest firstFan = new HoldRequest(1, "B-9", "fan_1");
    HoldRequest secondFan = new HoldRequest(1, "B-9", "fan_2");
    try (ConnectionPool pool = new ConnectionPool(connections);
        ConnectionPool.Lease lease = pool.borrow(SHORT_LIMIT).orElseThrow()) {
      SeatStore now = new SeatStore(FIXED_CLOCK, 1);
      SeatStore later = new SeatStore(afterExpiry, 1);

      HoldOutcome first = now.hold(lease.connection(), firstFan);
      HoldOutcome tooEarly = now.hold(lease.connection(), secondFan);
      HoldOutcome afterTheHoldExpired = later.hold(lease.connection(), secondFan);

      assertThat(first).isEqualTo(HoldOutcome.CREATED);
      assertThat(tooEarly).isEqualTo(HoldOutcome.SEAT_TAKEN);
      assertThat(afterTheHoldExpired).isEqualTo(HoldOutcome.CREATED);
    }
  }

  private static HoldEndpoint endpoint(
      Workers workers, ConnectionPool pool, LongSupplier nanoClock) {
    return new HoldEndpoint(
        workers,
        pool,
        new SeatStore(FIXED_CLOCK, 1),
        new HoldEndpoint.Limits(QUEUE_WAIT_LIMIT, SHORT_LIMIT, Duration.ZERO),
        nanoClock);
  }

  private static List<Connection> seededConnections() throws Exception {
    List<Connection> connections = TestNodes.connections(1);
    new SeatStore(FIXED_CLOCK, 1).createSchema(connections.getFirst());
    return connections;
  }

  private static void awaitQuietly(CountDownLatch release) {
    try {
      if (!release.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the test never released the worker");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
