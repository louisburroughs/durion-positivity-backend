package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.people.internal.enums.TimeEntryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One attendance record — clock-in, clock-out and the break time inside that window — as the
 * approvals queue reads it (#1573).
 *
 * <p>A time entry is attendance only. Time spent on a workorder is a separate record kept by
 * pos-workorder, so nothing here references a workorder, and this surface must not be used to
 * answer "how long did this job take".
 *
 * <p>{@code decisionBy} and {@code decisionAt} collapse the entity's approved/rejected pairs
 * into the single decision the story's table shows. Which decision it was is already carried by
 * {@code status}, so a second pair of columns would only restate it; {@code rejectionReason}
 * remains separate because it exists for one of the two outcomes.
 */
@Schema(description = "An attendance time entry (clock-in, clock-out, breaks) with its approval decision")
public record TimeEntrySummary(
        @Schema(description = "Time entry identifier", requiredMode = REQUIRED)
        UUID timeEntryId,

        @Schema(description = "Person the attendance belongs to")
        UUID employeeId,

        @Schema(description = "Location the person was clocked in at")
        UUID locationId,

        @Schema(
                description = "Calendar day of the clock-in, in the zone the query was filtered by",
                example = "2026-01-15")
        LocalDate workDate,

        @Schema(description = "Clock-in instant") Instant startAtUtc,

        @Schema(description = "Clock-out instant; null while the person is still clocked in")
        Instant endAtUtc,

        @Schema(
                description = "Break minutes taken inside the attendance window; the window is gross time",
                example = "30")
        Integer breakMinutes,

        @Schema(description = "Approval status", requiredMode = REQUIRED)
        TimeEntryStatus status,

        @Schema(description = "When the employee submitted the entry for approval")
        Instant submittedAtUtc,

        @Schema(description = "Who approved or rejected; null while no decision has been taken")
        String decisionByUserId,

        @Schema(description = "When the entry was approved or rejected")
        Instant decisionAtUtc,

        @Schema(description = "Why the entry was rejected; present only when status is REJECTED")
        String rejectionReason,

        @Schema(description = "Work session that produced the entry, when it came from the clock surface")
        UUID workSessionId) {}
