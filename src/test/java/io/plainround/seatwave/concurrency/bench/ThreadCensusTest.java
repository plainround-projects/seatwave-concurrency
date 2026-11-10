package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.concurrency.node.TestNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThreadCensusTest {

  private static final String GROUP = "census-test-";
  private static final int THREADS = 3;

  @Test
  void countsWaitingThreadsOfOneGroupByStateAndPlace() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int threadIndex = 0; threadIndex < THREADS; threadIndex++) {
      threads.add(Thread.ofPlatform().name(GROUP, threadIndex).start(() -> waitFor(release)));
    }
    await()
        .atMost(TestNodes.LONG_WAIT)
        .until(() ->
            threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING));

    List<CensusLine> census = ThreadCensus.take(List.of(GROUP));

    release.countDown();
    for (Thread thread : threads) {
      thread.join(TestNodes.LONG_WAIT);
    }
    assertThat(census).hasSize(1);
    assertThat(census.getFirst().threads()).isEqualTo(THREADS);
    assertThat(census.getFirst().state()).isEqualTo("TIMED_WAITING");
    assertThat(census.getFirst().waitingIn()).startsWith("ThreadCensusTest.");
  }

  private static void waitFor(CountDownLatch release) {
    try {
      if (!release.await(TestNodes.LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the test never released the thread");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
