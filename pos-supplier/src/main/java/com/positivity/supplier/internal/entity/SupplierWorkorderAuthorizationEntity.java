package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A fleet workorder authorization at a vendor (CAP-323 #1229).
 *
 * <h2>Not a shadow of the workorder</h2>
 *
 * This row holds what the vendor said and how far this side got in asking. It holds nothing about
 * what the workorder is doing, because pos-workorder owns that (ADR-0049 §2). The temptation with a
 * table like this is to cache a workorder status "for convenience" and then have two answers to the
 * same question, one of them stale.
 *
 * <h2>The row is written before the vendor is called</h2>
 *
 * A request that was sent and never answered is the interesting case — it is the one where a shop
 * waits on a fleet that will never reply — and it only exists in the record if the row predates the
 * answer.
 *
 * <h2>Two independent lifecycles</h2>
 *
 * {@code status} tracks the authorization decision; {@code approvalStatus} tracks the completion
 * sign-off that happens much later. They are separate columns rather than one because they fail
 * independently: work that was authorized can still fail to be approved on completion, and
 * collapsing the two would make an unsigned-off job look unauthorized.
 */
@Entity
@Table(name = "supplier_workorder_authorization")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierWorkorderAuthorizationEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "supplier_workorder_authorization_id")
    private UUID supplierWorkorderAuthorizationId;

    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    /** The profile's alias when the authorization was requested; descriptive, never a key. */
    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkorderAuthorizationStatus status;

    /** The vendor's own identifier; required to approve completion later. */
    @Column(name = "vendor_authorization_id", length = 100)
    private String vendorAuthorizationId;

    /** Where an asynchronously accepted request said its result would appear (202 {@code Location}). */
    @Column(name = "poll_location", length = 1000)
    private String pollLocation;

    @Column(name = "contract_reference", length = 100)
    private String contractReference;

    @Column(name = "authorized_amount", precision = 19, scale = 4)
    private BigDecimal authorizedAmount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "reason_text", length = 1000)
    private String reasonText;

    /** Why this row needs a human. Set together with a {@code MANUAL_REVIEW} status, never alone. */
    @Column(name = "review_reason", length = 1000)
    private String reviewReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private WorkorderApprovalStatus approvalStatus;

    @Column(name = "approval_attempts", nullable = false)
    private int approvalAttempts;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
