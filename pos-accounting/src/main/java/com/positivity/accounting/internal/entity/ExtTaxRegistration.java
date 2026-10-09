package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Accounting's copy of one pos-tax tenant tax registration (CAP:550 S32c; ADR-0071 §7, ADR-0044 R3), written only
 * by {@code tax.registration.changed} and guarded by the fact's {@code aggregateVersion}. Read as of a business date
 * by its inclusive effective dates (AW49). The number is INTERNAL (ADR-0072 Decision 1) and never logged, so {@code
 * toString} leaves it out.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_tax_registration")
public class ExtTaxRegistration extends TenantScopedEntity {

    @Id
    @AssignedIdentifier("pos-tax's registration id, carried on tax.registration.changed as the aggregate; minting one"
            + " here would sever the copy from its owning fact and defeat the version guard")
    @Column(name = "registration_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID registrationId;

    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "regime", length = 32, nullable = false)
    private String regime;

    @ToString.Exclude
    @Column(name = "registration_number", length = 32, nullable = false)
    private String registrationNumber;

    @Column(name = "jurisdiction_code", length = 32, nullable = false)
    private String jurisdictionCode;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    /** When pos-tax committed the change the copy holds. */
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    /** Whether the registration is in effect on {@code date} (both ends inclusive). */
    public boolean inEffectOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}
