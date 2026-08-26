package com.positivity.people.service;

import com.positivity.people.internal.dto.CreateTimePeriodRequest;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimePeriodRolloverResult;
import com.positivity.people.internal.enums.TimePeriodStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Pay-period lifecycle management (issue #1527). Periods used to exist only when a demo seed
 * inserted them; this service is the production write path: an operator API for explicit
 * creation and corrections, plus a scheduled rollover that keeps every tenant with timekeeping
 * activity covered by a period and advances period statuses as their dates pass.
 */
public interface TimePeriodManagementService {

    /**
     * Creates a pay period after validating the date range and rejecting any overlap with the
     * tenant's existing periods.
     */
    @NonNull
    TimePeriodDto createTimePeriod(@NonNull CreateTimePeriodRequest request);

    /**
     * Moves a period to a new lifecycle status. Allowed transitions: {@code OPEN ->
     * SUBMISSION_CLOSED}, {@code OPEN -> PAYROLL_CLOSED}, {@code SUBMISSION_CLOSED ->
     * PAYROLL_CLOSED}, and the correction path {@code SUBMISSION_CLOSED -> OPEN}.
     * {@code PAYROLL_CLOSED} is terminal.
     */
    @NonNull
    TimePeriodDto transitionTimePeriod(@NonNull UUID timePeriodId, @NonNull TimePeriodStatus targetStatus);

    /**
     * One rollover pass: advances period statuses whose dates (plus configured grace) have
     * passed, then creates any missing grid-aligned periods covering each tenant's timekeeping
     * entries up to the current date.
     */
    @NonNull
    TimePeriodRolloverResult runRollover();
}
