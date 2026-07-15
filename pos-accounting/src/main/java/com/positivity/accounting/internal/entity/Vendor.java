package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.VendorStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * AP vendor directory entry (Issue #816).
 *
 * <p>
 * Backs the vendor name typeahead used by the frontend vendor-payment page
 * (name → vendorId resolution). The vendorId is assigned by the upstream
 * system that owns the vendor relationship (purchasing / goods-received
 * events); the UUIDv7 generator honors assigned identifiers, and the
 * {@link Persistable} contract keeps {@code save()} on the {@code persist()}
 * path for new rows with pre-set ids.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "ap_vendor",
        indexes = {@Index(name = "idx_ap_vendor_name", columnList = "name")})
public class Vendor implements Persistable<UUID> {

    /**
     * Transient flag used by Spring Data JPA to distinguish new entities from
     * existing ones. Required because the entity uses an externally assigned
     * ID, which would otherwise cause {@code save()} to call {@code merge()}
     * instead of {@code persist()}.
     */
    @Transient
    private boolean isNew = true;

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_id", nullable = false, columnDefinition = "UUID")
    private UUID vendorId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "vendor_number", length = 50)
    private String vendorNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private VendorStatus status = VendorStatus.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Vendor(UUID vendorId, String name) {
        this.vendorId = vendorId;
        this.name = name;
    }

    @Override
    public UUID getId() {
        return vendorId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
