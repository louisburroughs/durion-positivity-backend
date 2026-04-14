package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Represents an approval or rejection record for a price override.
 *
 * <p>
 * Provides a complete audit trail of who approved/rejected overrides and when.
 * Supports tracking approval workflow and compliance reporting.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "approval_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalRecord {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID recordId;

    /**
     * Reference to the price override being approved/rejected.
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "price_override_id", nullable = false)
    private PriceOverride priceOverride;

    /**
     * User ID of the approver/rejecter.
     */
    @Column(nullable = false)
    private UUID reviewerUserId;

    /**
     * Role of the reviewer (for audit trail).
     */
    @Column(nullable = false)
    private String reviewerRole;

    /**
     * Action taken: APPROVED or REJECTED.
     */
    @Column(nullable = false)
    private String action;

    /**
     * Optional comments provided by reviewer.
     */
    @Column(length = 2000)
    private String comments;

    /**
     * Timestamp of the approval/rejection action.
     */
    @Column(nullable = false, updatable = false)
    private Instant actionTimestamp;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * IP address of the reviewer (for security audit).
     */
    private String reviewerIpAddress;

    @PrePersist
    protected void prePersist() {
        if (actionTimestamp == null) {
            actionTimestamp = Instant.now(Clock.systemUTC());
        }
    }
}
