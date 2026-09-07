package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a role assignment
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to assign a role to a user for an effective window")
public class RoleAssignmentRequest {
    @NotNull
    @Schema(
            description = "Identifier of the user to assign the role to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID userId;

    @NotNull
    @Schema(
            description = "Identifier of the role to assign",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID roleId;

    @Schema(
            description = "Inclusive start of the effective window",
            example = "2026-01-15T00:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime effectiveStartDate;

    @Schema(
            description = "Exclusive end of the effective window",
            example = "2026-12-31T00:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime effectiveEndDate;
}
