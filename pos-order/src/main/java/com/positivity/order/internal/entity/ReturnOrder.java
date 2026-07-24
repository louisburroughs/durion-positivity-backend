package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A return against a completed sales order — the Odoo {@code refund()} negative-quantity copy
 * (parity stories F1/F2, issues #1086/#1087, spec R5.1–R5.5). Exactly one {@code originalOrderId}
 * (a COMPLETED order); lines reference the sold lines and are capped per original line at the
 * un-refunded remainder. The saga status ({@link ReturnOrderStatus}) drives the F2 orchestration.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "return_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrder {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "return_order_id", columnDefinition = "UUID")
    private UUID returnOrderId;

    @Version
    @Column(nullable = false)
    private Long version;

    /** The single COMPLETED order this return is taken against (Odoo one-source-order rule). */
    @Column(name = "original_order_id", nullable = false, columnDefinition = "UUID")
    private UUID originalOrderId;

    @Column(name = "original_order_number")
    private String originalOrderNumber;

    @Column(name = "location_id", columnDefinition = "UUID")
    private UUID locationId;

    @Column(name = "customer_id", columnDefinition = "UUID")
    private UUID customerId;

    /** Invoice fronting the original order; the original-tender refund reverses against it. */
    @Column(name = "original_invoice_id", columnDefinition = "UUID")
    private UUID originalInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, length = 24)
    private RefundMethod refundMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReturnOrderStatus status;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "total_refund", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRefund;

    /** Client idempotency key from creation; unique, replays return the original return order. */
    @Column(name = "creation_idempotency_key", updatable = false, length = 128, unique = true)
    private String creationIdempotencyKey;

    @Column(name = "approved_by_user_id", columnDefinition = "UUID")
    private UUID approvedByUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    /** Saga failure detail parked on REFUND_FAILED (story F2). */
    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @OneToMany(mappedBy = "returnOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ReturnOrderLine> lines = new ArrayList<>();

    public void addLine(@NonNull ReturnOrderLine line) {
        lines.add(line);
        line.setReturnOrder(this);
    }
}
