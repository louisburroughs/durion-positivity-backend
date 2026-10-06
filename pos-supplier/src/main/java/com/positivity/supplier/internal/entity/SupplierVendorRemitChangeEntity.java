package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.RemitChangeStatus;
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
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A request to change a vendor's remit-to (#2516, SPEC §4.9 "Remit-to changes need a second
 * person"). Invisible outside pos-supplier while {@code PENDING}; applied only when someone other
 * than the requester approves it. Requester, decider, reason and notes are kept permanently.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier_vendor_remit_change")
@EntityListeners(AuditingEntityListener.class)
public class SupplierVendorRemitChangeEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "change_id", updatable = false, nullable = false)
    private UUID changeId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_remit_to", nullable = false, updatable = false, columnDefinition = "jsonb")
    private VendorRemitTo proposedRemitTo;

    @Column(name = "reason", nullable = false, updatable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RemitChangeStatus status;

    /** The vendor's remit-to version when the change was requested. */
    @Column(name = "from_version", nullable = false, updatable = false)
    private int fromVersion;

    /** The version the approval produced; {@code null} unless {@code APPROVED}. */
    @Column(name = "to_version")
    private Integer toVersion;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** The approver's verification note, or the rejection note. */
    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
