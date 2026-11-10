package io.plainround.seatwave.concurrency.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The value types of the node: each refuses to hold an invalid value. */
class NodeValuesTest {

  private static final int LAST_SEAT = 19_999;

  @Test
  void acceptsAValidHoldRequest() {
    HoldRequest request = new HoldRequest(1, "T-1000", "fan_1");

    assertThat(request.seatLabel()).isEqualTo("T-1000");
  }

  @Test
  void rejectsHoldRequestsOutsideTheAllowLists() {
    assertThatThrownBy(() -> new HoldRequest(0, "A-1", "fan"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HoldRequest(1, "Z-1;drop", "fan"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HoldRequest(1, "A-1", "fan one"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HoldRequest(1, "A-1", "x".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mapsEveryOutcomeToItsStatus() {
    assertThat(HoldOutcome.CREATED.httpStatus()).isEqualTo(201);
    assertThat(HoldOutcome.SEAT_TAKEN.httpStatus()).isEqualTo(409);
    assertThat(HoldOutcome.values())
        .filteredOn(outcome -> !outcome.isGood())
        .allSatisfy(outcome -> assertThat(outcome.httpStatus()).isEqualTo(503));
  }

  @Test
  void rejectsANegativeDurationInAReply() {
    assertThatThrownBy(() -> new HoldReply(HoldOutcome.CREATED, -1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void labelsTheFirstAndLastSeat() {
    assertThat(SeatStore.seatLabel(0)).isEqualTo("A-1");
    assertThat(SeatStore.seatLabel(LAST_SEAT)).isEqualTo("T-1000");
    assertThatThrownBy(() -> SeatStore.seatLabel(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SeatStore.seatLabel(LAST_SEAT + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsNodeSettingsFromAFile() {
    Result<NodeSettings, List<String>> read =
        NodeSettings.from(new SettingsReader(validProperties()), ExecutorMode.VIRTUAL);

    NodeSettings settings = read.discardError().orElseThrow();
    assertThat(settings.mode()).isEqualTo(ExecutorMode.VIRTUAL);
    assertThat(settings.poolBorrowLimit()).isEqualTo(Duration.ofMillis(500));
    assertThat(settings.withMode(ExecutorMode.PLATFORM).mode()).isEqualTo(ExecutorMode.PLATFORM);
    assertThat(settings.withDatabaseUrl("jdbc:h2:mem:other").databaseUrl())
        .isEqualTo("jdbc:h2:mem:other");
  }

  @Test
  void listsEveryMissingOrInvalidNodeSetting() {
    Properties properties = validProperties();
    properties.remove("pool.connections");
    properties.setProperty("platform.workers", "zero");

    Result<NodeSettings, List<String>> read =
        NodeSettings.from(new SettingsReader(properties), ExecutorMode.PLATFORM);

    assertThat(((Result.Error<NodeSettings, List<String>>) read).error())
        .containsExactly(
            "platform.workers must be a whole number, but was zero", "pool.connections is missing");
  }

  @Test
  void refusesADatabaseThatIsNotInMemory() {
    Properties properties = validProperties();
    properties.setProperty("database.url", "jdbc:h2:file:./seatwave");

    Result<NodeSettings, List<String>> read =
        NodeSettings.from(new SettingsReader(properties), ExecutorMode.PLATFORM);

    assertThat(((Result.Error<NodeSettings, List<String>>) read).error())
        .singleElement()
        .asString()
        .startsWith("only jdbc:h2:mem: URLs are allowed");
  }

  /**
   * Returns the node keys of the SIMPLE settings file.
   *
   * @return the properties
   */
  static Properties validProperties() {
    Properties properties = new Properties();
    properties.setProperty("platform.workers", "64");
    properties.setProperty("platform.queue.bound", "120");
    properties.setProperty("virtual.max.in.flight", "184");
    properties.setProperty("queue.wait.limit.millis", "300");
    properties.setProperty("pool.connections", "16");
    properties.setProperty("pool.borrow.limit.millis", "500");
    properties.setProperty("database.wait.millis", "40");
    properties.setProperty("database.statement.timeout.seconds", "2");
    properties.setProperty("database.url", "jdbc:h2:mem:seatwave-test-");
    return properties;
  }
}
