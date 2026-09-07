package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(
        description = "A named set of operations sold together. With fleetPartyId set it is that account's"
                + " requirement set rather than a general offering.")
public class ServicePackageRequestDto {

    @NotBlank
    @Size(max = 64)
    @Schema(
            description = "Stable package identity, unique platform-wide",
            example = "TIRE-INSTALL-PKG-4",
            requiredMode = REQUIRED)
    private String packageCode;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Display name", example = "Four Tire Installation Package", requiredMode = REQUIRED)
    private String name;

    @Schema(description = "What the package covers", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "PLATFORM (every location sells it) or SHOP (one location's own); defaults to PLATFORM",
            example = "PLATFORM",
            allowableValues = {"PLATFORM", "SHOP"},
            requiredMode = NOT_REQUIRED)
    private String ownerScope;

    @Schema(
            description = "Owning location; required when ownerScope is SHOP, rejected otherwise",
            requiredMode = NOT_REQUIRED)
    private UUID ownerLocationId;

    @Schema(
            description = "Fleet account this package is the requirement set for; omit for a general offering",
            requiredMode = NOT_REQUIRED)
    private UUID fleetPartyId;

    @Schema(
            description = "Authored labor hours for the package as sold, decimal hours in tenths. Not derived"
                    + " from the members: a shop prices a package as a number it chose.",
            example = "1.2",
            requiredMode = NOT_REQUIRED)
    private BigDecimal packageLaborHours;

    @Schema(description = "Whether the package is currently offered; defaults to true", requiredMode = NOT_REQUIRED)
    private Boolean active;

    @Schema(description = "First day the package is offered", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveFrom;

    @Schema(description = "Last day the package is offered", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveTo;
}
