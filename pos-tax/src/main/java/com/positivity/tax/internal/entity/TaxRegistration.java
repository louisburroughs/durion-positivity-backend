package com.positivity.tax.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A tenant's registration for one country's indirect-tax regime (CAP:550 S32c; ADR-0071 §7). pos-tax is its source
 * of truth; pos-accounting and pos-order keep copies from {@code tax.registration.changed}.
 *
 * <p>The country and regime come from the configured country profiles (S32a) and never change once created. The
 * number is stored only in the normalised form S32b's shape check accepted; it is INTERNAL (ADR-0072 Decision 1) and
 * never logged, so {@code toString} leaves it out. The jurisdiction is derived from the regime's configuration. A
 * registration is never deleted: it is ended by {@code effectiveTo} (inclusive).
 *
 * <p>Timestamps come from JPA auditing on the application clock (ADR-0024); the actor columns hold the forwarded
 * {@code X-User-Id} the front door sent (ADR-0018), set by the service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "tax_registration")
public class TaxRegistration extends TenantScopedEntity {

    /** UUID v7 primary key (ADR-0013). */
    @Id
    @UUIDv7Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** ISO 3166-1 alpha-2 country whose profile declares the regime. */
    @Column(name = "country_code", nullable = false, updatable = false, length = 2)
    private String countryCode;

    /** The regime code, as the country profile declares it. */
    @Column(name = "regime", nullable = false, updatable = false, length = 32)
    private String regime;

    /** The normalised, shape-checked number. INTERNAL; never logged. */
    @ToString.Exclude
    @Column(name = "registration_number", nullable = false, length = 32)
    private String registrationNumber;

    /** The regime's single region when it lists exactly one, else the country. Never input. */
    @Column(name = "jurisdiction_code", nullable = false, updatable = false, length = 32)
    private String jurisdictionCode;

    /** Inclusive first day in effect. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Inclusive last day in effect; {@code null} while open-ended. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Optimistic lock; strictly advancing, published as the fact's version (409 {@code OPTIMISTIC_LOCK}). */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The forwarded actor who created the registration. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The forwarded actor of the last change. */
    @Column(name = "updated_by", nullable = false)
    private String updatedBy;
}
