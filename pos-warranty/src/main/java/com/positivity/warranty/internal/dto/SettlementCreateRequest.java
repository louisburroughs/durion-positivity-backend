package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.SettlementType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Executes one settlement on an approved claim (PRD §3.6, §7 step 6). Which reference ids are
 * required depends on {@code settlementType}:
 *
 * <ul>
 *   <li>{@code REPLACEMENT_WORKORDER} — {@code replacementWorkorderId} (link only, validated
 *       against pos-workorder)
 *   <li>{@code INVOICE_CREDIT} / {@code PRORATED_CREDIT} / {@code GOODWILL} — {@code invoiceId}
 *       (warranty creates an {@code InvoiceAdjustment} via pos-invoice)
 *   <li>{@code REFUND} — {@code invoiceId} + {@code paymentId} (warranty initiates a refund via
 *       pos-invoice)
 *   <li>{@code NO_ACTION} — record only
 * </ul>
 */
@Schema(description = "Execute a settlement on an approved warranty claim")
public record SettlementCreateRequest(
        @Schema(description = "How the customer is made whole") @NotNull
        SettlementType settlementType,

        @Schema(description = "Amount coverage pays") @NotNull @PositiveOrZero
        BigDecimal coveredAmount,

        @Schema(description = "Amount the customer still owes (proration remainder, upcharge, fees)")
        @NotNull
        @PositiveOrZero
        BigDecimal customerAmount,

        @Schema(description = "Replacement workorder link (REPLACEMENT_WORKORDER only)")
        UUID replacementWorkorderId,

        @Schema(description = "Invoice to credit/refund against (credit and refund types)")
        UUID invoiceId,

        @Schema(description = "Captured payment to refund (REFUND only)")
        UUID paymentId,

        @Schema(description = "Staff notes recorded on the claim") @Size(max = 10_000)
        String notes) {}
