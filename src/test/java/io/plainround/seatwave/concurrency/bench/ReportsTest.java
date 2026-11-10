package io.plainround.seatwave.concurrency.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.concurrency.node.ExecutorMode;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** The CSV, the SVG chart, the text report and the value records they read. */
class ReportsTest {

  private static final Duration TARGET = Duration.ofMillis(300);
  private static final double BUDGET_PERCENT = 0.05;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final int HUNDRED = 100;

  private final List<StepResult> results =
      List.of(
          StepResults.step(ExecutorMode.PLATFORM, 200, 49.2, 0),
          StepResults.step(ExecutorMode.PLATFORM, 400, 586.9, 20),
          StepResults.step(ExecutorMode.VIRTUAL, 200, 45.9, 0),
          StepResults.step(ExecutorMode.VIRTUAL, 400, 87.0, 0));

  @Test
  void readsP50AndP99ByNearestRank() {
    long[] oneToHundredMillis = new long[HUNDRED];
    for (int index = 0; index < HUNDRED; index++) {
      oneToHundredMillis[index] = (HUNDRED - index) * NANOS_PER_MILLI;
    }

    LatencySummary summary = LatencySummary.of(oneToHundredMillis);

    assertThat(summary).isEqualTo(new LatencySummary(HUNDRED, 50, 99));
    assertThat(LatencySummary.of(new long[0]).hasSamples()).isFalse();
    assertThatThrownBy(() -> new LatencySummary(1, 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAStepWhoseOutcomesDoNotAddUp() {
    assertThatThrownBy(() ->
            new StepResult(
                ExecutorMode.VIRTUAL,
                1,
                2,
                1,
                0,
                0,
                0,
                0,
                0,
                1,
                new LatencySummary(0, 0, 0),
                new LatencySummary(0, 0, 0),
                new Saturation(0, 0, 0, 0, 0),
                0,
                List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Saturation(0, 0, 0, 0, HUNDRED + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void countsRefusedHoldsAsErrors() {
    assertThat(results.get(1).errorPercent()).isEqualTo(2.0);
    assertThat(results.getFirst().errorPercent()).isZero();
  }

  @Test
  void writesOneCsvLinePerStepWithEveryColumn() {
    String csv = BenchmarkCsv.render(results);

    List<String> lines = csv.lines().toList();
    int columns = BenchmarkCsv.HEADER.split(",").length;
    assertThat(lines).hasSize(results.size() + 1);
    assertThat(lines.subList(1, lines.size()))
        .allSatisfy(line -> assertThat(line.split(",")).hasSize(columns));
    assertThat(lines.get(2)).startsWith("platform,400,400.0,1000,980,0,20,");
  }

  @Test
  void drawsAValidSvgWithOneLinePerModeAndTheTarget() throws Exception {
    String svg = LoadChartSvg.render("p99 <&> load", results, ChartMetric.P99_MILLIS, 300);

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    assertThat(document.getElementsByTagName("polyline").getLength()).isEqualTo(2);
    assertThat(document.getElementsByTagName("circle").getLength()).isEqualTo(results.size());
    assertThat(svg).contains("p99 &lt;&amp;&gt; load", "target 300", "virtual threads");
  }

  @Test
  void findsTheLastStepInsideTheTarget() {
    List<StepResult> platform = results.subList(0, 2);
    List<StepResult> virtual = results.subList(2, 4);

    assertThat(BenchmarkReport.lastHealthyRate(platform, TARGET, BUDGET_PERCENT)).contains(200);
    assertThat(BenchmarkReport.lastHealthyRate(virtual, TARGET, BUDGET_PERCENT)).contains(400);
    assertThat(BenchmarkReport.lastHealthyRate(results.subList(1, 2), TARGET, BUDGET_PERCENT))
        .isEmpty();
  }

  @Test
  void printsATableAndABarPerStep() {
    String table = BenchmarkReport.table(results);
    String chart = BenchmarkReport.textChart(results);

    assertThat(table.lines()).hasSize(results.size() + 1);
    assertThat(chart).contains("platform threads", "virtual threads", "p99    586.9 ms");
    assertThat(chart.lines().filter(line -> line.contains("#".repeat(50)))).hasSize(1);
  }

  @Test
  void printsACensusLinePerGroupStateAndPlace() {
    String census =
        BenchmarkReport.census(
            List.of(
                new CensusLine("seatwave-worker-", "TIMED_WAITING", "ConnectionPool.borrow", 48)));

    assertThat(census).contains("48", "TIMED_WAITING", "in ConnectionPool.borrow");
    assertThatThrownBy(() -> new CensusLine("g", "s", "w", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
