package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** One package membership within a bulk-ingest payload, naming both sides by their codes. */
@Data
@Schema(description = "Single package-membership record within a bulk ingest payload")
public class ServicePackageMemberBulkIngestRecord {

    @Schema(
            description = "Code of the package the operation belongs to",
            example = "TIRE-INSTALL-PKG-4",
            requiredMode = REQUIRED)
    @NotBlank
    private String packageCode;

    @Schema(
            description = "Durion operation code of the member service",
            example = "WHEEL-BALANCE-SET-4",
            requiredMode = REQUIRED)
    @NotBlank
    private String operationCode;

    @Schema(
            description = "Position in the package; blank appends after the current last member",
            example = "20",
            requiredMode = NOT_REQUIRED)
    private String sequence;

    @Schema(description = "How many times the operation is performed", example = "1.00", requiredMode = NOT_REQUIRED)
    private String quantity;

    @Schema(
            description = "Whether the member is part of the package or an offered add-on; defaults to true",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private String required;
}
