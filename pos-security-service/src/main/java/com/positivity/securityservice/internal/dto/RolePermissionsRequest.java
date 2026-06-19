package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating role permissions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to replace the permission set granted to a role")
public class RolePermissionsRequest {
    @NotNull
    @Schema(
            description = "Identifier of the role to update",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID roleId;

    @NotNull
    @Schema(
            description = "Replacement set of permission names in format domain:resource:action",
            example = "[\"people:timekeeping:view\", \"people:timekeeping:edit\"]",
            requiredMode = REQUIRED)
    private Set<String> permissionNames;
}
