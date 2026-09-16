package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Assembles {@code GET /v1/schedules/capacity} (issue #2023): per-day, per-bay occupancy across a
 * bounded date range, in a fixed number of database round trips regardless of the range length.
 */
public interface ScheduleCapacityService {

    /**
     * Builds the capacity view for {@code [from, to]}, inclusive on both ends.
     *
     * @param locationId the location whose bays and hours are read
     * @param from first date in the range
     * @param to last date in the range; must not be before {@code from}
     * @return a response carrying exactly one entry per date in the range, never omitting one
     * @throws com.positivity.shopmanager.internal.exception.ShopManagerValidationException if
     *     {@code to} is before {@code from}
     * @throws com.positivity.shopmanager.internal.exception.ScheduleCapacityRangeExceededException
     *     if the range spans more than the 42-day policy limit
     */
    @NonNull
    ScheduleCapacityResponse getCapacity(@NonNull UUID locationId, @NonNull LocalDate from, @NonNull LocalDate to);
}
