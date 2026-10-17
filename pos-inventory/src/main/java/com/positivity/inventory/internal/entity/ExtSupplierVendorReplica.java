package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.VendorStatus;
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
 * pos-inventory's copy of one pos-supplier vendor (CAP:550 S36, #2525; ADR-0044 R3).
 *
 * <p>Written only by the {@code supplier.vendor.updated} branch of {@code SupplierEventsListener}, through {@code
 * SupplierVendorReplicaWriter}, under a stale-version guard. It holds every vendor, active or inactive, and only what a
 * purchase suggestion shows and checks: the vendor key, its number, its display name and its status. A vendor feed
 * mapping may name only a vendor held here; the mapping stores the vendor id with no foreign key.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_supplier_vendor")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtSupplierVendorReplica extends TenantScopedEntity {

    @Id
    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "vendor_number", length = 30)
    private String vendorNumber;

    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private VendorStatus status;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /** Envelope {@code aggregateVersion} of the last applied fact: the stale-fact guard. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the vendor id is a UUIDv7 issued by pos-supplier, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
