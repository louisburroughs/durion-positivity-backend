package com.positivity.peoplecontact.internal.client.dto;

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
 * Outbound body for {@code POST /v1/roles/assignments}, mirroring pos-security-service's own
 * {@code RoleAssignmentRequest}.
 *
 * <p>Under ADR-0061 a role assignment is an effective-dated user-to-role link and nothing more.
 * It carries no location scope: a person's location reach is the assigned role's own
 * {@code location_scope} combined with that person's pos-people staffing assignment, resolved at
 * token issuance. The {@code scopeType}/{@code scopeLocationIds} fields this DTO used to send
 * were deleted from pos-security-service by issue #1875; because Spring silently drops unknown
 * body fields, continuing to send them did not fail — it just quietly produced an unscoped
 * assignment while the caller believed it was location-scoped.
 *
 * <p>The effective dates are {@link LocalDateTime}, not {@link java.time.LocalDate}: the
 * downstream fields are {@code LocalDateTime} and Jackson cannot widen a date-only
 * {@code "2026-01-01"} into one, so a date-only value is rejected downstream as a 400 rather
 * than being coerced to midnight.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to assign a role to a user in the security service")
public class RoleAssignmentRequest {

    @NotNull(message = "userId is required")
    @Schema(
            description = "User identifier the role is assigned to",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID userId;

    @NotNull(message = "roleId is required")
    @Schema(
            description = "Role identifier to assign",
            example = "01960011-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID roleId;

    @Schema(
            description = "Inclusive start of the effective window",
            example = "2026-01-01T00:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime effectiveStartDate;

    @Schema(
            description = "Exclusive end of the effective window",
            example = "2026-12-31T00:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime effectiveEndDate;
}
