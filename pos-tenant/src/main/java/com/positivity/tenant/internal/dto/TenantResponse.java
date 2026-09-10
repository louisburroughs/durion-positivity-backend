package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.tenant.internal.enums.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A tenant as platform staff see it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A tenant of the platform")
public class TenantResponse {

    @Schema(description = "Tenant id; the value every module's tenant_id refers to", requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "URL-safe unique name", example = "acme-tire", requiredMode = REQUIRED)
    private String slug;

    @Schema(description = "Human-readable name", example = "Acme Tire & Auto", requiredMode = REQUIRED)
    private String displayName;

    @Schema(description = "Lifecycle status", requiredMode = REQUIRED)
    private TenantStatus status;

    @Schema(description = "Owning account id", requiredMode = REQUIRED)
    private UUID accountId;

    @Schema(description = "Cell or region the tenant is served from", requiredMode = NOT_REQUIRED)
    private String cell;

    @Schema(description = "Email of the initial administrator", requiredMode = REQUIRED)
    private String initialAdminEmail;

    @Schema(description = "Registered at (ISO 8601)", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last changed at (ISO 8601)", requiredMode = REQUIRED)
    private Instant updatedAt;

    @Schema(description = "First became ACTIVE at (ISO 8601)", requiredMode = NOT_REQUIRED)
    private Instant activatedAt;

    @Schema(description = "Last suspended at (ISO 8601)", requiredMode = NOT_REQUIRED)
    private Instant suspendedAt;

    @Schema(description = "Decommissioned at (ISO 8601)", requiredMode = NOT_REQUIRED)
    private Instant decommissionedAt;
}
