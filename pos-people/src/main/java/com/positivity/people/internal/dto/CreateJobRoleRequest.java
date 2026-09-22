package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Request to add a job role to the calling tenant's own list")
public class CreateJobRoleRequest {

    @NotBlank(message = "code is required")
    @Size(max = 64, message = "code must be at most 64 characters")
    @Schema(description = "Tenant's own short code", example = "LEAD_TECH", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank(message = "name is required")
    @Schema(
            description = "Display name",
            example = "Lead Technician",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 1000, message = "description must be at most 1000 characters")
    @Schema(
            description = "Optional description of the role's responsibilities",
            example = "Senior technician who leads a repair bay",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;
}
