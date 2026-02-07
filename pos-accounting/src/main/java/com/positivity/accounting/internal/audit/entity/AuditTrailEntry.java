package com.positivity.accounting.internal.audit.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail entry for financial exceptions.
 * 
 * <p>
 * Records price overrides, refunds, and cancellations with full traceability
 * including actor, authorization level, policy version, and accounting intent.
 * 
 * <p>
 * Entries are never updated or deleted - only appended with corrections.
 */
@Entity
@Table(name = "audit_trail_entry", indexes = {
        @Index(name = "idx_exception_type", columnList = "exception_type"),
        @Index(name = "idx_order_id", columnList = "order_id"),
        @Index(name = "idx_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_actor_id", columnList = "actor_id"),
        @Index(name = "idx_timestamp", columnList = "timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditTrailEntry {

    @Id
    @Column(name = "audit_id", updatable = false, nullable = false, columnDefinition = "UUID")
    private UUID auditId;

    @PrePersist
    public void generateId() {
        if (auditId == null) {
            auditId = UUIDv7Generator.generate();
        }
    }

    /**
     * Type of financial exception.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 50)
    private ExceptionType exceptionType;

    /**
     * User who performed the action.
     */
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    /**
     * Role of the actor.
     */
    @Column(name = "actor_role", nullable = false, length = 100)
    private String actorRole;

    /**
     * Server-generated, immutable timestamp.
     */
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    /**
     * Reason for the exception (free text or structured code).
     */
    @Column(name = "reason", length = 2000)
    private String reason;

    /**
     * Authorization level required/granted.
     */
    @Column(name = "authorization_level", nullable = false, length = 100)
    private String authorizationLevel;

    /**
     * Policy version enforced at creation time.
     */
    @Column(name = "policy_version", nullable = false, length = 50)
    private String policyVersion;

    // ========== PRICE_OVERRIDE fields ==========

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "line_item_id")
    private UUID lineItemId;

    @Column(name = "original_price", precision = 19, scale = 4)
    private BigDecimal originalPrice;

    @Column(name = "adjusted_price", precision = 19, scale = 4)
    private BigDecimal adjustedPrice;

    @Column(name = "override_amount_or_percent", length = 50)
    private String overrideAmountOrPercent;

    @Column(name = "forbidden_category_code", length = 100)
    private String forbiddenCategoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_validation_result", length = 50)
    private PolicyValidationResult policyValidationResult;

    // ========== REFUND fields ==========

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_type", length = 50)
    private RefundType refundType;

    @Column(name = "refund_amount", precision = 19, scale = 4)
    private BigDecimal refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_payment_status", length = 50)
    private PaymentStatus originalPaymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", length = 50)
    private RefundMethod refundMethod;

    @Column(name = "linked_source_ids", columnDefinition = "TEXT")
    private String linkedSourceIds; // JSON string

    // ========== CANCELLATION fields ==========

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_type", length = 50)
    private CancellationType cancellationType;

    @Column(name = "before_snapshot", columnDefinition = "TEXT")
    private String beforeSnapshot; // JSON string

    @Column(name = "after_snapshot", columnDefinition = "TEXT")
    private String afterSnapshot; // JSON string

    @Column(name = "partial_payment_info", columnDefinition = "TEXT")
    private String partialPaymentInfo; // JSON string

    @Column(name = "gl_reversal_status", length = 50)
    private String glReversalStatus;

    // ========== Accounting integration fields ==========

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_intent", length = 50)
    private AccountingIntent accountingIntent;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_status", nullable = false, length = 50)
    @Builder.Default
    private AccountingStatus accountingStatus = AccountingStatus.PENDING_POSTING;

    @Column(name = "expected_accounting_outcome", length = 1000)
    private String expectedAccountingOutcome;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "source_document_id", length = 255)
    private String sourceDocumentId;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (accountingStatus == null) {
            accountingStatus = AccountingStatus.PENDING_POSTING;
        }
    }
}
