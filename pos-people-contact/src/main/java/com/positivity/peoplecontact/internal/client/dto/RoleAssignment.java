package com.positivity.peoplecontact.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wire shape of pos-security-service's {@code RoleAssignmentDto}, returned by
 * {@code POST /v1/roles/assignments} and {@code GET /v1/roles/assignments/user/{userId}}.
 *
 * <p>The user and the role arrive as flat {@code userId}/{@code roleId} identifiers, not as
 * nested objects. This DTO used to declare nested {@code user}/{@code role} objects, which
 * Jackson simply left null — so {@code assignment.getUser().getId()} read as "no user" on every
 * response rather than failing loudly.
 *
 * <p>Under ADR-0061 an assignment has no location scope, so there is no {@code scopeType} or
 * {@code scopeLocationIds} here; issue #1875 deleted both downstream.
 *
 * <p>{@code roleCode} is the role's stable name, returned alongside {@code roleId} by
 * pos-security-service (issue #1886). Without it a listing could only be rendered by resolving
 * every {@code roleId} through the role catalog, and the code is what callers act on: revocation
 * addresses an assignment by role code, not by role id.
 *
 * <p>The audit metadata pos-security-service also returns ({@code revokedAt}, {@code createdAt},
 * {@code createdBy}, {@code lastModifiedAt}, {@code lastModifiedBy}) is deliberately not mapped:
 * nothing in this module reads it, and {@code ignoreUnknown} absorbs it. {@code revokedAt} in
 * particular is not the revocation marker its name suggests — pos-security-service stamps it
 * from the {@code effectiveEndDate} setter, so an ordinary bounded assignment carries one from
 * the moment it is created — so an active flag must not be derived from it.
 */
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

    @Schema(
            description = "Identifier of the user the role is assigned to",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID userId;

    @Schema(
            description = "Identifier of the assigned role",
            example = "01960011-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID roleId;

    @Schema(
            description = "Stable code of the assigned role, identical to the role's name",
            example = "TECHNICIAN",
            requiredMode = REQUIRED)
    private String roleCode;

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
