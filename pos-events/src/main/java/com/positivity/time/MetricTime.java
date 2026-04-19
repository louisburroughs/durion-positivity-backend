package com.positivity.time;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public record MetricTime(int hour, int minute, int second) {
  public static final int HOURS_PER_DAY = 10;
  public static final int MINUTES_PER_HOUR = 100;
  public static final int SECONDS_PER_MINUTE = 100;
  public static final int SECONDS_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE; // 100_000

  public MetricTime {
    if (hour < 0 || hour >= HOURS_PER_DAY) {
      throw new IllegalArgumentException("hour out of range: " + hour);
    }
    if (minute < 0 || minute >= MINUTES_PER_HOUR) {
      throw new IllegalArgumentException("minute out of range: " + minute);
    }
    if (second < 0 || second >= SECONDS_PER_MINUTE) {
      throw new IllegalArgumentException("second out of range: " + second);
    }
  }

  public static MetricTime now(Clock clock) {
    Objects.requireNonNull(clock, "clock");
    return fromInstant(clock.instant());
  }

  public static MetricTime fromInstant(Instant instant) {
    Objects.requireNonNull(instant, "instant");

    long secondOfMetricDay = Math.floorMod(instant.getEpochSecond(), SECONDS_PER_DAY);

    int hour = (int) (secondOfMetricDay / (MINUTES_PER_HOUR * SECONDS_PER_MINUTE));
    int remainder = (int) (secondOfMetricDay % (MINUTES_PER_HOUR * SECONDS_PER_MINUTE));
    int minute = remainder / SECONDS_PER_MINUTE;
    int second = remainder % SECONDS_PER_MINUTE;

    return new MetricTime(hour, minute, second);
  }

  @Override
  public String toString() {
    return "%01d:%02d:%02d".formatted(hour, minute, second);
  }
}