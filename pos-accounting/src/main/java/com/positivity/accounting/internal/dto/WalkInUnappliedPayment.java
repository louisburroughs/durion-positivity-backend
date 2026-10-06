package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A CASH walk-in payment with money left unapplied (#2508, §4.4 item 4): an excess the CASH account never
 * keeps as credit, waiting to be refunded through pos-invoice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A walk-in payment with money left unapplied, to be refunded")
public class WalkInUnappliedPayment {

    @Schema(
            description = "Number of the invoice the payment was taken against (its remittance reference); null"
                    + " when unknown",
            example = "INV-2026-01042",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String paymentReference;

    @Schema(description = "When the payment cleared", example = "2026-10-05T15:12:09Z", requiredMode = REQUIRED)
    private Instant receivedAt;

    @Schema(description = "Amount not applied to any invoice", example = "5.00", requiredMode = REQUIRED)
    private BigDecimal unappliedAmount;

    @Schema(
            description = "Receivable payment identifier, for links",
            example = "0199a000-0000-7000-8000-000000000101",
            requiredMode = REQUIRED)
    private UUID paymentId;
}
