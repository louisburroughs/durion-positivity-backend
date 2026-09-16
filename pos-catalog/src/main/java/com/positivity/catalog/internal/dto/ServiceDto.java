package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog service item")
public class ServiceDto {

    @Schema(description = "Service identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535")
    private UUID id;

    @Schema(description = "Service name", example = "Standard installation")
    private String name;

    @Schema(description = "Long service description")
    private String longDescription;

    @Schema(description = "Short service description")
    private String shortDescription;

    @Schema(description = "Durion operation code", example = "BRAKE-PAD-FRONT")
    private String operationCode;

    @Schema(
            description = "Operation category",
            example = "REPAIR",
            allowableValues = {"REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE"})
    private String operationCategory;

    @Schema(description = "Vehicle-agnostic fallback labor hours in tenths", example = "1.5")
    private BigDecimal defaultLaborHours;

    @Schema(
            description = "When the skill requirements were last declared (CAP-329); null means never configured,"
                    + " which consumers warn about rather than deny on",
            example = "2026-09-16T12:00:00Z")
    private Instant requirementsConfiguredAt;

    @Schema(
            description = "Skills the service requires per GVWR class range; empty with a non-null"
                    + " requirementsConfiguredAt declares the service unconstrained, null means not configured")
    private List<RequiredSkillDto> requiredSkills;
}
