package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkersTest {

  private static final int TWO = 2;

  @Test
  void platformWorkersRefuseWhenEveryThreadIsBusyAndTheQueueIsFull() {
    CountDownLatch release = new CountDownLatch(1);
    try (PlatformWorkers workers = new PlatformWorkers(1, 1)) {
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.queued() == 0);
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();

      boolean isThirdAccepted = workers.tryExecute(() -> awaitQuietly(release));

      assertThat(isThirdAccepted).isFalse();
      assertThat(workers.threadsAlive()).isEqualTo(1);
      assertThat(workers.queued()).isEqualTo(1);
      release.countDown();
    }
  }

  @Test
  void virtualWorkersRefuseOverTheInFlightBoundAndFreeThePermitAfterward() {
    CountDownLatch release = new CountDownLatch(1);
    try (VirtualWorkers workers = new VirtualWorkers(TWO)) {
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();
      assertThat(workers.tryExecute(() -> awaitQuietly(release))).isTrue();

      boolean isThirdAccepted = workers.tryExecute(() -> awaitQuietly(release));

      assertThat(isThirdAccepted).isFalse();
      assertThat(workers.threadsAlive()).isEqualTo(TWO);
      assertThat(workers.queued()).isZero();
      release.countDown();
      await().atMost(TestNodes.LONG_WAIT).until(() -> workers.threadsAlive() == 0);
      assertThat(workers.tryExecute(() -> {})).isTrue();
    }
  }

  @Test
  void virtualWorkersRefuseAfterClose() {
    VirtualWorkers workers = new VirtualWorkers(TWO);
    workers.close();

    assertThat(workers.tryExecute(() -> {})).isFalse();
    assertThat(workers.threadsAlive()).isZero();
  }

  @Test
  void rejectsAZeroBound() {
    assertThatThrownBy(() -> new PlatformWorkers(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VirtualWorkers(0)).isInstanceOf(IllegalArgumentException.class);
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
