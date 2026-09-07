package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One labor standard within a bulk-ingest payload, addressed by the operation code it belongs to
 * and carrying its own provenance.
 *
 * <p>Provenance is what separates this from {@link ServiceLaborStandardRequestDto}, which is the
 * hand-authoring shape: a row authored through that API is DURION-source by definition and is
 * stamped with the moment it was written, whereas an imported row is a statement about what some
 * named source published in some named revision. The two are different authorities, which is why
 * they are different request shapes behind different permissions — {@code catalog:labor_standard:manage}
 * for authoring, {@code catalog:labor_standard:import} for this.
 *
 * <p>Every field is text so that a value which will not parse fails its own row rather than the
 * batch (docs/DATA_SEED_STRATEGY.md §3).
 */
@Data
@Schema(description = "Single labor-standard record within a bulk ingest payload, with its source provenance")
public class ServiceLaborStandardImportRequestDto {

    @Schema(
            description = "Durion operation code of the service the standard belongs to",
            example = "TIRE-INSTALL-LT-SET-4",
            requiredMode = REQUIRED)
    @NotBlank
    private String operationCode;

    @Schema(
            description = "Source that published this time; part of the row's provenance",
            example = "DURION",
            requiredMode = REQUIRED)
    @NotBlank
    private String sourceCode;

    @Schema(
            description = "The source's revision this row came from; a row already active under the"
                    + " same key and revision is a no-op, and a new revision supersedes it",
            example = "tier0-fake-2026-09",
            requiredMode = REQUIRED)
    @NotBlank
    private String sourceRevision;

    @Schema(
            description = "Model year the time applies to; blank matches every year",
            example = "2019",
            requiredMode = NOT_REQUIRED)
    private String vehicleYear;

    @Schema(description = "Vehicle make; blank matches every make", example = "Ford", requiredMode = NOT_REQUIRED)
    private String make;

    @Schema(description = "Vehicle model; blank matches every model", example = "F-350", requiredMode = NOT_REQUIRED)
    private String model;

    @Schema(
            description = "Vehicle submodel; blank matches every submodel",
            example = "XLT",
            requiredMode = NOT_REQUIRED)
    private String submodel;

    @Schema(description = "Engine code; blank matches every engine", example = "6.7L-PSD", requiredMode = NOT_REQUIRED)
    private String engineCode;

    @Schema(description = "Labor time in decimal hours, in tenths", example = "1.6", requiredMode = REQUIRED)
    @NotBlank
    private String laborHours;

    @Schema(description = "Kind of time this row states", example = "DURION_STANDARD", requiredMode = NOT_REQUIRED)
    private String timeType;

    @Schema(
            description = "Overlap group; operations sharing one bill their shared setup once",
            example = "WHEEL-OFF",
            requiredMode = NOT_REQUIRED)
    private String overlapGroup;

    @Schema(
            description = "Comma-separated operation codes this time already includes, which"
                    + " therefore contribute nothing when billed alongside it",
            example = "WHEEL-BALANCE-SET-4,TPMS-SENSOR-SERVICE",
            requiredMode = NOT_REQUIRED)
    private String includedOpCodes;

    @Schema(
            description = "Whether the row is the platform's answer or one shop's own",
            example = "PLATFORM",
            allowableValues = {"PLATFORM", "SHOP"},
            requiredMode = NOT_REQUIRED)
    private String ownerScope;

    @Schema(
            description = "Owning location, required for a SHOP row and rejected on a PLATFORM one",
            example = "0198f2a1-0000-7000-8000-00000000000a",
            requiredMode = NOT_REQUIRED)
    private String ownerLocationId;

    @Schema(description = "Date the source published this time", example = "2026-09-01", requiredMode = NOT_REQUIRED)
    private String publishedAt;
}
