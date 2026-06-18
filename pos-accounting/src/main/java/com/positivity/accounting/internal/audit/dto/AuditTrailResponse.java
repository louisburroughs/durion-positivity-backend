package com.positivity.accounting.internal.audit.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.audit.entity.PolicyValidationResult;
import com.positivity.accounting.internal.enums.AccountingIntent;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.enums.CancellationType;
import com.positivity.accounting.internal.enums.RefundMethod;
import com.positivity.accounting.internal.enums.RefundPaymentStatus;
import com.positivity.accounting.internal.enums.RefundType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response containing audit trail entry details.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Audit trail entry describing a recorded accounting exception event")
public class AuditTrailResponse {

    @Schema(
            description = "Unique identifier of the audit trail entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID auditId;

    @Schema(description = "Category of exception recorded", example = "PRICE_OVERRIDE", requiredMode = REQUIRED)
    @NotNull
    private ExceptionType exceptionType;

    @Schema(
            description = "Identifier of the actor who performed the action",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private String actorId;

    @Schema(description = "Role of the actor who performed the action", example = "STORE_MANAGER", requiredMode = NOT_REQUIRED)
    private String actorRole;

    @Schema(
            description = "Timestamp when the audit event occurred (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant timestamp;

    @Schema(description = "Reason captured for the action", example = "Price matched competitor quote", requiredMode = NOT_REQUIRED)
    private String reason;

    @Schema(description = "Authorization level applied to the action", example = "MANAGER", requiredMode = NOT_REQUIRED)
    private String authorizationLevel;

    @Schema(description = "Version of the policy evaluated for the action", example = "v3", requiredMode = NOT_REQUIRED)
    private String policyVersion;

    // Price override fields
    @Schema(
            description = "Identifier of the order associated with the event",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID orderId;

    @Schema(
            description = "Identifier of the line item associated with the event",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID lineItemId;

    @Schema(description = "Original price before override", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal originalPrice;

    @Schema(description = "Adjusted price after override", example = "1100.00", requiredMode = NOT_REQUIRED)
    private BigDecimal adjustedPrice;

    @Schema(description = "Override expressed as an amount or percentage", example = "-12%", requiredMode = NOT_REQUIRED)
    private String overrideAmountOrPercent;

    @Schema(description = "Forbidden category code that was triggered, if any", example = "BELOW_COST", requiredMode = NOT_REQUIRED)
    private String forbiddenCategoryCode;

    @Schema(description = "Result of policy validation for the event", requiredMode = NOT_REQUIRED)
    private PolicyValidationResult policyValidationResult;

    // Refund fields
    @Schema(
            description = "Identifier of the invoice associated with the event",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    private UUID invoiceId;

    @Schema(
            description = "Identifier of the payment associated with the event",
            example = "01960003-0000-7000-8000-000000000006",
            requiredMode = NOT_REQUIRED)
    private UUID paymentId;

    @Schema(description = "Type of refund recorded", example = "REVERSAL", requiredMode = NOT_REQUIRED)
    private RefundType refundType;

    @Schema(description = "Refund amount", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal refundAmount;

    @Schema(description = "Status of the original payment being refunded", example = "SETTLED", requiredMode = NOT_REQUIRED)
    private RefundPaymentStatus originalPaymentStatus;

    @Schema(description = "Method used to issue the refund", example = "CASH_REFUND", requiredMode = NOT_REQUIRED)
    private RefundMethod refundMethod;

    @Schema(description = "Comma-separated linked source identifiers", example = "01960003-0000-7000-8000-000000000007", requiredMode = NOT_REQUIRED)
    private String linkedSourceIds;

    // Cancellation fields
    @Schema(description = "Type of cancellation recorded", example = "ORDER_CANCELLED", requiredMode = NOT_REQUIRED)
    private CancellationType cancellationType;

    @Schema(description = "Snapshot of state before the cancellation (JSON)", requiredMode = NOT_REQUIRED)
    private String beforeSnapshot;

    @Schema(description = "Snapshot of state after the cancellation (JSON)", requiredMode = NOT_REQUIRED)
    private String afterSnapshot;

    @Schema(description = "Partial payment details captured at cancellation (JSON)", requiredMode = NOT_REQUIRED)
    private String partialPaymentInfo;

    @Schema(description = "Status of the GL reversal triggered by the cancellation", example = "COMPLETED", requiredMode = NOT_REQUIRED)
    private String glReversalStatus;

    // Accounting fields
    @Schema(description = "Accounting intent of the event", example = "REVENUE_ADJUSTMENT", requiredMode = NOT_REQUIRED)
    private AccountingIntent accountingIntent;

    @Schema(description = "Accounting status of the event", example = "POSTED", requiredMode = NOT_REQUIRED)
    private AccountingStatus accountingStatus;

    @Schema(description = "Expected accounting outcome description", example = "Net revenue decreased by 150.00", requiredMode = NOT_REQUIRED)
    private String expectedAccountingOutcome;

    @Schema(
            description = "Identifier of the source accounting event",
            example = "01960003-0000-7000-8000-000000000008",
            requiredMode = NOT_REQUIRED)
    private UUID sourceEventId;

    @Schema(description = "Identifier of the source business document", example = "INV-2026-000123", requiredMode = NOT_REQUIRED)
    private String sourceDocumentId;
}
