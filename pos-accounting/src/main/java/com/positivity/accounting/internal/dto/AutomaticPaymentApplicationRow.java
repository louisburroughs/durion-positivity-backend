package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.ApplicationSource;
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
 * One payment application nobody made by hand (#2503; spec §5.3 item 4 "Matched automatically", §7.1
 * "Done automatically"). Display fields are null when accounting cannot resolve them, never an
 * identifier rendered as text (P8, ADR-0064); the ids are for commands and links.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A payment application made automatically, with what it did and whether it was undone")
public class AutomaticPaymentApplicationRow {

    /** The one action a row offers: undo through reversePaymentApplication. */
    public static final String ACTION_UNDO = "UNDO";

    @Schema(
            description = "Payment application identifier, for reversePaymentApplication",
            example = "0199a000-0000-7000-8000-000000002201",
            requiredMode = REQUIRED)
    private UUID paymentApplicationId;

    @Schema(
            description = "Receivable payment applied",
            example = "0199a000-0000-7000-8000-000000000101",
            requiredMode = REQUIRED)
    private UUID paymentId;

    @Schema(
            description = "Invoice the payment was applied to",
            example = "0199a000-0000-7000-8000-000000001702",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @Schema(
            description = "Path that made the application: PAYMENT_SETTLED (when the payment settled) or"
                    + " INVOICE_PAYMENT (the INVOICE_PAYMENT accounting event)",
            example = "PAYMENT_SETTLED",
            allowableValues = {"PAYMENT_SETTLED", "INVOICE_PAYMENT"},
            requiredMode = REQUIRED)
    private ApplicationSource source;

    @Schema(
            description = "Application date: the settlement instant for PAYMENT_SETTLED",
            example = "2026-10-05T14:31:07Z",
            requiredMode = REQUIRED)
    private Instant appliedAt;

    @Schema(description = "Amount applied to the invoice", example = "114.75", requiredMode = REQUIRED)
    private BigDecimal appliedAmount;

    @Schema(description = "Currency (ISO 4217)", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @Schema(
            description = "Invoice number from accounting's invoice replica; null when unknown",
            example = "INV-2026-01702",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String invoiceNumber;

    @Schema(
            description = "Customer name from accounting's customer replica; null when unknown",
            example = "Rivera Trucking",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String customerDisplayName;

    @Schema(
            description = "Customer number from accounting's customer replica; null when unknown",
            example = "CUST-00412",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String customerReference;

    @Schema(
            description = "Customer credit the same application created for an amount beyond the invoice's"
                    + " balance; null when none",
            example = "5.25",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private BigDecimal creditCreatedAmount;

    @Schema(
            description = "Whether the application has been undone (reversed)",
            example = "false",
            requiredMode = REQUIRED)
    private boolean reversed;

    @Schema(
            description = "When the application was undone; null when it was not",
            example = "2026-10-05T16:02:44Z",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private Instant reversedAt;

    @ArraySchema(
            arraySchema =
                    @Schema(
                            description = "Commands the caller may run on this row: UNDO when it is not reversed and"
                                    + " the caller holds accounting:payment:reverse, else empty",
                            example = "[\"UNDO\"]",
                            requiredMode = REQUIRED))
    private List<String> actions;
}
