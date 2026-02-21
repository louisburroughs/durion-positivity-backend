package com.positivity.accounting.internal.audit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.audit.entity.PolicyValidationResult;
import com.positivity.accounting.internal.enums.AccountingIntent;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.enums.CancellationType;
import com.positivity.accounting.internal.enums.RefundMethod;
import com.positivity.accounting.internal.enums.RefundPaymentStatus;
import com.positivity.accounting.internal.enums.RefundType;

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
public class AuditTrailResponse {

    private UUID auditId;
    private ExceptionType exceptionType;
    private String actorId;
    private String actorRole;
    private Instant timestamp;
    private String reason;
    private String authorizationLevel;
    private String policyVersion;

    // Price override fields
    private UUID orderId;
    private UUID lineItemId;
    private BigDecimal originalPrice;
    private BigDecimal adjustedPrice;
    private String overrideAmountOrPercent;
    private String forbiddenCategoryCode;
    private PolicyValidationResult policyValidationResult;

    // Refund fields
    private UUID invoiceId;
    private UUID paymentId;
    private RefundType refundType;
    private BigDecimal refundAmount;
    private RefundPaymentStatus originalPaymentStatus;
    private RefundMethod refundMethod;
    private String linkedSourceIds;

    // Cancellation fields
    private CancellationType cancellationType;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String partialPaymentInfo;
    private String glReversalStatus;

    // Accounting fields
    private AccountingIntent accountingIntent;
    private AccountingStatus accountingStatus;
    private String expectedAccountingOutcome;
    private UUID sourceEventId;
    private String sourceDocumentId;
}
