package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.SourceInvoiceState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One customer payment still waiting to be matched (#2502). Display fields are null when accounting
 * cannot resolve them, never an identifier rendered as text (P8, ADR-0064). The source invoice's state,
 * the possible-duplicate flag and the automatic-application reason are served facts (#2563), so a
 * client renders them instead of inferring them from the suggestion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A customer payment with an unapplied amount, and the invoices it most likely pays")
public class UnappliedPaymentRow {

    @Schema(
            description = "Receivable payment identifier",
            example = "0199a000-0000-7000-8000-000000000101",
            requiredMode = REQUIRED)
    private UUID paymentId;

    @Schema(
            description = "Customer the payment belongs to",
            example = "0198a000-0000-7000-8000-000000000412",
            requiredMode = REQUIRED)
    private UUID customerId;

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
            description = "Settlement method as the payment fact sent it (CASH, CARD, ON_ACCOUNT, OTHER); null when"
                    + " the recording path carried none",
            example = "CARD",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String paymentMethod;

    @Schema(description = "When the payment cleared", example = "2026-10-04T15:12:09Z", requiredMode = REQUIRED)
    private Instant receivedAt;

    @Schema(description = "Payment currency (ISO 4217)", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @Schema(description = "Amount the payment cleared for", example = "4615.00", requiredMode = REQUIRED)
    private BigDecimal totalAmount;

    @Schema(description = "Amount not yet applied or credited", example = "4615.00", requiredMode = REQUIRED)
    private BigDecimal unappliedAmount;

    @Schema(
            description = "Invoice the payment was taken against (its remittance reference); null when unknown",
            example = "0199a000-0000-7000-8000-000000001702",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private UUID sourceInvoiceId;

    @Schema(
            description = "Number of the invoice the payment was taken against; null when unknown",
            example = "INV-2026-01702",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String sourceInvoiceNumber;

    @Schema(
            description = "State of the invoice the payment was taken against, by the rule automatic application"
                    + " decides with: OPEN (it has an open balance this payment can pay), PAID (no open balance is"
                    + " left; it may have been paid by this payment, see possibleDuplicate), NOT_FOUND (not in"
                    + " accounting's invoice replica yet), NOT_ELIGIBLE (not FINALIZED or POSTED, so it cannot take a"
                    + " payment), OTHER_CUSTOMER (its customer is missing or is not the payment's); null when the"
                    + " payment names no invoice",
            example = "PAID",
            allowableValues = {"OPEN", "PAID", "NOT_FOUND", "NOT_ELIGIBLE", "OTHER_CUSTOMER"},
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private SourceInvoiceState sourceInvoiceState;

    @Schema(
            description = "True only when the invoice the payment was taken against is already paid (sourceInvoiceState"
                    + " PAID) and this payment applied nothing to it that is still in force: the payment is"
                    + " possibly a duplicate. False for every other state, and for a payment that itself paid the"
                    + " invoice, in full or in part, and has a remainder waiting",
            example = "true",
            requiredMode = REQUIRED)
    private boolean possibleDuplicate;

    @Schema(
            description = "Why automatic application did not apply the payment when it settled: the reason on its"
                    + " newest automatic-application record. NOT_POSTABLE is a skip (a method other than CASH or"
                    + " CARD, another customer's invoice, or an invoice already paid); INVOICE_NOT_FOUND,"
                    + " INVOICE_NOT_ELIGIBLE, PERIOD_CLOSED and ACCOUNTING_TIME_ZONE_UNSET are holds that are"
                    + " retried or reprocessed. Null when the payment has no such record or the record was later"
                    + " processed",
            example = "NOT_POSTABLE",
            allowableValues = {
                "NOT_POSTABLE",
                "INVOICE_NOT_FOUND",
                "INVOICE_NOT_ELIGIBLE",
                "PERIOD_CLOSED",
                "ACCOUNTING_TIME_ZONE_UNSET"
            },
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String autoApplicationReason;

    @Schema(description = "The invoices the payment most likely pays and why", requiredMode = REQUIRED)
    private PaymentMatchSuggestion suggestion;
}
