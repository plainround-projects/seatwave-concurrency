package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkRunTest {

  private static final int STEPS_PER_MODE = 2;
  private static final int FILES = 3;

  @Test
  void runsEveryModeOnItsOwnNodeAndWritesTheCsvAndCharts(@TempDir Path folder) throws Exception {
    BenchmarkSettings settings =
        BenchmarkSettings.from(new SettingsReader(properties())).discardError().orElseThrow();

    List<StepResult> all = BenchmarkRun.runEveryMode(settings);
    String summary = BenchmarkRun.summary(settings, all);
    List<Path> written = BenchmarkRun.writeFiles(folder, "tiny", all, settings);

    assertThat(all)
        .extracting(StepResult::mode)
        .containsExactly(
            ExecutorMode.PLATFORM,
            ExecutorMode.PLATFORM,
            ExecutorMode.VIRTUAL,
            ExecutorMode.VIRTUAL);
    assertThat(all).hasSize(STEPS_PER_MODE * 2);
    assertThat(BenchmarkRun.header(settings)).contains("Node: 4 platform workers", "seed 20261110");
    assertThat(summary)
        .contains(
            "Thread census at 100 holds/s, platform mode",
            "p99 against offered load, virtual threads",
            "platform: last step inside the target");
    assertThat(written).hasSize(FILES).allSatisfy(path -> assertThat(path).isRegularFile());
    assertThat(Files.readString(written.getFirst(), StandardCharsets.UTF_8))
        .startsWith(BenchmarkCsv.HEADER);
  }

  private static Properties properties() {
    Properties properties = new Properties();
    properties.setProperty("executor.modes", "platform,virtual");
    properties.setProperty("load.steps.rps", "50,100");
    properties.setProperty("load.warmup.millis", "50");
    properties.setProperty("load.step.millis", "100");
    properties.setProperty("load.random.seed", "20261110");
    properties.setProperty("client.timeout.millis", "5000");
    properties.setProperty("sampler.interval.millis", "5");
    properties.setProperty("census.at.rps", "100");
    properties.setProperty("target.p99.millis", "300");
    properties.setProperty("target.max.error.percent", "0.05");
    properties.setProperty("platform.workers", "4");
    properties.setProperty("platform.queue.bound", "10");
    properties.setProperty("virtual.max.in.flight", "100");
    properties.setProperty("queue.wait.limit.millis", "300");
    properties.setProperty("pool.connections", "4");
    properties.setProperty("pool.borrow.limit.millis", "500");
    properties.setProperty("database.wait.millis", "1");
    properties.setProperty("database.statement.timeout.seconds", "2");
    properties.setProperty("database.url", "jdbc:h2:mem:run-test-");
    return properties;
  }
}
