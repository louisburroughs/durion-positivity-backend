package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for mobile unit endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a mobile unit")
public class MobileUnitResponse {

    @Schema(
            description = "Unique identifier of the mobile unit",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Display name of the mobile unit", example = "Van 7", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Identifier of the base location the mobile unit operates from",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID baseLocationId;

    @Schema(
            description = "Operational status of the mobile unit: ACTIVE, OUT_OF_SERVICE or RETIRED"
                    + " (DECISION-LOCATION-026).",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "OUT_OF_SERVICE", "RETIRED"},
            requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Reason the unit is OUT_OF_SERVICE; null unless status is OUT_OF_SERVICE.",
            example = "EQUIPMENT_FAILURE",
            requiredMode = NOT_REQUIRED)
    private String outOfServiceReason;

    @Schema(
            description = "Free-text detail for outOfServiceReason; null unless status is OUT_OF_SERVICE.",
            example = "Awaiting hydraulic lift inspection",
            requiredMode = NOT_REQUIRED)
    private String outOfServiceNote;

    @Schema(
            description = "Advisory expected return-to-service time; not used by scheduling. Null unless status "
                    + "is OUT_OF_SERVICE and one was given.",
            example = "2026-07-01T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant expectedReturnAt;

    @Schema(
            description = "Identifier of the travel buffer policy applied to the mobile unit",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID travelBufferPolicyId;

    @Schema(
            description = "Free-text notes about the mobile unit",
            example = "Equipped with hydraulic lift",
            requiredMode = NOT_REQUIRED)
    private String notes;

    @Schema(
            description = "Catalog operation codes this unit can perform off-site (CAP-325 D14), UPPER-DASH per"
                    + " ADR-0059 §3; empty for a unit that has not declared any.",
            example = "[\"OIL-CHANGE-FULL-SYNTHETIC\", \"BATTERY-REPLACEMENT\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityCodes;

    @Schema(
            description = "Timestamp when the mobile unit was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the mobile unit was last updated (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "The unit's coverage rules ordered by ascending priority. Present only on listMobileUnits"
                    + " with include=coverageRules; absent otherwise (read them with listCoverageRules).",
            requiredMode = NOT_REQUIRED)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<CoverageRuleResponse> coverageRules;

    @Schema(
            description =
                    "Heaviest GVWR class (1-8) the unit accepts; null when unconstrained " + "(DECISION-LOCATION-029).",
            example = "3",
            requiredMode = NOT_REQUIRED)
    private Integer maxDutyClass;

    @Schema(
            description = "Fleet/unit number painted on the vehicle; display only, not used by scheduling; null"
                    + " when not set.",
            example = "Fleet-107",
            requiredMode = NOT_REQUIRED)
    private String unitNumber;

    @Schema(
            description = "17-character vehicle identification number, upper case; display only, not used by"
                    + " scheduling; null when not set.",
            example = "1HGCM82633A004352",
            requiredMode = NOT_REQUIRED)
    private String vin;

    @Schema(
            description = "License plate number; display only, not used by scheduling; null when not set.",
            example = "ABC-1234",
            requiredMode = NOT_REQUIRED)
    private String licensePlate;

    @Schema(
            description = "ISO 3166-2 region code for licensePlate, upper case; display only, not used by"
                    + " scheduling; null when not set.",
            example = "US-NC",
            requiredMode = NOT_REQUIRED)
    private String plateRegion;
}
