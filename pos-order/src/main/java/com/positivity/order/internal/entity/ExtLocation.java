package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only shop-location replica (ADR-0044 §6, parity story B3): fed by
 * {@code location.events.v1}; supplies the tax jurisdiction address (pos-workorder ext_location
 * pattern, #892) and the friendly location code.
 */
@Entity
@Table(name = "ext_location")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtLocation {

    @Id
    @Column(name = "location_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID locationId;

    @Column(length = 64)
    private String code;

    @Column
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column
    private String addressLine1;

    @Column
    private String addressLine2;

    @Column(length = 128)
    private String city;

    @Column(length = 64)
    private String region;

    @Column(length = 32)
    private String postalCode;

    @Column(length = 8)
    private String country;

    @Column(nullable = false)
    private long aggregateVersion;

    @Column(nullable = false)
    private Instant syncedAt;

    /** ArchUnit UUIDv7 rule hook: locationId is a UUIDv7 issued by pos-location. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
