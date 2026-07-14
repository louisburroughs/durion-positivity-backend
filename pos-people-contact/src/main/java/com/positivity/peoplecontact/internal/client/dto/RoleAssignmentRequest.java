package com.positivity.peoplecontact.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to assign a role to a user in the security service")
public class RoleAssignmentRequest {

    @Schema(
            description = "User identifier the role is assigned to",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID userId;

    @NotNull(message = "roleId is required")
    @Schema(
            description = "Role identifier to assign",
            example = "01960011-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID roleId;

    @Schema(description = "Scope at which the role applies", example = "GLOBAL", requiredMode = NOT_REQUIRED)
    private ScopeType scopeType = ScopeType.GLOBAL;

    @Schema(
            description = "Location identifiers the role is scoped to when scopeType is LOCATION",
            example = "[\"01960011-0000-7000-8000-000000000010\"]",
            requiredMode = NOT_REQUIRED)
    private Set<String> scopeLocationIds;

    @Schema(description = "Date the assignment becomes effective", example = "2026-01-01", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveStartDate;

    @Schema(description = "Date the assignment ends", example = "2026-12-31", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveEndDate;
}
