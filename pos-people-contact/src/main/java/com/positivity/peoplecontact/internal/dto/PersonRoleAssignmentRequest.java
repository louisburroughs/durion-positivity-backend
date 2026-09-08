package com.positivity.peoplecontact.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /v1/people/{personUuid}/access/assignments}.
 *
 * <p>There is no {@code locationId}. Under ADR-0061 a role assignment is an effective-dated
 * user-to-role link and carries no location of its own: a person's location reach is the
 * assigned role's {@code location_scope} combined with that person's pos-people staffing
 * assignment, resolved at token issuance. The field was forwarded to pos-security-service as a
 * {@code scopeType}/{@code scopeLocationIds} scope that issue #1875 deleted there, so supplying
 * it never changed the resulting grant; it is gone from the published contract rather than kept
 * as an input this module knowingly disregards.
 */
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
