package io.plainround.seatwave.concurrency.threads;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.concurrency.node.TestNodes;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The two demos differ by a whole blocking period, so the comparison is safe on any machine: an
 * unmounted blocker frees its carrier at once, and a pinned one frees it only when it stops
 * blocking. The pinned demo touches a class that only this test initializes.
 */
class CarrierPinningTest {

  @Test
  void anUnrelatedTaskStartsAtOnceWhileEveryCarrierSleepsUnmounted() throws Exception {
    Duration delay = CarrierPinning.delayWhileSleeping(TestNodes.LONG_WAIT);

    assertThat(delay).isLessThan(CarrierPinning.BLOCKS_FOR);
  }

  @Test
  void anUnrelatedTaskWaitsWhileEveryCarrierIsPinnedByAClassInitializer() throws Exception {
    Duration delay = CarrierPinning.delayWhilePinned(TestNodes.LONG_WAIT);

    assertThat(delay).isGreaterThanOrEqualTo(CarrierPinning.BLOCKS_FOR.dividedBy(2));
  }
}
