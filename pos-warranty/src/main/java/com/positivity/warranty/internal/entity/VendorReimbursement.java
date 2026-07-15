package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Back-office vendor-money lifecycle, one per claim per provider (PRD §3.7). Every transition
 * emits an outbox event (PRD §9.3) that pos-accounting consumes to match expected vendor
 * credits — warranty never writes to accounting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "vendor_reimbursement",
        indexes = {
            @Index(name = "idx_vreimb_claim", columnList = "claim_id"),
            @Index(name = "idx_vreimb_status", columnList = "status"),
            @Index(name = "idx_vreimb_provider", columnList = "provider_id")
        })
@EntityListeners(AuditingEntityListener.class)
public class VendorReimbursement {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReimbursementStatus status = ReimbursementStatus.NOT_SUBMITTED;

    @Column(name = "amount_requested", precision = 19, scale = 4)
    private BigDecimal amountRequested;

    @Column(name = "amount_approved", precision = 19, scale = 4)
    private BigDecimal amountApproved;

    /** The manufacturer's/vendor's claim reference number (recorded, not integrated — PRD §2). */
    @Column(name = "vendor_claim_reference", length = 100)
    private String vendorClaimReference;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "credit_reference", length = 100)
    private String creditReference;

    @Column(name = "credit_received_at")
    private Instant creditReceivedAt;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
}
