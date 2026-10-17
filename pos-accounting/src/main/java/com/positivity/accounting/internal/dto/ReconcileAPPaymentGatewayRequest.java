package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reconcile a {@code GATEWAY_PENDING} AP payment with what the gateway did (#2729, Accounting ruling of 2026-10-17): an
 * orphan left by a crash or timeout between the gateway call and its answer. The operator attests the outcome until
 * #2642 gives the gateway port a lookup by payment reference.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reconcile a GATEWAY_PENDING AP payment with the gateway's outcome")
public class ReconcileAPPaymentGatewayRequest {

    /** What the gateway did with the instruction. */
    public enum GatewayOutcome {
        /** No money moved: the payment is voided; nothing to post or reopen. */
        NOT_CHARGED,
        /** Money moved: the payment posts (Dr 2000 / Cr bank) as an unapplied payment, allocated later. */
        CHARGED
    }

    @NotNull(message = "gatewayOutcome is required")
    @Schema(
            description = "NOT_CHARGED voids the payment (nothing moved); CHARGED posts it as an unapplied payment,"
                    + " the vendor's bills allocated later",
            example = "NOT_CHARGED",
            requiredMode = REQUIRED)
    private GatewayOutcome gatewayOutcome;

    @Nullable
    @Size(max = 200)
    @Schema(
            description = "The gateway's transaction id; required with CHARGED",
            example = "ch_3Q2abcDEF",
            requiredMode = NOT_REQUIRED)
    private String gatewayTransactionId;

    @NotBlank(message = "justification is required")
    @Size(min = 10, max = 1000, message = "justification must be 10-1000 characters")
    @Schema(
            description = "Why this outcome is asserted (what was checked at the gateway); audited under the caller",
            example =
                    "Gateway console shows no charge for PAY-412 on 2026-10-16; the request timed out before an answer",
            requiredMode = REQUIRED)
    private String justification;
}
