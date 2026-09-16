package com.positivity.shopmanager.internal.exception;

/**
 * The {@code from}/{@code to} span requested from {@code GET /v1/schedules/capacity} exceeds the
 * 42-day policy limit (issue #2023 AC3).
 *
 * <p>Mapped to {@code 422 Unprocessable Content}, not {@code 400}: the request is syntactically
 * valid (both dates parse and {@code to} is not before {@code from}) but violates a business policy
 * limit, per DECISION-SHOPMGMT-011 ("422 for business rule/policy failures, 400 for syntactic
 * validation") and the same distinction {@code LocationServiceImpl} draws for its own policy
 * checks.
 */
public class ScheduleCapacityRangeExceededException extends RuntimeException {

    public static final String CODE = "CAPACITY_RANGE_EXCEEDED";

    public ScheduleCapacityRangeExceededException(int maxDays, long requestedDays) {
        super("Requested range spans " + requestedDays + " day(s), exceeding the maximum of " + maxDays
                + " days; request the calendar in bounded windows of " + maxDays + " days or fewer.");
    }
}
