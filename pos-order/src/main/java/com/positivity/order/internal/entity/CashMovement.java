package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A non-sale drawer cash movement against an open register session (parity story G1, spec R6.6;
 * Odoo {@code try_cash_in_out} analog). {@code amount} is always positive; the direction is carried
 * by {@link CashMovementType} and signed into the session's theoretical cash at close.
 *
 * <p>From CAP:550 S16 (#2512, AW15) every movement carries one of the fixed {@link
 * CashMovementReason}s with that reason's detail, the register's {@code requestId} (idempotency key),
 * the cashier from the security context and, when a manager approved it, the approver and the
 * approval it used. Rows recorded before then keep their free text as {@code note} and have no reason.
 * A movement is never changed after it is recorded.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "cash_movement")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashMovement extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "movement_id", columnDefinition = "UUID")
    private UUID movementId;

    @Column(name = "session_id", nullable = false, columnDefinition = "UUID")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 16)
    private CashMovementType movementType;

    /** Positive amount; sign is carried by {@link #movementType}. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** The register's idempotency key (UUIDv7, unique per tenant); null only on pre-S16 rows. */
    @Column(name = "request_id", columnDefinition = "UUID", updatable = false)
    private UUID requestId;

    /** The fixed reason; null only on pre-S16 rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 32, updatable = false)
    private CashMovementReason reasonCode;

    /** Accounting petty-expense category code, for {@link CashMovementReason#PETTY_EXPENSE}. */
    @Column(name = "category_code", length = 64, updatable = false)
    private String categoryCode;

    /** The vendor paid, for {@link CashMovementReason#VENDOR_COD}. */
    @Column(name = "vendor_id", columnDefinition = "UUID", updatable = false)
    private UUID vendorId;

    /** The deposit bag, for {@link CashMovementReason#BANK_DROP}. */
    @Column(name = "bag_number", length = 64, updatable = false)
    private String bagNumber;

    /** The receipt, for {@link CashMovementReason#PETTY_EXPENSE}. */
    @Column(name = "receipt_reference", length = 128, updatable = false)
    private String receiptReference;

    /** Optional free text; the whole free-text reason of a pre-S16 row. */
    @Column(name = "note", length = 500, updatable = false)
    private String note;

    /** The cashier who recorded it, from the security context (ADR-0018). */
    @Column(name = "clerk_id", nullable = false, updatable = false)
    private String clerkId;

    /** The user id of the manager whose approval token it used, or null. */
    @Column(name = "approved_by", updatable = false)
    private String approvedBy;

    /** The approval it used, or null. */
    @Column(name = "approval_id", columnDefinition = "UUID", updatable = false)
    private UUID approvalId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
