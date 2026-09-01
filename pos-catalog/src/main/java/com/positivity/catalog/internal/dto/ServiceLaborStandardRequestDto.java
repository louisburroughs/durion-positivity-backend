package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

@Data
@Schema(
        description = "A hand-authored (DURION-source) labor standard for a service operation."
                + " Vehicle-key fields left null are wildcards: the time applies to any vehicle"
                + " the more specific fields do not narrow.")
public class ServiceLaborStandardRequestDto {

    @Size(max = 16)
    @Schema(
            description = "Model year or year range the time applies to",
            example = "2019-2023",
            requiredMode = NOT_REQUIRED)
    private String vehicleYear;

    @Size(max = 64)
    @Schema(description = "Vehicle make", example = "Honda", requiredMode = NOT_REQUIRED)
    private String make;

    @Size(max = 64)
    @Schema(description = "Vehicle model", example = "Civic", requiredMode = NOT_REQUIRED)
    private String model;

    @Size(max = 64)
    @Schema(description = "Vehicle submodel or trim", example = "EX", requiredMode = NOT_REQUIRED)
    private String submodel;

    @Size(max = 64)
    @Schema(description = "Engine code", example = "K20C2", requiredMode = NOT_REQUIRED)
    private String engineCode;

    @NotNull
    @Schema(
            description = "Labor hours, decimal hours in tenths (0.1 hr = 6 min)",
            example = "1.5",
            requiredMode = REQUIRED)
    private BigDecimal laborHours;

    @Schema(
            description = "Kind of published time; defaults to DURION_STANDARD",
            example = "DURION_STANDARD",
            allowableValues = {"RETAIL_FLAT_RATE", "OEM_WARRANTY", "MANUFACTURER_INSTALL", "DURION_STANDARD"},
            requiredMode = NOT_REQUIRED)
    private String timeType;

    @Size(max = 64)
    @Schema(
            description = "Operations sharing this group share setup time and must not be summed naively",
            example = "WHEEL-OFF",
            requiredMode = NOT_REQUIRED)
    private String overlapGroup;

    @Schema(
            description = "Operation codes whose time is already included in this one's hours",
            example = "[\"BRAKE-ROTOR-FRONT-PAIR\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> includedOpCodes;

    @Schema(
            description = "Date the time was published or decided; omitted means undated",
            example = "2026-09-01",
            requiredMode = NOT_REQUIRED)
    private LocalDate publishedAt;
}
