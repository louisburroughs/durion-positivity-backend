package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One invoice a payment most likely pays, with the amount to apply to it (#2502). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An open invoice suggested for a payment, with the amount the suggestion applies to it")
public class SuggestedInvoice {

    @Schema(
            description = "Invoice identifier",
            example = "0199a000-0000-7000-8000-000000001702",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @Schema(
            description = "Invoice number; null when the replica holds none",
            example = "INV-2026-01702",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String invoiceNumber;

    @Schema(
            description = "What is still owed on the invoice after applications, credits, credit memos and deposits",
            example = "4615.00",
            requiredMode = REQUIRED)
    private BigDecimal balanceDue;

    @Schema(
            description = "Amount the suggestion applies to this invoice; never more than its balance due",
            example = "4615.00",
            requiredMode = REQUIRED)
    private BigDecimal suggestedAmount;
}
