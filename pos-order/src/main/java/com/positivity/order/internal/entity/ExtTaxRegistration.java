package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * pos-order's copy of one pos-tax tenant tax registration (CAP:550 S32c; ADR-0071 §7, ADR-0044 R3), written only by
 * {@code tax.registration.changed} and guarded by the fact's {@code version}. Keyed by the pos-tax registration id.
 * Read as of a business date (AW49) to decide which drawer fields to show. The registration number is not copied:
 * the drawer needs only whether a regime is registered on a date.
 */
@Entity
@Table(name = "ext_tax_registration")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtTaxRegistration extends TenantScopedEntity {

    @Id
    @Column(name = "registration_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID registrationId;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "regime", nullable = false, length = 32)
    private String regime;

    @Column(name = "jurisdiction_code", nullable = false, length = 32)
    private String jurisdictionCode;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    /** ArchUnit UUIDv7 rule hook: the id is a UUIDv7 issued by pos-tax. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
