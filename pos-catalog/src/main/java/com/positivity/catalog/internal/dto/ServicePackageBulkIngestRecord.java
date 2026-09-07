package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One service package within a bulk-ingest payload, keyed by its package code.
 *
 * <p>Membership is not carried here. A package and its members are two files and two calls, so
 * re-loading the package pack cannot silently empty a package's membership, and a member row
 * whose operation does not exist fails only itself.
 */
@Data
@Schema(description = "Single service-package record within a bulk ingest payload")
public class ServicePackageBulkIngestRecord {

    @Schema(
            description = "Package code; the natural key the row is upserted on",
            example = "TIRE-INSTALL-PKG-4",
            requiredMode = REQUIRED)
    @NotBlank
    private String packageCode;

    @Schema(
            description = "Package name shown to a writer",
            example = "Four Tire Installation Package",
            requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(
            description = "What the package covers and why it is priced as one job",
            example = "Mount and balance four tires, reset TPMS and torque to specification.",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Whether the package is the platform's or one shop's own",
            example = "PLATFORM",
            allowableValues = {"PLATFORM", "SHOP"},
            requiredMode = NOT_REQUIRED)
    private String ownerScope;

    @Schema(
            description = "Owning location, required for a SHOP package and rejected on a PLATFORM one",
            example = "0198f2a1-0000-7000-8000-00000000000a",
            requiredMode = NOT_REQUIRED)
    private String ownerLocationId;

    @Schema(
            description = "Fleet account this is a standing requirement set for; blank makes it an"
                    + " offering. A file that names the fleet rather than its id has the loader resolve"
                    + " it against the party directory before the batch is posted",
            example = "0198f2a1-0000-7000-8000-0000000000f1",
            requiredMode = NOT_REQUIRED)
    private String fleetPartyId;

    @Schema(description = "Authored labor hours for the package, in tenths", example = "1.6", requiredMode = REQUIRED)
    @NotBlank
    private String packageLaborHours;

    @Schema(description = "Whether the package is sellable", example = "true", requiredMode = NOT_REQUIRED)
    private String active;

    @Schema(description = "First date the package may be sold", example = "2026-01-01", requiredMode = NOT_REQUIRED)
    private String effectiveFrom;

    @Schema(description = "Last date the package may be sold", example = "2026-12-31", requiredMode = NOT_REQUIRED)
    private String effectiveTo;
}
