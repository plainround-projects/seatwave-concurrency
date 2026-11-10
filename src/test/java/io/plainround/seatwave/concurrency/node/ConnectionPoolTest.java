package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

  private static final Duration SHORT_LIMIT = Duration.ofMillis(20);
  private static final int CONNECTIONS = 4;
  private static final int BORROWERS = 200;

  @Test
  void refusesAPoolWithoutConnections() {
    assertThatThrownBy(() -> new ConnectionPool(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void givesUpAfterTheLimitWhenEveryConnectionIsLent() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(1))) {
      Optional<ConnectionPool.Lease> first = pool.borrow(SHORT_LIMIT);

      Optional<ConnectionPool.Lease> second = pool.borrow(SHORT_LIMIT);

      assertThat(first).isPresent();
      assertThat(second).isEmpty();
      assertThat(pool.snapshot()).isEqualTo(new PoolSnapshot(1, 1, 0, 1));
    }
  }

  @Test
  void countsAWaitingBorrowerUntilAConnectionComesBack() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(1));
        ExecutorService borrower = Executors.newVirtualThreadPerTaskExecutor()) {
      ConnectionPool.Lease held = pool.borrow(SHORT_LIMIT).orElseThrow();
      Future<Optional<ConnectionPool.Lease>> waiting =
          borrower.submit(() -> pool.borrow(TestNodes.LONG_WAIT));
      await().atMost(TestNodes.LONG_WAIT).until(() -> pool.snapshot().waitingBorrowers() == 1);

      held.close();

      assertThat(waiting.get()).isPresent();
      assertThat(pool.snapshot().waitingBorrowers()).isZero();
      assertThat(pool.snapshot().busy()).isEqualTo(1);
    }
  }

  @Test
  void returnsALeaseOnlyOnceEvenWhenClosedTwice() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(2))) {
      ConnectionPool.Lease lease = pool.borrow(SHORT_LIMIT).orElseThrow();

      lease.close();
      lease.close();

      assertThat(pool.snapshot().busy()).isZero();
      assertThat(pool.borrow(SHORT_LIMIT)).isPresent();
      assertThat(pool.borrow(SHORT_LIMIT)).isPresent();
      assertThat(pool.borrow(SHORT_LIMIT)).isEmpty();
    }
  }

  @Test
  void neverLendsOneConnectionToTwoBorrowersAtOnce() throws Exception {
    Set<Connection> inUse = ConcurrentHashMap.newKeySet();
    try (ConnectionPool pool = new ConnectionPool(TestNodes.connections(CONNECTIONS));
        ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Boolean>> borrows =
          IntStream.range(0, BORROWERS)
              .mapToObj(borrowerIndex ->
                  CompletableFuture.supplyAsync(() -> borrowAlone(pool, inUse), threads))
              .toList();

      for (CompletableFuture<Boolean> borrow : borrows) {
        assertThat(borrow.get()).isTrue();
      }
      assertThat(pool.snapshot().busy()).isZero();
    }
  }

  @Test
  void closesEveryConnection() throws Exception {
    List<Connection> connections = TestNodes.connections(2);
    ConnectionPool pool = new ConnectionPool(connections);

    pool.close();

    assertThat(connections).allSatisfy(connection -> assertThat(connection.isClosed()).isTrue());
  }

  @Test
  void rejectsImpossibleCounters() {
    assertThatThrownBy(() -> new PoolSnapshot(1, 2, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // True when no other borrower held this connection while this one did.
  private static boolean borrowAlone(ConnectionPool pool, Set<Connection> inUse) {
    try (ConnectionPool.Lease lease = pool.borrow(TestNodes.LONG_WAIT).orElseThrow()) {
      boolean isAlone = inUse.add(lease.connection());
      inUse.remove(lease.connection());
      return isAlone;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
