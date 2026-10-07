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
import jakarta.persistence.Version;
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
 * A manager's single-use approval of one drawer cash movement (CAP:550 S16, #2512; AW31), minted by
 * the step-up endpoint after pos-security-service verified the manager's own credentials. Only the
 * SHA-256 hash of the token is stored. The approval is bound to the session, the reason, the amount
 * and the category or vendor; it expires after the configured time (five minutes by default) and is
 * used by at most one movement.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "cash_movement_approval")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashMovementApproval extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "approval_id", columnDefinition = "UUID")
    private UUID approvalId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "session_id", nullable = false, columnDefinition = "UUID", updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32, updatable = false)
    private CashMovementReason reasonCode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "category_code", length = 64, updatable = false)
    private String categoryCode;

    @Column(name = "vendor_id", columnDefinition = "UUID", updatable = false)
    private UUID vendorId;

    /** Lowercase hex SHA-256 of the token; the token itself is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    /** The verified manager's user id, from pos-security-service's step-up check. */
    @Column(name = "approver_user_id", nullable = false, columnDefinition = "UUID", updatable = false)
    private UUID approverUserId;

    /** The cashier who called the step-up (ADR-0018). */
    @Column(name = "requested_by", nullable = false, updatable = false)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CashMovementApprovalStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_by_movement_id", columnDefinition = "UUID")
    private UUID usedByMovementId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
