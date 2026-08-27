package com.positivity.location.internal.entity;

import com.positivity.location.internal.enums.AllowNewProductPolicy;
import com.positivity.location.internal.enums.StorageCategory;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * Storage location aggregate for floor/shelf/bin/cage/truck topology.
 *
 * Issue: CAP-214 #39
 */
@Entity
@Table(name = "storage_location")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class StorageLocationEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(unique = false)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private StorageLocationType type;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private StorageLocationStatus status = StorageLocationStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Location site;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_storage_location_id")
    private StorageLocationEntity parentStorageLocation;

    public UUID getSiteId() {
        return site != null ? site.getId() : null;
    }

    public UUID getParentStorageLocationId() {
        return parentStorageLocation != null ? parentStorageLocation.getId() : null;
    }

    /**
     * Putaway capability of the location — what it is fit to hold — distinct from {@link #type},
     * which is physical topology (issue #1514).
     *
     * <p>Nullable on purpose: rows that predate the capability declare none, so V8 needed no
     * backfill. Null is resolved to {@link StorageCategory#GENERAL} at every read boundary via
     * {@link StorageCategory#orDefault}, never here, so the stored state stays honest about
     * whether an operator ever declared one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_category_code", length = 30)
    private StorageCategory storageCategoryCode;

    /**
     * Whether the location provides spill/hazard containment. Backs the putaway compatibility
     * matrix, which requires containment for battery and oil storage (issue #1514).
     */
    @Column(name = "hazard_containment", nullable = false)
    private boolean hazardContainment;

    /** Whether the location will take stock of a product it is not already holding (#1514). */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "allow_new_product", nullable = false, length = 30)
    private AllowNewProductPolicy allowNewProduct = AllowNewProductPolicy.MIXED;

    @Column(name = "capacity", columnDefinition = "TEXT")
    private String capacity;

    @Column(name = "temperature", columnDefinition = "TEXT")
    private String temperature;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * JPA optimistic-lock counter, also published as the {@code location.storage-location.updated}
     * envelope's {@code aggregateVersion} (#1486): it strictly increments on every committed
     * mutation, replacing the retired {@code Instant.now(clock)}-stamped emission timestamp that
     * could tie when two mutations landed in the same millisecond. Migration V6 seeded it from
     * wall-clock millis at migration time, not {@code updated_at}, so the published sequence
     * continues above every version consumers already hold.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = StorageLocationStatus.ACTIVE;
        }
        // allow_new_product is NOT NULL in the schema; an entity built through the no-args
        // constructor bypasses the builder default, so coerce it the same way status is (#1514).
        if (allowNewProduct == null) {
            allowNewProduct = AllowNewProductPolicy.MIXED;
        }
    }
}
