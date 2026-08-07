package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Accounting-side location dimension master data (issue #731, CAP-316 enrichment).
 *
 * <p>Keyed by {@code locationCode} — the value carried in the journal-entry-line
 * {@code locationId} dimension. Supplies the human-readable label and reporting currency for
 * location-scoped reports. Locations without a profile are treated as US plants
 * ({@code USD}, label = echoed location code).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "accounting_location_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LocationProfile {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "profile_id", columnDefinition = "UUID")
    private UUID profileId;

    /** Accounting {@code locationId} dimension value (e.g. {@code LOC-107}). Unique. */
    @NonNull
    @Column(name = "location_code", length = 100, nullable = false, unique = true)
    private String locationCode;

    /** Human-readable location label shown on reports. */
    @NonNull
    @Column(name = "location_label", length = 255, nullable = false)
    private String locationLabel;

    /** ISO-4217 reporting currency of the plant (e.g. {@code USD}, {@code MXN}). */
    @NonNull
    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Version
    @Column(name = "version")
    private Integer version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;
}
