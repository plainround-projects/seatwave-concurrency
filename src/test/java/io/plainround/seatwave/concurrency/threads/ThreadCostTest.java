package io.plainround.seatwave.concurrency.threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.TestNodes;
import org.junit.jupiter.api.Test;

class ThreadCostTest {

  private static final int PLATFORM_THREADS = 20;
  private static final int VIRTUAL_THREADS = 2000;

  @Test
  void startsEveryPlatformThreadAndSeesThemInTheJvm() throws Exception {
    ThreadCost.Result result =
        ThreadCost.measure(ExecutorMode.PLATFORM, PLATFORM_THREADS, TestNodes.LONG_WAIT);

    assertThat(result.threads()).isEqualTo(PLATFORM_THREADS);
    assertThat(result.platformThreadsInJvm()).isGreaterThanOrEqualTo(PLATFORM_THREADS);
    assertThat(result.startTime()).isPositive();
  }

  @Test
  void startsThousandsOfVirtualThreadsWithoutAPlatformThreadEach() throws Exception {
    ThreadCost.Result result =
        ThreadCost.measure(ExecutorMode.VIRTUAL, VIRTUAL_THREADS, TestNodes.LONG_WAIT);

    assertThat(result.threads()).isEqualTo(VIRTUAL_THREADS);
    assertThat(result.platformThreadsInJvm()).isLessThan(VIRTUAL_THREADS);
  }

  @Test
  void rejectsZeroThreads() {
    assertThatThrownBy(() -> ThreadCost.measure(ExecutorMode.VIRTUAL, 0, TestNodes.LONG_WAIT))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
