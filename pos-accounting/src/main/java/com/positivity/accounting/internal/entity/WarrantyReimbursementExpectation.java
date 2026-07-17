package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Expected vendor credit for a warranty reimbursement (issue #927), materialized from
 * {@code warranty.events.v1}. pos-warranty owns the reimbursement lifecycle; this row is
 * accounting's expectation record for AP credit matching. Status is {@code EXPECTED} on
 * submission, then the owner's terminal {@code ReimbursementStatus} name on resolution
 * (e.g. {@code APPROVED}, {@code DENIED}, {@code WRITTEN_OFF} — stop expecting the credit).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Table(name = "warranty_reimbursement_expectation")
public class WarrantyReimbursementExpectation {

    /** Status while the credit is still awaited (set on {@code warranty.reimbursement.submitted}). */
    public static final String STATUS_EXPECTED = "EXPECTED";

    @Id
    @Column(name = "reimbursement_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID reimbursementId;

    @Column(name = "claim_id", columnDefinition = "UUID", nullable = false)
    private UUID claimId;

    @Column(name = "claim_code", length = 64)
    private String claimCode;

    @Column(name = "provider_id", columnDefinition = "UUID")
    private UUID providerId;

    @Column(name = "ap_vendor_id", columnDefinition = "UUID")
    private UUID apVendorId;

    @Column(name = "amount_requested", precision = 12, scale = 2)
    private BigDecimal amountRequested;

    @Column(name = "vendor_claim_reference", length = 128)
    private String vendorClaimReference;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "amount_approved", precision = 12, scale = 2)
    private BigDecimal amountApproved;

    @Column(name = "credit_reference", length = 128)
    private String creditReference;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013, generator-owned upstream): the key is pos-warranty's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
