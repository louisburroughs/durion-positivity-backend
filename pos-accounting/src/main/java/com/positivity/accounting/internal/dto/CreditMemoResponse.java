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
            description = "Identifier of the original invoice the credit memo references",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID originalInvoiceId;

    @Schema(
            description = "Identifier of the customer the credit memo applies to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID customerId;

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

    @Schema(
            description = "Invoice outstanding balance after the credit was applied",
            example = "0.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal invoiceBalanceAfter;
}
