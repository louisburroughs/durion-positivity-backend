package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating bays.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for creating a service bay")
public class BayRequest {

    @Schema(description = "Display name of the bay", example = "Bay A1", requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(
            description = "Type classification of the bay; must be a BayType value",
            example = "GENERAL_SERVICE",
            requiredMode = REQUIRED)
    @NotBlank
    private String bayType;

    @Schema(description = "Capacity configuration for the bay", requiredMode = REQUIRED)
    @NotNull
    @Valid
    private BayCapacityRequest capacity;

    @Schema(
            description = "Number of vehicles the bay physically accommodates at once. A bay is a single "
                    + "bookable resource regardless of this value; register separate bays for independently "
                    + "bookable stalls.",
            example = "1",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer maxConcurrentVehicles;

    @Schema(
            description = "Catalog operation codes this bay type is the only one able to perform "
                    + "(CAP-325 D14). Omit or send null to apply the bay type's default specialty codes; send "
                    + "an empty list for a general bay with no specialty claim. Each value must be an active "
                    + "catalog operationCode (UPPER-DASH, ADR-0059 §3); unknown or retired codes are rejected 422.",
            example = "[\"WHEEL-ALIGNMENT-4-WHEEL\"]",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private List<String> serviceCapabilityCodes;

    @Schema(
            description = "Heaviest GVWR class (1–8) the bay accepts; omit for unconstrained (CAP-325 D13).",
            example = "3",
            minimum = "1",
            maximum = "8",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(8)
    private Integer maxDutyClass;

    @Schema(
            description = "Operational status of the bay: ACTIVE, OUT_OF_SERVICE or RETIRED; defaults to ACTIVE.",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "OUT_OF_SERVICE", "RETIRED"},
            requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Reason the bay is OUT_OF_SERVICE (DECISION-LOCATION-026); required when status is "
                    + "OUT_OF_SERVICE, refused with 422 OUT_OF_SERVICE_REASON_REQUIRED otherwise.",
            example = "EQUIPMENT_FAILURE",
            allowableValues = {
                "EQUIPMENT_FAILURE",
                "SCHEDULED_MAINTENANCE",
                "INSPECTION",
                "SAFETY_HOLD",
                "FACILITY_ISSUE",
                "OTHER"
            },
            requiredMode = NOT_REQUIRED)
    private String outOfServiceReason;

    @Schema(
            description = "Free-text detail for outOfServiceReason (max 255 characters); required when "
                    + "outOfServiceReason is OTHER, optional otherwise.",
            example = "Lift arm replaced under warranty",
            maxLength = 255,
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String outOfServiceNote;

    @Schema(
            description = "Advisory expected return-to-service time; not used by scheduling.",
            example = "2026-07-01T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant expectedReturnAt;

    @Schema(
            description = "Sort key for bay lists and the dispatch board; bays without a value sort last, ties "
                    + "broken by name.",
            example = "10",
            requiredMode = NOT_REQUIRED)
    private Integer displayOrder;
}
