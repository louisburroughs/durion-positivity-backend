package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Patch payload for bays.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Partial update payload for a service bay; null fields are left unchanged")
public class BayPatchRequest {

    @Schema(description = "Display name of the bay", example = "Bay A1", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Type classification of the bay; must be a BayType value. Changing bayType without "
                    + "also sending serviceCapabilityCodes resets the bay's codes to the new type's specialty-map "
                    + "defaults (CAP-325 D14 rule 3) — for example, retyping to GENERAL_SERVICE clears any "
                    + "alignment/tire/inspection claim rather than keeping it. Send serviceCapabilityCodes in the "
                    + "same request to state the bay's codes explicitly instead.",
            example = "GENERAL_SERVICE",
            requiredMode = NOT_REQUIRED)
    private String bayType;

    @Schema(
            description = "Operational status of the bay: ACTIVE, OUT_OF_SERVICE or RETIRED. Going OUT_OF_SERVICE "
                    + "requires outOfServiceReason in this same request or already on the bay; returning to ACTIVE "
                    + "clears outOfServiceReason, outOfServiceNote and expectedReturnAt. DELETE is the usual way to "
                    + "RETIRE a bay; RETIRED here is reversible the same as OUT_OF_SERVICE.",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "OUT_OF_SERVICE", "RETIRED"},
            requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Reason the bay is OUT_OF_SERVICE (DECISION-LOCATION-026); required when the resulting "
                    + "status is OUT_OF_SERVICE, refused with 422 OUT_OF_SERVICE_REASON_REQUIRED otherwise. Null "
                    + "leaves the current reason unchanged unless status is patched to ACTIVE, which always clears it.",
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
            description = "Free-text detail for outOfServiceReason (max 255 characters); required when the "
                    + "resulting outOfServiceReason is OTHER. Null leaves the current note unchanged unless status "
                    + "is patched to ACTIVE, which always clears it.",
            example = "Lift arm replaced under warranty",
            maxLength = 255,
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String outOfServiceNote;

    @Schema(
            description = "Advisory expected return-to-service time; not used by scheduling. Null leaves the "
                    + "current value unchanged unless status is patched to ACTIVE, which always clears it.",
            example = "2026-07-01T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant expectedReturnAt;

    @Schema(
            description = "Number of vehicles the bay physically accommodates at once. A bay is a single "
                    + "bookable resource regardless of this value; register separate bays for independently "
                    + "bookable stalls.",
            example = "1",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer maxConcurrentVehicles;

    @Schema(description = "Capacity configuration for the bay", requiredMode = NOT_REQUIRED)
    @Valid
    private BayCapacityRequest capacity;

    @Schema(
            description = "Catalog operation codes this bay type is the only one able to perform "
                    + "(CAP-325 D14). Null leaves unchanged; an empty list clears to general. Each value must "
                    + "be an active catalog operationCode; unknown codes are rejected 422.",
            example = "[\"WHEEL-ALIGNMENT-4-WHEEL\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityCodes;

    @Schema(
            description = "Heaviest GVWR class (1–8) the bay accepts (CAP-325 D13). Null leaves unchanged.",
            example = "3",
            minimum = "1",
            maximum = "8",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(8)
    private Integer maxDutyClass;

    @Schema(
            description = "Sort key for bay lists and the dispatch board; null leaves the current value unchanged, "
                    + "the same as every other nullable field on this patch.",
            example = "10",
            requiredMode = NOT_REQUIRED)
    private Integer displayOrder;
}
