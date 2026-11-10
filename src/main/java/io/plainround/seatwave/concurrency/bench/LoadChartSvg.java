package io.plainround.seatwave.concurrency.bench;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Draws one metric against the offered rate as a small SVG line chart, one line per thread model,
 * with no charting library: the file is plain text a browser opens. Stateless.
 */
public final class LoadChartSvg {

  private static final int WIDTH = 720;
  private static final int HEIGHT = 420;
  private static final int LEFT = 80;
  private static final int RIGHT = 160;
  private static final int TOP = 50;
  private static final int BOTTOM = 60;
  private static final int TICKS = 5;
  private static final int POINT_RADIUS = 4;
  private static final int LEGEND_LINE_HEIGHT = 22;
  // Offsets in pixels that keep labels clear of the lines they name.
  private static final double AXIS_TITLE_GAP = 18;
  private static final double TICK_LABEL_GAP = 8;
  private static final double BASELINE_NUDGE = 4;
  private static final int LEGEND_SWATCH_GAP = 10;
  private static final double LEGEND_TEXT_GAP = 30;
  private static final double HALF = 2.0;
  private static final double DECIMAL_BASE = 10;
  private static final double[] NICE_STEPS = {1, 2, 5, 10};
  // Two colors that stay apart for the common kinds of color blindness.
  private static final Map<ExecutorMode, String> COLORS =
      Map.of(ExecutorMode.PLATFORM, "#d55e00", ExecutorMode.VIRTUAL, "#0072b2");

  private LoadChartSvg() {}

  /**
   * Draws the chart.
   *
   * @param title the chart title; it is escaped
   * @param results the steps of every mode
   * @param metric the value to plot
   * @param referenceLine a horizontal line to draw, such as the p99 target; 0 for none
   * @return the SVG document
   */
  public static String render(
      String title, List<StepResult> results, ChartMetric metric, double referenceLine) {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(results, "results");
    Objects.requireNonNull(metric, "metric");
    double maxRate = results.stream().mapToInt(StepResult::offeredRps).max().orElse(1);
    double maxValue =
        Math.max(referenceLine, results.stream().mapToDouble(metric::read).max().orElse(1));
    Scale scale = new Scale(niceCeiling(maxRate), niceCeiling(maxValue));
    StringBuilder svg = new StringBuilder();
    svg.append(
        String.format(
            Locale.ROOT,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\""
                + " font-family=\"sans-serif\" font-size=\"12\">\n",
            WIDTH,
            HEIGHT,
            WIDTH,
            HEIGHT));
    svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
    text(svg, WIDTH / HALF, TOP / HALF, "middle", escape(title));
    axes(svg, scale, metric);
    if (referenceLine > 0) {
      double lineY = scale.y(referenceLine);
      svg.append(
          String.format(
              Locale.ROOT,
              "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#555555\""
                  + " stroke-dasharray=\"6 4\"/>\n",
              LEFT,
              lineY,
              WIDTH - RIGHT,
              lineY));
      text(
          svg,
          WIDTH - RIGHT + BASELINE_NUDGE,
          lineY + BASELINE_NUDGE,
          "start",
          "target " + number(referenceLine));
    }
    lines(svg, scale, results, metric);
    svg.append("</svg>\n");
    return svg.toString();
  }

  private static void axes(StringBuilder svg, Scale scale, ChartMetric metric) {
    int plotBottom = HEIGHT - BOTTOM;
    svg.append(
        String.format(
            Locale.ROOT,
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#000000\"/>\n"
                + "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#000000\"/>\n",
            LEFT,
            plotBottom,
            WIDTH - RIGHT,
            plotBottom,
            LEFT,
            TOP,
            LEFT,
            plotBottom));
    for (int tickIndex = 0; tickIndex <= TICKS; tickIndex++) {
      double rate = scale.maxRate() * tickIndex / TICKS;
      double value = scale.maxValue() * tickIndex / TICKS;
      text(svg, scale.x(rate), plotBottom + AXIS_TITLE_GAP, "middle", number(rate));
      text(svg, LEFT - TICK_LABEL_GAP, scale.y(value) + BASELINE_NUDGE, "end", number(value));
    }
    text(
        svg,
        (LEFT + WIDTH - RIGHT) / HALF,
        HEIGHT - AXIS_TITLE_GAP,
        "middle",
        "offered holds per second");
    svg.append(
        String.format(
            Locale.ROOT,
            "<text x=\"18\" y=\"%.1f\" text-anchor=\"middle\" transform=\"rotate(-90 18 %.1f)\">"
                + "%s</text>\n",
            (TOP + plotBottom) / HALF,
            (TOP + plotBottom) / HALF,
            escape(metric.axisLabel())));
  }

  private static void lines(
      StringBuilder svg, Scale scale, List<StepResult> results, ChartMetric metric) {
    Map<ExecutorMode, StringBuilder> points = new TreeMap<>();
    for (StepResult step : results) {
      double pointX = scale.x(step.offeredRps());
      double pointY = scale.y(metric.read(step));
      points
          .computeIfAbsent(step.mode(), unused -> new StringBuilder())
          .append(String.format(Locale.ROOT, "%.1f,%.1f ", pointX, pointY));
      svg.append(
          String.format(
              Locale.ROOT,
              "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%d\" fill=\"%s\"/>\n",
              pointX,
              pointY,
              POINT_RADIUS,
              COLORS.get(step.mode())));
    }
    int legendIndex = 0;
    for (Map.Entry<ExecutorMode, StringBuilder> line : points.entrySet()) {
      String color = COLORS.get(line.getKey());
      svg.append(
          String.format(
              Locale.ROOT,
              "<polyline fill=\"none\" stroke=\"%s\" stroke-width=\"2\" points=\"%s\"/>\n",
              color,
              line.getValue().toString().strip()));
      double legendY = TOP + LEGEND_LINE_HEIGHT * (legendIndex + 1.0);
      svg.append(
          String.format(
              Locale.ROOT,
              "<rect x=\"%d\" y=\"%.1f\" width=\"14\" height=\"4\" fill=\"%s\"/>\n",
              WIDTH - RIGHT + LEGEND_SWATCH_GAP,
              legendY - BASELINE_NUDGE,
              color));
      text(
          svg,
          WIDTH - RIGHT + LEGEND_TEXT_GAP,
          legendY,
          "start",
          line.getKey().name().toLowerCase(Locale.ROOT) + " threads");
      legendIndex++;
    }
  }

  private static void text(
      StringBuilder svg, double textX, double textY, String anchor, String body) {
    svg.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\">%s</text>\n",
            textX,
            textY,
            anchor,
            body));
  }

  // Rounds up to 1, 2 or 5 times a power of ten, so the axis ticks are round numbers.
  private static double niceCeiling(double value) {
    if (value <= 0) {
      return 1;
    }
    double power = Math.pow(DECIMAL_BASE, Math.floor(Math.log10(value)));
    for (double step : NICE_STEPS) {
      if (step * power >= value) {
        return step * power;
      }
    }
    return DECIMAL_BASE * power;
  }

  private static String number(double value) {
    return value == Math.rint(value)
        ? String.format(Locale.ROOT, "%,d", (long) value)
        : String.format(Locale.ROOT, "%,.1f", value);
  }

  private static String escape(String raw) {
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private record Scale(double maxRate, double maxValue) {
    double x(double rate) {
      return LEFT + (WIDTH - LEFT - RIGHT) * rate / maxRate;
    }

    double y(double value) {
      return HEIGHT - BOTTOM - (HEIGHT - TOP - BOTTOM) * value / maxValue;
    }
  }
}
