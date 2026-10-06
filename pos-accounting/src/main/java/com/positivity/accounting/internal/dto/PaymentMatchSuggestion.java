package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The invoices an unapplied payment most likely pays and why (#2502, BR-4; spec P3). Computed over
 * the payment customer's open invoices only; advisory — the apply command validates again.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The invoices a payment most likely pays, the reasons, and what would be left over")
public class PaymentMatchSuggestion {

    @ArraySchema(
            arraySchema =
                    @Schema(
                            description = "Why these invoices are suggested. Served values: REMITTANCE_REFERENCE (the"
                                    + " payment was taken against this invoice), SAME_CUSTOMER (the invoices belong to"
                                    + " the payment's customer), EXACT_TOTAL (the suggested balances add up exactly to"
                                    + " the unapplied amount). A client renders an unknown value as Unknown. Empty when"
                                    + " the customer has no open invoice.",
                            example = "[\"REMITTANCE_REFERENCE\",\"SAME_CUSTOMER\",\"EXACT_TOTAL\"]",
                            requiredMode = REQUIRED),
            schema = @Schema(example = "SAME_CUSTOMER"))
    private List<String> reasons;

    @ArraySchema(
            arraySchema =
                    @Schema(
                            description = "Suggested invoices in allocation order (oldest first); empty when none is"
                                    + " suggested",
                            requiredMode = REQUIRED))
    private List<SuggestedInvoice> invoices;

    @Schema(
            description = "Sum of the suggested amounts; never more than the unapplied amount",
            example = "4615.00",
            requiredMode = REQUIRED)
    private BigDecimal suggestedTotal;

    @Schema(
            description = "Unapplied amount minus the suggested total: what would become a customer credit (AD-003);"
                    + " null for a payment of the CASH walk-in account, which never keeps a credit: its excess is"
                    + " refunded (#2508)",
            example = "0.00",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private BigDecimal leftOver;
}
