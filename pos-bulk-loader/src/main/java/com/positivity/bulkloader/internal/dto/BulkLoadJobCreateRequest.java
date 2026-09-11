package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.bulkloader.internal.enums.DomainType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request to create a new bulk load job")
public class BulkLoadJobCreateRequest {

    @NotBlank
    @Schema(
            description = "Name of the source file to be uploaded for this job",
            example = "products-2026-01.csv",
            requiredMode = REQUIRED)
    private String fileName;

    @NotNull
    @Schema(description = "Target domain for the bulk load", example = "CATALOG_PRODUCT", requiredMode = REQUIRED)
    private DomainType domainType;

    @Schema(
            description = "Identifier of the location to scope the load to, if applicable",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Tenant the job loads into (ADR-0062). Every row the job writes and every call it makes"
                    + " to a sibling service is bound to this tenant. Must be an active tenant of the cell, or the"
                    + " platform tenant for platform data such as the role template's roles.csv. A caller bound to"
                    + " a tenant may only name its own tenant; a platform-tenant caller may name any. Omitting it"
                    + " is a 400 BULK_JOB_TENANT_REQUIRED unless the transitional default tenant is configured, in"
                    + " which case the default is used and logged at WARN.",
            example = "01900000-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID tenantId;
}
