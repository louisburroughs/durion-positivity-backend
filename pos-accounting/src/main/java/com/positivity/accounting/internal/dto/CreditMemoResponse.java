package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.CreditMemoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * Response containing Credit Memo details.
 *
 * <p>Carries display-ready values alongside every identifier (issue #1779): screens render
 * {@code creditMemoReference}, {@code originalInvoiceReference}, {@code customerDisplayName} and
 * {@code customerReference}, while the UUIDs stay in the contract for commands, links and audit
 * traceability. Every display value is nullable and is null when accounting cannot resolve it — a
 * UUID is never copied into a display field as fallback text.
 */
@Data
@Schema(description = "Response containing credit memo details")
public class CreditMemoResponse {

    @Schema(
            description = "Unique identifier of the credit memo",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID creditMemoId;

    @Schema(
            description = "Short human-readable reference for the credit memo, shown in place of the raw "
                    + "creditMemoId UUID (CM-{YYYYMM}-{n}). Null for memos issued before the reference was "
                    + "introduced and never backfilled; render nothing rather than falling back to the UUID.",
            example = "CM-202609-7",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String creditMemoReference;

    @Schema(
            description = "Identifier of the original invoice the credit memo references",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID originalInvoiceId;

    @Schema(
            description = "Human-readable number of the original invoice, resolved from accounting's invoice "
                    + "replica. Null when the replica holds no number for the invoice; never the invoice UUID "
                    + "as fallback text.",
            example = "INV-2026-004417",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String originalInvoiceReference;

    @Schema(
            description = "Identifier of the customer the credit memo applies to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID customerId;

    @Schema(
            description = "Customer display name resolved from accounting's customer-party replica. Null when "
                    + "the owner knows no name for the party or the replica has not seen it; never the customer "
                    + "UUID as fallback text.",
            example = "Northside Fleet Services",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String customerDisplayName;

    @Schema(
            description = "Stable human-facing customer number resolved from accounting's customer-party "
                    + "replica. Null when the owner never numbered the party or the replica has not seen it; "
                    + "never the customer UUID as fallback text.",
            example = "C-10427",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String customerReference;

    @Schema(description = "Credit amount", example = "1250.00", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal creditAmount;

    @Schema(description = "Tax amount reversed by the credit", example = "100.00", requiredMode = NOT_REQUIRED)
    private BigDecimal taxAmountReversed;

    @Schema(
            description = "Total amount of the credit memo including tax",
            example = "1350.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal totalAmount;

    @Schema(description = "Reason code for the credit memo", example = "RETURN", requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(
            description = "Justification note explaining the credit",
            example = "Customer returned defective parts",
            requiredMode = NOT_REQUIRED)
    private String justificationNote;

    @Schema(description = "Current status of the credit memo", example = "POSTED", requiredMode = REQUIRED)
    @NotNull
    private CreditMemoStatus status;

    @Schema(
            description = "Timestamp when the credit memo was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant creationTimestamp;

    @Schema(
            description = "Timestamp when the credit memo was posted (ISO 8601)",
            example = "2026-06-18T09:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant postedTimestamp;

    @Schema(
            description = "Identifier of the user who created the credit memo",
            example = "user-1042",
            requiredMode = NOT_REQUIRED)
    private String createdByUserId;

    @Schema(
            description = "Whether the credit memo is a prior-period adjustment",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean priorPeriodAdjustment;

    @Schema(
            description = "Identifier of the original accounting period",
            example = "2026-05",
            requiredMode = NOT_REQUIRED)
    private String originalPeriodId;

    @Schema(description = "ISO 4217 currency code", example = "USD", requiredMode = NOT_REQUIRED)
    private String currency;

    @Schema(description = "When the memo was voided; null unless status is VOIDED", requiredMode = NOT_REQUIRED)
    private Instant voidedTimestamp;

    @Schema(description = "User who voided the memo; null unless status is VOIDED", requiredMode = NOT_REQUIRED)
    private String voidedByUserId;

    @Schema(description = "Reason the memo was voided; null unless status is VOIDED", requiredMode = NOT_REQUIRED)
    private String voidReason;

    @Schema(
            description = "Invoice outstanding balance after the credit was applied",
            example = "0.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal invoiceBalanceAfter;
}
