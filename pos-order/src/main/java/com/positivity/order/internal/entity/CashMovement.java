package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "cash_movement")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashMovement {

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

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "clerk_id", nullable = false)
    private String clerkId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
