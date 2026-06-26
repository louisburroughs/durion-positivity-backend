package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.EmployeeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Compliance finding: an ACTIVE user linked to a person whose status is inactive
 * (SUSPENDED, TERMINATED, or DISABLED). Per ADR-0015 §4, disabling/archiving a
 * person must disable their active user, so each row is a violation to remediate.
 */
@Data
@Builder
@Schema(description = "An active user-person link whose linked person is in an inactive status (ADR-0015 §4 violation)")
public class InactivePersonActiveUserResponse {

    @Schema(
            description = "User-person link identifier",
            example = "01960012-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID linkId;

    @Schema(
            description = "Username of the security user account with the still-active link",
            example = "marcus.webb",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(
            description = "Person identifier in an inactive status",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "Inactive status of the linked person",
            example = "TERMINATED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private EmployeeStatus personStatus;

    @Schema(
            description = "When the person's status last became effective",
            example = "2026-01-15T09:30:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant personStatusEffectiveAt;
}
