package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.FeedVendorType;
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
 * The pos-supplier vendor a manufacturer or distributor feed vendor is (CAP:550 S36, #2525).
 *
 * <p>{@code feedVendorRef} is the manufacturer id (a UUID, stored lower-case) or the distributor id as its feed carries
 * it; {@code vendorId} is the pos-supplier vendor id, checked against {@link ExtSupplierVendorReplica} when the mapping
 * is made and carrying no foreign key. One mapping per {@code (tenant, feedType, feedVendorRef)}.
 */
@Entity
@Table(name = "vendor_feed_mapping")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorFeedMapping extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "mapping_id", nullable = false, updatable = false)
    private UUID mappingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feed_type", nullable = false, length = 20)
    private FeedVendorType feedType;

    @Column(name = "feed_vendor_ref", nullable = false)
    private String feedVendorRef;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "note", length = 500)
    private String note;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
