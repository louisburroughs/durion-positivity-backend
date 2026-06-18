package com.positivity.people.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Role assignment as returned by the security service")
public class RoleAssignment {

    @Schema(
            description = "Role assignment identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "User the role is assigned to", requiredMode = NOT_REQUIRED)
    private User user;

    @Schema(description = "Role that was assigned", requiredMode = NOT_REQUIRED)
    private Role role;

    @Schema(description = "Scope at which the role applies", example = "LOCATION", requiredMode = NOT_REQUIRED)
    private ScopeType scopeType;

    @Schema(
            description = "Location identifiers the role is scoped to when scopeType is LOCATION",
            example = "[\"01960011-0000-7000-8000-000000000010\"]",
            requiredMode = NOT_REQUIRED)
    private Set<UUID> scopeLocationIds;

    @Schema(description = "Date the assignment becomes effective", example = "2026-01-01", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveStartDate;

    @Schema(description = "Date the assignment ends", example = "2026-12-31", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveEndDate;

    @Schema(description = "Creation timestamp in UTC", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Identifier of the actor that created the assignment", example = "manager.user", requiredMode = NOT_REQUIRED)
    private String createdBy;
}
