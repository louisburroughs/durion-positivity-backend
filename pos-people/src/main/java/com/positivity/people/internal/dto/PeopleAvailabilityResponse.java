package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AssignmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Availability of a person at a location for a given date based on staffing assignments")
public class PeopleAvailabilityResponse {

    @Schema(description = "Person identifier", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(description = "First name of the person", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String firstName;

    @Schema(description = "Last name of the person", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String lastName;

    @Schema(description = "Location identifier", example = "01960011-0000-7000-8000-000000000010", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(description = "Assignment role at the location", example = "TECHNICIAN", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String role;

    @Schema(description = "Whether this is the person's primary assignment", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean primary;

    @Schema(description = "Lifecycle status of the assignment", example = "ACTIVE", requiredMode = Schema.RequiredMode.REQUIRED)
    private AssignmentStatus assignmentStatus;

    @Schema(description = "Date the assignment becomes effective", example = "2026-02-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate effectiveFrom;

    @Schema(description = "Date the assignment ends", example = "2026-12-31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate effectiveTo;

    @Schema(description = "Date the availability was evaluated for", example = "2026-02-16", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate availableOn;
}
