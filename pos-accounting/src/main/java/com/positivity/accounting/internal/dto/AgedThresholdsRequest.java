package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to replace the three aged-item thresholds (CAP:550 S19, #2515). The service validates every field, so each
 * refusal is the platform's 400 {@code VALIDATION_ERROR} naming the field.
 */
@Data
@NoArgsConstructor
@Schema(description = "Replaces the tenant's three aged-item thresholds; all fields are required")
public class AgedThresholdsRequest {

    @Schema(
            description = "Calendar days an undeposited register session waits before it is aged, from its business"
                    + " date; 1 to 30, default 3",
            example = "3",
            requiredMode = REQUIRED)
    private Integer undepositedCashAgedDays;

    @Schema(
            description = "Calendar days a vendor cash-on-delivery payment sits unapplied before it is aged, from"
                    + " its payment date; 1 to 90, default 14",
            example = "14",
            requiredMode = REQUIRED)
    private Integer vendorCodUnappliedAgedDays;

    @Schema(
            description = "Calendar days a goods receipt waits for its approved bill before it is listed; 7 to 120,"
                    + " default 30",
            example = "30",
            requiredMode = REQUIRED)
    private Integer deliveriesNotBilledDays;

    @Schema(
            description = "Why the thresholds change; 10 to 1000 characters; recorded on each audit row",
            example = "Finance tightens deposit ageing after the Q3 review",
            requiredMode = REQUIRED)
    private String justification;

    @Schema(
            description = "Client-generated UUID that makes the PUT idempotent: a replay with the same body writes"
                    + " nothing, a reuse with another body is refused 409",
            example = "019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6e",
            requiredMode = REQUIRED)
    private UUID requestId;
}
