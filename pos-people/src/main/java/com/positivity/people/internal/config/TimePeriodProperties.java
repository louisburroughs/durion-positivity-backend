package com.positivity.people.internal.config;

import java.time.LocalDate;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Pay-period grid and rollover cadence (issue #1527). Periods are laid out on a fixed grid of
 * {@code periodLengthDays}-day windows anchored at {@code anchorDate}, so every service
 * instance computes identical period boundaries regardless of when it runs.
 */
@Data
@Component
@ConfigurationProperties(prefix = "pos.people.time-period")
public class TimePeriodProperties {

    /** Length of one pay period in days (biweekly by default). */
    private int periodLengthDays = 14;

    /** A known period start date anchoring the grid; any past or future date on the grid works. */
    private LocalDate anchorDate = LocalDate.of(2026, 6, 1);

    /** Days after a period's end date before an OPEN period moves to SUBMISSION_CLOSED. */
    private int submissionCloseGraceDays = 0;

    /** Days after a period's end date before a SUBMISSION_CLOSED period moves to PAYROLL_CLOSED. */
    private int payrollCloseGraceDays = 7;

    /** Upper bound on how many past periods one tenant's rollover pass may backfill. */
    private int maxBackfillPeriods = 12;
}
