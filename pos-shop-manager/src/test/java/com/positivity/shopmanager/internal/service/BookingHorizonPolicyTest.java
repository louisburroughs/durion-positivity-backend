package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.shopmanager.internal.exception.BookingHorizonExceededException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The booking horizon itself (DECISION-SHOPMGMT-019, issue #2100): the boundary, the configured
 * value, and the fact that the days are counted in the facility's own timezone.
 */
class BookingHorizonPolicyTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private final BookingHorizonPolicy defaultPolicy = new BookingHorizonPolicy(180);

    @Test
    @DisplayName("AC2: a booking well inside the horizon is allowed")
    void bookingInsideTheHorizonIsAllowed() {
        assertThatCode(() -> defaultPolicy.verifyWithinHorizon(Instant.parse("2026-04-15T09:00:00Z"), UTC, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC2: the 180th day is inside the horizon — the bound is inclusive")
    void bookingExactlyAtTheHorizonIsAllowed() {
        // 2026-03-01 + 180 days = 2026-08-28, and the hour of day does not matter: the comparison is
        // between local dates, so a booking late on the 180th day is still the 180th day.
        assertThatCode(() -> defaultPolicy.verifyWithinHorizon(Instant.parse("2026-08-28T23:59:00Z"), UTC, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC1: the 181st day is refused, and the message names the limit")
    void bookingOneDayBeyondTheHorizonIsRefused() {
        assertThatThrownBy(() -> defaultPolicy.verifyWithinHorizon(Instant.parse("2026-08-29T00:01:00Z"), UTC, NOW))
                .isInstanceOf(BookingHorizonExceededException.class)
                .hasMessageContaining("181")
                .hasMessageContaining("180");
    }

    @Test
    @DisplayName("AC3: a configured horizon replaces the default on both sides of its own boundary")
    void configuredHorizonReplacesTheDefault() {
        BookingHorizonPolicy thirtyDays = new BookingHorizonPolicy(30);

        assertThatCode(() -> thirtyDays.verifyWithinHorizon(Instant.parse("2026-03-31T12:00:00Z"), UTC, NOW))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> thirtyDays.verifyWithinHorizon(Instant.parse("2026-04-01T12:00:00Z"), UTC, NOW))
                .isInstanceOf(BookingHorizonExceededException.class);
        // The same instant the default policy accepts without complaint.
        assertThatCode(() -> defaultPolicy.verifyWithinHorizon(Instant.parse("2026-04-01T12:00:00Z"), UTC, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC5: the facility's timezone decides, not UTC")
    void horizonIsMeasuredInFacilityLocalDays() {
        // 02:00Z is still the previous evening in Chicago, so the booking is made on 2026-02-28
        // locally and on 2026-03-01 in UTC. The start is 2026-08-28 in both zones. That is 181 days
        // from the local booking date and 180 from the UTC one — the two zones disagree, and the
        // facility's own zone is the one that counts.
        Instant now = Instant.parse("2026-03-01T02:00:00Z");
        Instant startAt = Instant.parse("2026-08-28T20:00:00Z");

        assertThatThrownBy(() -> defaultPolicy.verifyWithinHorizon(startAt, CHICAGO, now))
                .isInstanceOf(BookingHorizonExceededException.class)
                .hasMessageContaining("181");
        assertThatCode(() -> defaultPolicy.verifyWithinHorizon(startAt, UTC, now))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Only the forward bound is this policy's business")
    void startInThePastIsNotRefusedHere() {
        assertThatCode(() -> defaultPolicy.verifyWithinHorizon(Instant.parse("2020-01-01T09:00:00Z"), UTC, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A non-positive horizon is a misconfiguration, not an off switch")
    void nonPositiveHorizonFailsFast() {
        assertThatThrownBy(() -> new BookingHorizonPolicy(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> new BookingHorizonPolicy(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
