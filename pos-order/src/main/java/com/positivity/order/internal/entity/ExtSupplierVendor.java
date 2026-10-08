package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * pos-order's copy of one pos-supplier vendor (CAP:550 S24, #2517; ADR-0044 R3; ADR-0070 Decision 7).
 *
 * <p>Written only by the {@code supplier.vendor.updated} branch of {@code SupplierOrderResultListener}, under
 * {@code ReplicaVersionGuard}. It holds every vendor, active or inactive, and only the R3 minimum a purchase order
 * needs: the vendor key, its number, its display name and its status, which {@code SupplierVendorGuard} reads before
 * a purchase order is created, approved, revised to another vendor or transmitted. No tax registration is copied.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_supplier_vendor")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtSupplierVendor extends TenantScopedEntity {

    /** The vendor's status, as pos-supplier publishes it. */
    public enum Status {
        ACTIVE,
        INACTIVE
    }

    @Id
    @Column(name = "vendor_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID vendorId;

    @Column(name = "vendor_number", nullable = false, length = 64)
    private String vendorNumber;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /** Envelope {@code aggregateVersion} of the last applied fact: the stale-fact guard. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Whether the vendor may be named on a new, approved or transmitted purchase order. */
    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** ArchUnit UUIDv7 rule hook: vendorId is a UUIDv7 issued by pos-supplier. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
