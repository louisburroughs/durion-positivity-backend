package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for manually matching an UNMATCHED settlement line to an AR
 * receivable payment (Story F1c, issue #963, decision D-14). AP matching is not
 * supported in v1.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story F1c</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for manually matching a settlement line to a receivable payment")
public class SettlementManualMatchRequest {

    @NotNull(message = "receivablePaymentId is required")
    @Schema(
            description = "The receivable payment to match the settlement line to",
            example = "01936e5e-7890-7a3d-8b6e-4d5678901234",
            requiredMode = REQUIRED)
    private UUID receivablePaymentId;
}
