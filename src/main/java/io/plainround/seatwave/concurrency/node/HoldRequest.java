package io.plainround.seatwave.concurrency.node;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One fan's request to hold one seat: the body of {@code POST /holds} from Module 1.
 *
 * @param eventId the event, 1 or more
 * @param seatLabel the seat, such as {@code A-7}: a section letter from A to T and a number
 * @param customer the fan's identifier: letters, digits, underscore and hyphen, at most 64
 */
public record HoldRequest(long eventId, String seatLabel, String customer) {

  // The same allow-lists as SeatWave version 0, so a request valid there is valid here.
  private static final Pattern SEAT_LABEL_PATTERN = Pattern.compile("[A-T]-[0-9]{1,4}");
  private static final Pattern CUSTOMER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /**
   * Creates the request.
   *
   * @throws IllegalArgumentException if a field breaks its allow-list
   */
  public HoldRequest {
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(customer, "customer");
    if (eventId < 1) {
      throw new IllegalArgumentException("eventId must be 1 or more: " + eventId);
    }
    if (!SEAT_LABEL_PATTERN.matcher(seatLabel).matches()) {
      throw new IllegalArgumentException("invalid seat label");
    }
    if (!CUSTOMER_PATTERN.matcher(customer).matches()) {
      throw new IllegalArgumentException("invalid customer");
    }
  }
}
