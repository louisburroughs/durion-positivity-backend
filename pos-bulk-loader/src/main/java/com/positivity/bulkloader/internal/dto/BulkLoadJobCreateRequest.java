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
}
