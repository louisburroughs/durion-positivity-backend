package com.positivity.peoplecontact.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A person's role assignment as this module hands it back to callers.
 *
 * <p>There is no {@code locationId} here. Under ADR-0061 an assignment is an effective-dated
 * user-to-role link and carries no location; the location reach it used to imply comes from the
 * role's own {@code location_scope} combined with the person's pos-people staffing assignment,
 * resolved at token issuance. The field was mapped from a {@code scopeLocationIds} that
 * pos-security-service stopped returning (issue #1875), so it read as null on every response.
 *
 * <p>The effective window is a {@link LocalDateTime}, matching both the {@code startDate}/{@code
 * endDate} callers send on {@code PersonRoleAssignmentRequest} and the {@code LocalDateTime}
 * window pos-security-service stores, so a time-of-day a caller supplied is echoed back rather
 * than truncated to its date.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "User role assignment as exposed by the security service")
public class UserRoleDto {

    @Schema(
            description = "User identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String userId;

    @Schema(description = "Stable role code", example = "TECHNICIAN", requiredMode = NOT_REQUIRED)
    private String roleCode;

    @Schema(
            description = "Inclusive start of the effective window",
            example = "2026-01-01T00:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime startDate;

    @Schema(
            description = "Exclusive end of the effective window",
            example = "2026-12-31T23:59:59",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime endDate;

    @Schema(description = "Whether the assignment is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;
}
