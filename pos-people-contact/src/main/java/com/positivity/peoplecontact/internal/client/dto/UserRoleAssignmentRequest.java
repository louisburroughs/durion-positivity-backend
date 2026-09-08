package com.positivity.peoplecontact.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This module's role-code-shaped input to {@link
 * com.positivity.peoplecontact.internal.client.SecurityServiceClient#assignRole}, which resolves
 * the code to a role id before posting a {@link RoleAssignmentRequest}.
 *
 * <p>Under ADR-0061 an assignment carries no location: the {@code locationId}/{@code
 * locationIds} this DTO used to hold were folded into a {@code scopeType}/{@code
 * scopeLocationIds} body that pos-security-service no longer reads (issue #1875), so they
 * changed nothing about the resulting grant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to assign a role to a user by role code in the security service")
public class UserRoleAssignmentRequest {

    @Schema(
            description = "User identifier the role is assigned to",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID userId;

    @Schema(description = "Stable role code to assign", example = "TECHNICIAN", requiredMode = NOT_REQUIRED)
    private String roleCode;

    @Schema(
            description = "Date and time the assignment becomes effective",
            example = "2026-01-01T00:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime startDate;

    @Schema(
            description = "Date and time the assignment ends",
            example = "2026-12-31T23:59:59",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime endDate;
}
