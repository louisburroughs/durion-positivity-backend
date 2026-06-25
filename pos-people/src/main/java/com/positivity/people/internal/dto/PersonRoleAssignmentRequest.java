package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to assign a role to a person")
public class PersonRoleAssignmentRequest {

    @NotBlank
    @Schema(
            description = "Stable role code to assign",
            example = "TECHNICIAN",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String roleCode;

    @Schema(
            description = "Location identifier the role is scoped to",
            example = "01960011-0000-7000-8000-000000000010",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Date and time the assignment becomes effective",
            example = "2026-01-01T00:00:00",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDateTime startDate;

    @Schema(
            description = "Date and time the assignment ends",
            example = "2026-12-31T23:59:59",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDateTime endDate;
}
