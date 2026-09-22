package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * One application-role assignment shown on the employee register (durion#2155), projected from
 * the {@code ext_role_assignment_replica} replica (ADR-0044 §6, durion#2160).
 *
 * <p>A small nested object rather than a bare role id or name, matching the precedent {@link
 * EmployeeJobRoleDto} already set for {@code EmployeeProfileDto}: a register row needs enough of
 * the assignment to render without a second call, and leaves room to grow.
 *
 * <p>{@code roleLocationScope} is nullable and, as of this DTO, always null — {@code
 * RoleAssignmentChangedV1} does not carry the role's location scope yet (durion#2160 is still in
 * flight); see {@link com.positivity.people.internal.entity.ExtRoleAssignmentReplica}'s javadoc.
 */
@Value
@Builder
@Schema(description = "One application-role assignment shown on the employee register")
public class EmployeeRoleAssignmentDto {

    @Schema(description = "Role assignment id", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID assignmentId;

    @Schema(description = "Assigned role id", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID roleId;

    @Schema(description = "Assigned role name", example = "SHOP_MANAGER", requiredMode = Schema.RequiredMode.REQUIRED)
    String roleName;

    @Schema(
            description =
                    "The role's location scope, when known. Null today — not yet carried by the upstream event.",
            nullable = true)
    String roleLocationScope;

    @Schema(description = "When this assignment becomes or became effective", requiredMode = Schema.RequiredMode.REQUIRED)
    LocalDateTime effectiveStartDate;

    @Schema(description = "Exclusive end of the effective window; null while open-ended", nullable = true)
    LocalDateTime effectiveEndDate;

    @Schema(description = "When the revocation was entered; null while the assignment stands", nullable = true)
    Instant revokedAt;
}
