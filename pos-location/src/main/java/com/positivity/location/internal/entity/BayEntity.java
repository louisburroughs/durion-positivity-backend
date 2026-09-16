package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Bay aggregate for location service operations.
 *
 * Issue: CAP-136 #77
 */
@Entity
@Table(
        name = "bays",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_bays_location_normalized_name",
                    columnNames = {"location_id", "normalized_name"})
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class BayEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    public UUID getLocationId() {
        return location != null ? location.getId() : null;
    }

    @Column(nullable = false)
    private String name;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Column(name = "bay_type", nullable = false, length = 50)
    private String bayType;

    @Builder.Default
    @Column(nullable = false, length = 50)
    private String status = "ACTIVE";

    @Column(name = "max_concurrent_vehicles", nullable = false)
    private Integer maxConcurrentVehicles;

    /**
     * Catalog {@code operationCode}s this bay type is the only one able to perform (CAP-325 D14).
     * Empty for a general bay. Values are validated against the {@code ext_catalog_service}
     * replica on write and stored uppercased. Renamed from {@code serviceCapabilityIds}: the
     * values were always codes, never identifiers, and the old name lied.
     */
    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "service_capability_codes", columnDefinition = "TEXT")
    private List<String> serviceCapabilityCodes = new ArrayList<>();

    /**
     * Heaviest GVWR class (1–8) the bay accepts; null when unconstrained (CAP-325 D13). A class
     * number, not a token: Light 1–3, Medium 4–6, Heavy 7–8, and the boundary at class 4 is where
     * ASE's Medium/Heavy Truck series begins.
     */
    @Column(name = "max_duty_class")
    private Integer maxDutyClass;

    /**
     * Aggregate version backing the {@code location.bay.*} facts' {@code aggregateVersion}
     * (issue #1668, contract established in #1486). Strictly increments on every committed
     * mutation, so two bay changes landing in the same millisecond can never tie and a consumer's
     * stale guard stays sound. {@code LocationFactPublisher} flushes before reading it so the
     * emitted fact carries the version the row is about to commit as. Seeded to 0 by migration V9.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "last_modified_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        normalizedName = normalizeName(name);
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (serviceCapabilityCodes == null) {
            serviceCapabilityCodes = new ArrayList<>();
        }
    }

    @PreUpdate
    void onUpdate() {
        normalizedName = normalizeName(name);
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
