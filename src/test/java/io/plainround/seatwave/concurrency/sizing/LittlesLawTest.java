package io.plainround.seatwave.concurrency.sizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LittlesLawTest {

  private static final Duration FORTY_MILLIS = Duration.ofMillis(40);
  private static final int HOLDS_PER_SECOND = 500;
  private static final double CEILING = 0.8;
  private static final double TOLERANCE = 1e-9;

  @Test
  void puts20HoldsInFlightAt500PerSecondAnd40Milliseconds() {
    assertThat(LittlesLaw.inFlight(HOLDS_PER_SECOND, FORTY_MILLIS))
        .isCloseTo(20, within(TOLERANCE));
  }

  @Test
  void sizesThePoolAt25ConnectionsForAn80PercentCeiling() {
    assertThat(LittlesLaw.poolSize(HOLDS_PER_SECOND, FORTY_MILLIS, CEILING)).isEqualTo(25);
    assertThat(LittlesLaw.capacityPerSecond(25, FORTY_MILLIS)).isCloseTo(625, within(TOLERANCE));
  }

  @Test
  void neverSizesAPoolBelowOne() {
    assertThat(LittlesLaw.poolSize(1, Duration.ofNanos(1), 1)).isEqualTo(1);
  }

  @Test
  void growsTheWaitingFactorSteeplyNearFullUse() {
    assertThat(LittlesLaw.waitingFactor(0.5)).isCloseTo(1, within(TOLERANCE));
    assertThat(LittlesLaw.waitingFactor(0.8)).isCloseTo(4, within(TOLERANCE));
    assertThat(LittlesLaw.waitingFactor(0.99)).isCloseTo(99, within(TOLERANCE));
  }

  @Test
  void rejectsImpossibleInputs() {
    assertThatThrownBy(() -> LittlesLaw.inFlight(0, FORTY_MILLIS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.inFlight(Double.NaN, FORTY_MILLIS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.poolSize(HOLDS_PER_SECOND, FORTY_MILLIS, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.capacityPerSecond(0, FORTY_MILLIS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LittlesLaw.waitingFactor(1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void printsTheWalkthroughSizing() {
    SizingInputs inputs =
        SizingInputs.from(new SettingsReader(walkthrough())).discardError().orElseThrow();

    String report = SizingReport.render(inputs);

    assertThat(report)
        .contains(
            "= 20.0 holds",
            "= 25 connections; the database allows 32: fits",
            "= 10.0 vCPUs busy",
            "250 %: one node cannot serve this",
            "safe at 70 %: 560 seat maps/s",
            "= 4 nodes",
            "80 %: 4.0");
  }

  @Test
  void listsMissingSizingInputs() {
    Properties properties = walkthrough();
    properties.remove("node.vcpus");

    Result<SizingInputs, List<String>> read = SizingInputs.from(new SettingsReader(properties));

    assertThat(((Result.Error<SizingInputs, List<String>>) read).error())
        .containsExactly("node.vcpus is missing");
  }

  private static Properties walkthrough() {
    Properties properties = new Properties();
    properties.setProperty("hold.rate.per.second", "500");
    properties.setProperty("hold.time.millis", "40");
    properties.setProperty("database.max.connections", "32");
    properties.setProperty("seatmap.rate.per.second", "2000");
    properties.setProperty("seatmap.cpu.millis", "5");
    properties.setProperty("node.vcpus", "4");
    properties.setProperty("pool.utilization.ceiling.percent", "80");
    properties.setProperty("cpu.utilization.target.percent", "70");
    return properties;
  }
}
