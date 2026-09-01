package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "A labor standard row with its provenance. supersededAt null means the row is active.")
public class ServiceLaborStandardResponseDto {

    @Schema(description = "Labor standard identifier", requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Owning service identifier", requiredMode = REQUIRED)
    private UUID serviceId;

    @Schema(description = "Model year or year range; null = any", requiredMode = NOT_REQUIRED)
    private String vehicleYear;

    @Schema(description = "Vehicle make; null = any", requiredMode = NOT_REQUIRED)
    private String make;

    @Schema(description = "Vehicle model; null = any", requiredMode = NOT_REQUIRED)
    private String model;

    @Schema(description = "Vehicle submodel or trim; null = any", requiredMode = NOT_REQUIRED)
    private String submodel;

    @Schema(description = "Engine code; null = any", requiredMode = NOT_REQUIRED)
    private String engineCode;

    @Schema(description = "Labor hours, decimal hours in tenths", example = "1.5", requiredMode = REQUIRED)
    private BigDecimal laborHours;

    @Schema(description = "Kind of published time", example = "DURION_STANDARD", requiredMode = REQUIRED)
    private String timeType;

    @Schema(description = "Overlap group shared with other operations", requiredMode = NOT_REQUIRED)
    private String overlapGroup;

    @Schema(description = "Operation codes whose time this one already includes", requiredMode = NOT_REQUIRED)
    private List<String> includedOpCodes;

    @Schema(description = "Source that published the time; DURION for hand-authored rows", requiredMode = REQUIRED)
    private String sourceCode;

    @Schema(description = "Source revision the time came from", requiredMode = REQUIRED)
    private String sourceRevision;

    @Schema(description = "Date the source published the time", requiredMode = NOT_REQUIRED)
    private LocalDate publishedAt;

    @Schema(description = "When a newer row replaced this one; null while active", requiredMode = NOT_REQUIRED)
    private Instant supersededAt;

    @Schema(description = "Row creation instant", requiredMode = REQUIRED)
    private Instant createdAt;
}
