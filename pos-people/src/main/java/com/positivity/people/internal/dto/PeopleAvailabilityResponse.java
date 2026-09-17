package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.ClockState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Availability of a person at a location for a given date based on staffing assignments")
public class PeopleAvailabilityResponse {

    @Schema(
            description = "Person identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(description = "First name of the person", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String firstName;

    @Schema(description = "Last name of the person", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String lastName;

    @Schema(
            description = "Location identifier",
            example = "01960011-0000-7000-8000-000000000010",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Assignment role at the location",
            example = "TECHNICIAN",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String role;

    @Schema(
            description = "Whether this is the person's primary assignment",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean primary;

    @Schema(
            description = "Lifecycle status of the assignment",
            example = "ACTIVE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private AssignmentStatus assignmentStatus;

    @Schema(
            description = "Date the assignment becomes effective",
            example = "2026-02-01",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate effectiveFrom;

    @Schema(
            description = "Date the assignment ends",
            example = "2026-12-31",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate effectiveTo;

    @Schema(
            description = "Date the availability was evaluated for",
            example = "2026-02-16",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate availableOn;

    // ---- Current work-session (clock) state (issue #2061) ---------------------------------
    // Derived on read from the open WorkSession and WorkSessionBreak, resolved for the whole
    // page in a bounded number of queries. Present only for rows the caller may see: their own
    // person, or any person when they hold people:timekeeping:view covering the row's location.

    @Schema(
            description = "The person's current clock state: CLOCKED_IN (open work session, no open break),"
                    + " ON_BREAK (open session with an open break) or CLOCKED_OUT (no open session). Null when"
                    + " the caller may not see this person's clock state: it is shown for the caller's own row,"
                    + " and for every row when the caller holds people:timekeeping:view covering the location.",
            example = "CLOCKED_IN",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private ClockState clockState;

    @Schema(
            description = "The open work session; non-null exactly when clockState is CLOCKED_IN or ON_BREAK."
                    + " The break endpoints are keyed by this id.",
            example = "01960011-0000-7000-8000-000000000002",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID workSessionId;

    @Schema(
            description = "When the open work session started; non-null exactly when workSessionId is",
            example = "2026-02-16T08:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant clockedInAt;

    @Schema(
            description = "When the open break started; non-null exactly when clockState is ON_BREAK",
            example = "2026-02-16T12:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant breakStartedAt;
}
