package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.NodeSettings;
import io.plainround.seatwave.concurrency.node.SeatWaveNode;
import io.plainround.seatwave.concurrency.node.TestNodes;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Short real runs of the load generator. Timing varies by machine, so the tests check what is true
 * everywhere: the number of holds, that each lands in one bucket, and where the refusals come from.
 */
class OpenModelLoadTest {

  private static final int RATE = 200;
  private static final Duration SHORT_STEP = Duration.ofMillis(250);
  private static final int EXPECTED_HOLDS = 50;
  private static final int OVERLOAD_RATE = 2000;
  private static final int OVERLOAD_HOLDS = 200;
  private static final Duration OVERLOAD_STEP = Duration.ofMillis(100);

  @Test
  void sendsExactlyRateTimesDurationHoldsAndAccountsForEach() throws Exception {
    BenchmarkSettings settings = settings("200", "200");
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.roomy(ExecutorMode.VIRTUAL), Clock.systemUTC())) {

      StepResult step = new OpenModelLoad(settings).runStep(node, RATE, SHORT_STEP);

      assertThat(step.sent()).isEqualTo(EXPECTED_HOLDS);
      assertThat(step.created() + step.seatTaken()).isEqualTo(EXPECTED_HOLDS);
      assertThat(step.errorPercent()).isZero();
      assertThat(step.latency().count()).isEqualTo(EXPECTED_HOLDS);
      assertThat(step.census()).isNotEmpty();
    }
  }

  @Test
  void refusesAtTheDoorWhenOneWorkerMeetsABurst() throws Exception {
    BenchmarkSettings settings = settings("2000", "2000");
    NodeSettings oneWorkerSlowDatabase =
        withDatabaseWait(TestNodes.tiny(ExecutorMode.PLATFORM), Duration.ofMillis(20));
    try (SeatWaveNode node = SeatWaveNode.start(oneWorkerSlowDatabase, Clock.systemUTC())) {

      StepResult step = new OpenModelLoad(settings).runStep(node, OVERLOAD_RATE, OVERLOAD_STEP);

      assertThat(step.sent()).isEqualTo(OVERLOAD_HOLDS);
      assertThat(step.refusedQueue()).isPositive();
      assertThat(step.clientTimeouts()).isZero();
      assertThat(step.census())
          .anySatisfy(line -> assertThat(line.group()).isEqualTo("seatwave-worker-"));
    }
  }

  @Test
  void runsAWarmUpAndThenOneResultPerStep() throws Exception {
    BenchmarkSettings settings = settings("100,200", "100");
    try (SeatWaveNode node =
        SeatWaveNode.start(TestNodes.roomy(ExecutorMode.PLATFORM), Clock.systemUTC())) {

      List<StepResult> results = new OpenModelLoad(settings).runAll(node);

      assertThat(results).extracting(StepResult::offeredRps).containsExactly(100, 200);
      assertThat(results)
          .allSatisfy(step -> assertThat(step.mode()).isEqualTo(ExecutorMode.PLATFORM));
    }
  }

  @Test
  void refusesSettingsWhoseCensusRateIsNotAStep() {
    Properties properties = properties("200,320", "999");

    Result<BenchmarkSettings, List<String>> read =
        BenchmarkSettings.from(new SettingsReader(properties));

    assertThat(((Result.Error<BenchmarkSettings, List<String>>) read).error())
        .containsExactly("census.at.rps must be one of the steps: 999");
  }

  private static BenchmarkSettings settings(String rates, String censusRate) {
    return BenchmarkSettings.from(new SettingsReader(properties(rates, censusRate)))
        .discardError()
        .orElseThrow();
  }

  private static Properties properties(String rates, String censusRate) {
    Properties properties = new Properties();
    properties.setProperty("executor.modes", "virtual,platform");
    properties.setProperty("load.steps.rps", rates);
    properties.setProperty("load.warmup.millis", "50");
    properties.setProperty("load.step.millis", "100");
    properties.setProperty("load.random.seed", "20261110");
    properties.setProperty("client.timeout.millis", "5000");
    properties.setProperty("sampler.interval.millis", "5");
    properties.setProperty("census.at.rps", censusRate);
    properties.setProperty("target.p99.millis", "300");
    properties.setProperty("target.max.error.percent", "0.05");
    properties.setProperty("platform.workers", "4");
    properties.setProperty("platform.queue.bound", "10");
    properties.setProperty("virtual.max.in.flight", "100");
    properties.setProperty("queue.wait.limit.millis", "300");
    properties.setProperty("pool.connections", "4");
    properties.setProperty("pool.borrow.limit.millis", "500");
    properties.setProperty("database.wait.millis", "0");
    properties.setProperty("database.statement.timeout.seconds", "2");
    properties.setProperty("database.url", "jdbc:h2:mem:bench-test-");
    return properties;
  }

  private static NodeSettings withDatabaseWait(NodeSettings settings, Duration databaseWait) {
    return new NodeSettings(
        settings.mode(),
        settings.platformWorkers(),
        settings.executorQueueBound(),
        settings.virtualMaxInFlight(),
        settings.queueWaitLimit(),
        settings.poolConnections(),
        settings.poolBorrowLimit(),
        databaseWait,
        settings.statementTimeoutSeconds(),
        settings.databaseUrl());
  }
}
