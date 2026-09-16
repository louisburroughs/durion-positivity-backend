package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
 * Response payload for bay endpoints.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a service bay")
public class BayResponse {

    @Schema(
            description = "Unique identifier of the bay",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Identifier of the location that owns the bay",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Display name of the bay", example = "Bay A1", requiredMode = REQUIRED)
    @NotNull
    private String name;

    @Schema(description = "Type classification of the bay", example = "LIFT", requiredMode = NOT_REQUIRED)
    private String bayType;

    @Schema(description = "Operational status of the bay", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Number of vehicles the bay physically accommodates at once. A bay is a single "
                    + "bookable resource regardless of this value; register separate bays for independently "
                    + "bookable stalls.",
            example = "1",
            requiredMode = NOT_REQUIRED)
    private Integer maxConcurrentVehicles;

    @Schema(
            description = "Catalog operation codes this bay type is the only one able to perform "
                    + "(CAP-325 D14). Empty for a general bay, which is eligible for every operation no "
                    + "specialty bay claims. Values are catalog operationCodes, UPPER-DASH per ADR-0059 §3.",
            example = "[\"WHEEL-ALIGNMENT-4-WHEEL\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityCodes;

    @Schema(
            description = "Heaviest GVWR class (1–8) the bay accepts; null when unconstrained (CAP-325 D13). "
                    + "Light = 1–3, Medium = 4–6, Heavy = 7–8.",
            example = "3",
            minimum = "1",
            maximum = "8",
            requiredMode = NOT_REQUIRED)
    private Integer maxDutyClass;

    @Schema(
            description = "Timestamp when the bay was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the bay was last modified (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant lastModifiedAt;
}
