package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.exception.BookingHorizonExceededException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How far ahead an appointment may be booked (DECISION-SHOPMGMT-019, issue #2100).
 *
 * <p>The bound is deployment configuration — {@code pos.shop-manager.booking.max-advance-days},
 * overridable as {@code POS_SHOP_MANAGER_BOOKING_MAX_ADVANCE_DAYS} — defaulting to 180 days,
 * because the right horizon differs by trade: a tyre shop and a restoration shop do not book alike.
 * The <em>existence</em> of a horizon is not configurable. A deployment that wants effectively no
 * limit sets a large number; the check is never disabled, which is why a non-positive value is a
 * startup failure rather than an off switch.
 *
 * <h2>What this is not</h2>
 *
 * Two forward bounds already live in this module and are deliberately unrelated to this one. Do not
 * reuse either, and do not collapse them into one setting:
 *
 * <ul>
 *   <li>{@code OpeningSearchServiceImpl.MAX_HORIZON_DAYS} (30) bounds how far forward one
 *       availability <em>search</em> scans (#2022 AC11);
 *   <li>{@code ScheduleCapacityServiceImpl.MAX_RANGE_DAYS} (42) bounds the span of one capacity
 *       <em>read</em> (#2023 AC3).
 * </ul>
 *
 * <p>Those are read bounds. This is a write policy, enforced where the booking enters the system so
 * that an out-of-horizon appointment never reaches the database: a read-side filter would instead
 * leave a real, bay-holding row that some reads honour and others silently drop.
 *
 * <h2>Measured in facility-local days, from the write</h2>
 *
 * The comparison is between two <em>local dates</em> in the facility's zone — the date the booking
 * is being written and the date the appointment starts — so a shop 180 days out is inside the
 * horizon whatever the hour and whatever the caller's own timezone. It is evaluated against the
 * moment of the write rather than a stored {@code createdAt}, so re-saving an old appointment never
 * re-evaluates it against a date it was never booked on.
 */
@Component
public class BookingHorizonPolicy {

    private final int maxAdvanceDays;

    public BookingHorizonPolicy(@Value("${pos.shop-manager.booking.max-advance-days:180}") int maxAdvanceDays) {
        if (maxAdvanceDays < 1) {
            throw new IllegalArgumentException("pos.shop-manager.booking.max-advance-days must be at least 1, was "
                    + maxAdvanceDays + "; to allow bookings far ahead, configure a large horizon rather than"
                    + " a non-positive one (DECISION-SHOPMGMT-019)");
        }
        this.maxAdvanceDays = maxAdvanceDays;
    }

    public int maxAdvanceDays() {
        return maxAdvanceDays;
    }

    /**
     * Refuses a booking whose start lies beyond the horizon.
     *
     * <p>Only the forward bound is checked. A start in the past is not this policy's business: it is
     * governed by the conflict rules and by the shop's own judgement, and an early start is
     * explicitly legitimate (DECISION-SHOPMGMT-020).
     *
     * @throws BookingHorizonExceededException when {@code startAt} is more than the configured
     *     number of facility-local days after {@code now}
     */
    public void verifyWithinHorizon(@NonNull Instant startAt, @NonNull ZoneId facilityZone, @NonNull Instant now) {
        LocalDate bookedOn = LocalDate.ofInstant(now, facilityZone);
        LocalDate startsOn = LocalDate.ofInstant(startAt, facilityZone);
        long daysAhead = ChronoUnit.DAYS.between(bookedOn, startsOn);
        if (daysAhead > maxAdvanceDays) {
            throw new BookingHorizonExceededException(maxAdvanceDays, daysAhead);
        }
    }
}
