package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** A job role on the calling tenant's own list (durion#2157) -- HR master data, not a permission. */
@Value
@Builder
@Schema(description = "A tenant-defined job role, e.g. 'Lead Technician' or 'Parts Counter'")
public class JobRoleDto {

    @Schema(description = "Job role id", requiredMode = REQUIRED)
    UUID id;

    @Schema(description = "Tenant's own short code", example = "LEAD_TECH", requiredMode = REQUIRED)
    String code;

    @Schema(description = "Display name", example = "Lead Technician", requiredMode = REQUIRED)
    String name;

    @Schema(
            description = "Optional description of the role's responsibilities",
            example = "Senior technician who leads a repair bay",
            requiredMode = NOT_REQUIRED)
    String description;

    @Schema(description = "Whether the role can still be assigned to employees", requiredMode = REQUIRED)
    boolean active;

    @Schema(requiredMode = REQUIRED)
    Instant createdAt;

    @Schema(requiredMode = REQUIRED)
    Instant updatedAt;
}
