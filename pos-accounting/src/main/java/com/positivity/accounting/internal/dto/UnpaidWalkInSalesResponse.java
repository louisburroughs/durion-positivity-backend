package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What is still owed on the CASH walk-in house account (CAP:550 S11, #2508; SPEC-accounting-workspace
 * §4.1, §4.4 item 2, §9.5a; AW12): the balance, the open walk-in invoices, the day-end needs-attention
 * item and walk-in payments with money left unapplied. Computed on read; nothing is stored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Unpaid walk-in sales on the CASH house account, with the day-end needs-attention item")
public class UnpaidWalkInSalesResponse {

    @Schema(description = "When the read was computed", example = "2026-10-06T15:00:00Z", requiredMode = REQUIRED)
    private Instant asOf;

    @Schema(
            description = "False when accounting's customer replica holds no CASH house account yet: the read then"
                    + " answers zero but cannot vouch for it",
            example = "true",
            requiredMode = REQUIRED)
    private boolean houseAccountKnown;

    @Schema(
            description = "Customer number of the CASH account; null when unknown",
            example = "CASH",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String customerNumber;

    @Schema(description = "Functional (ledger) currency, ISO 4217", example = "USD", requiredMode = REQUIRED)
    private String currencyCode;

    @Schema(
            description = "Sum of the balances due on the CASH account's open invoices",
            example = "52.50",
            requiredMode = REQUIRED)
    private BigDecimal balance;

    @ArraySchema(
            arraySchema = @Schema(description = "Open walk-in invoices, oldest sale first"),
            schema = @Schema(implementation = WalkInOpenInvoice.class))
    private List<WalkInOpenInvoice> openInvoices;

    @Schema(description = "Open walk-in invoices whose business day has ended", requiredMode = REQUIRED)
    private WalkInNeedsAttention needsAttention;

    @ArraySchema(
            arraySchema = @Schema(description = "CASH payments with money left unapplied, oldest first"),
            schema = @Schema(implementation = WalkInUnappliedPayment.class))
    private List<WalkInUnappliedPayment> unappliedPayments;

    @Schema(
            description = "Party identifier of the CASH account, for links; null when unknown",
            example = "0198a000-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private UUID customerId;
}
