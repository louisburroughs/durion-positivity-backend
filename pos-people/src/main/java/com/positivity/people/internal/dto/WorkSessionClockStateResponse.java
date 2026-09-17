package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ClockState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One person's current work-session (clock) state, derived from the open session and break
 * (issue #2061). The same four fields ride on each {@link PeopleAvailabilityResponse} row.
 */
@Data
@Builder
@Schema(
        description = "A person's current work-session (clock) state, derived from their open work session and"
                + " open break. CLOCKED_OUT is an answer, not an error: it is returned when nothing is open.")
public class WorkSessionClockStateResponse {

    @Schema(
            description = "Person the state belongs to",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "CLOCKED_IN (open session, no open break), ON_BREAK (open session with an open break)"
                    + " or CLOCKED_OUT (no open session)",
            example = "CLOCKED_IN",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ClockState clockState;

    @Schema(
            description = "The open work session; non-null exactly when clockState is CLOCKED_IN or ON_BREAK. The"
                    + " break endpoints are keyed by this id.",
            example = "01960011-0000-7000-8000-000000000002",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID workSessionId;

    @Schema(
            description = "When the open session started; non-null exactly when workSessionId is",
            example = "2026-02-16T08:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant clockedInAt;

    @Schema(
            description = "When the open break started; non-null exactly when clockState is ON_BREAK",
            example = "2026-02-16T12:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant breakStartedAt;
}
