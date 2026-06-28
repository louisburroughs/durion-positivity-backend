package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for permission registration
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Outcome of a permission catalog registration request")
public class PermissionRegistrationResponse {
    @Schema(
            description = "True when the registration completed without fatal errors",
            example = "true",
            requiredMode = REQUIRED)
    private boolean success;

    @Schema(
            description = "Human-readable summary of the registration outcome",
            example = "Registered 12 permissions, updated 3",
            requiredMode = NOT_REQUIRED)
    private String message;

    @Schema(
            description = "Total number of permissions submitted in the manifest",
            example = "15",
            requiredMode = REQUIRED)
    private int totalPermissions;

    @Schema(description = "Number of new permissions registered", example = "12", requiredMode = REQUIRED)
    private int registeredPermissions;

    @Schema(description = "Number of existing permissions updated", example = "3", requiredMode = REQUIRED)
    private int updatedPermissions;

    @Schema(
            description = "Number of permissions skipped because they were unchanged",
            example = "0",
            requiredMode = REQUIRED)
    private int skippedPermissions;

    @Schema(description = "Per-permission error messages, when any occurred", requiredMode = NOT_REQUIRED)
    private List<String> errors;
}
