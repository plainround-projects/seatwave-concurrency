package io.plainround.seatwave.concurrency.bench;

import java.util.function.ToDoubleFunction;

/** A value of a step that a chart can plot against the offered rate. */
public enum ChartMetric {
  /** The p99 of the good replies, in milliseconds. */
  P99_MILLIS("p99 latency of 201 and 409 replies (ms)", step -> step.latency().p99Millis()),
  /** The share of holds without a good reply in time, in percent. */
  ERROR_PERCENT("holds without a good reply in time (%)", StepResult::errorPercent);

  private final String axisLabel;
  private final ToDoubleFunction<StepResult> reading;

  ChartMetric(String axisLabel, ToDoubleFunction<StepResult> reading) {
    this.axisLabel = axisLabel;
    this.reading = reading;
  }

  /**
   * Returns the label of the vertical axis.
   *
   * @return the label, with its unit
   */
  public String axisLabel() {
    return axisLabel;
  }

  /**
   * Reads this metric from a step.
   *
   * @param step the step
   * @return the value
   */
  public double read(StepResult step) {
    return reading.applyAsDouble(step);
  }
}
