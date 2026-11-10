package io.plainround.seatwave.concurrency.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import io.plainround.seatwave.concurrency.node.ExecutorMode;
import io.plainround.seatwave.concurrency.node.NodeSettings;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What one benchmark run measures. Every value comes from the settings file; there are no defaults.
 *
 * @param modes the thread models to run, in order; the same load runs once per mode
 * @param stepRates the offered holds per second of each load step, in order
 * @param warmup how long the first step's rate runs before measuring, per mode
 * @param stepDuration how long each measured step sends
 * @param randomSeed the seed of the arrival times and seat choices
 * @param clientTimeout how long a client waits for one reply before it gives up
 * @param sampleInterval how often the saturation counters are read
 * @param censusRate the step at which a thread census is taken
 * @param targetP99 the hold p99 target that marks a step as healthy (Module 2)
 * @param maxErrorPercent the largest error share a healthy step may have (Module 2)
 * @param node the node settings, with the first mode
 */
public record BenchmarkSettings(
    List<ExecutorMode> modes,
    List<Integer> stepRates,
    Duration warmup,
    Duration stepDuration,
    long randomSeed,
    Duration clientTimeout,
    Duration sampleInterval,
    int censusRate,
    Duration targetP99,
    double maxErrorPercent,
    NodeSettings node) {

  // A rate above 100,000 holds per second is not something one node in this module can be asked.
  private static final int MAX_RATE = 100_000;
  // Steps longer than a minute belong in a capacity test (Module 41), not in this teaching run.
  private static final int MAX_STEP_MILLIS = 60_000;
  private static final int MAX_SEED = Integer.MAX_VALUE;
  private static final int MAX_PERCENT = 100;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a list is empty, a duration is not positive, or the census
   *     rate is not one of the steps
   */
  public BenchmarkSettings {
    modes = List.copyOf(modes);
    stepRates = List.copyOf(stepRates);
    Objects.requireNonNull(node, "node");
    if (modes.isEmpty() || stepRates.isEmpty()) {
      throw new IllegalArgumentException("a benchmark needs at least one mode and one step");
    }
    for (Duration positive :
        new Duration[] {warmup, stepDuration, clientTimeout, sampleInterval, targetP99}) {
      if (positive.isNegative() || positive.isZero()) {
        throw new IllegalArgumentException("every duration must be positive: " + positive);
      }
    }
    if (!stepRates.contains(censusRate)) {
      throw new IllegalArgumentException("census.at.rps must be one of the steps: " + censusRate);
    }
  }

  /**
   * Reads and checks every key of a benchmark settings file.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<BenchmarkSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    List<ExecutorMode> modes = reader.enumList("executor.modes", ExecutorMode.class);
    List<Integer> rates = reader.intListBetween("load.steps.rps", 1, MAX_RATE);
    Duration warmup = reader.millisBetween("load.warmup.millis", 1, MAX_STEP_MILLIS);
    Duration step = reader.millisBetween("load.step.millis", 1, MAX_STEP_MILLIS);
    long seed = reader.intBetween("load.random.seed", 1, MAX_SEED);
    Duration clientTimeout = reader.millisBetween("client.timeout.millis", 1, MAX_STEP_MILLIS);
    Duration sample = reader.millisBetween("sampler.interval.millis", 1, MAX_STEP_MILLIS);
    int census = reader.intBetween("census.at.rps", 1, MAX_RATE);
    Duration targetP99 = reader.millisBetween("target.p99.millis", 1, MAX_STEP_MILLIS);
    double maxErrors = reader.decimalBetween("target.max.error.percent", 0, MAX_PERCENT);
    ExecutorMode firstMode = modes.isEmpty() ? ExecutorMode.PLATFORM : modes.getFirst();
    Result<NodeSettings, List<String>> node = NodeSettings.from(reader, firstMode);
    return switch (node) {
      case Result.Error<NodeSettings, List<String>>(List<String> problems) ->
          Result.error(problems);
      case Result.Success<NodeSettings, List<String>>(NodeSettings nodeSettings) -> {
        try {
          yield Result.success(
              new BenchmarkSettings(
                  modes,
                  rates,
                  warmup,
                  step,
                  seed,
                  clientTimeout,
                  sample,
                  census,
                  targetP99,
                  maxErrors,
                  nodeSettings));
        } catch (IllegalArgumentException inconsistent) {
          // Each key was valid alone, but together they break a rule, such as a census rate that is
          // not a step.
          yield Result.error(List.of(inconsistent.getMessage()));
        }
      }
    };
  }
}
