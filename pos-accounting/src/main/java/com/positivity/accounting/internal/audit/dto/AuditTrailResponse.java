package com.positivity.accounting.internal.audit.dto;

import com.positivity.accounting.internal.audit.entity.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
    private UUID actorId;
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
    private PaymentStatus originalPaymentStatus;
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
