package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.TaxIdRevealOutcome;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One reveal of a vendor's tax-registration number: the {@code VENDOR_TAX_ID_REVEALED} record (#2621, Security
 * ruling on #2617, ruling 4). Written in the reveal's own transaction <em>before</em> the number is returned;
 * no row means no number.
 *
 * <p>Append-only. Every column is {@code updatable = false}, there is no setter and no {@code @Version}, the
 * code never updates or deletes a row, and {@code V6} revokes {@code UPDATE} and {@code DELETE} from the
 * application role. The row never holds the number or {@code last4}. No foreign key to the vendor: the record
 * outlives anything it names, as {@link AuditAccessEntity} does.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier_vendor_tax_id_reveal")
public class SupplierVendorTaxIdRevealEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "reveal_id", updatable = false, nullable = false)
    private UUID revealId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "registration_id", nullable = false, updatable = false)
    private UUID registrationId;

    /** Denormalised, so the row reads without the vendor's current registrations. */
    @Column(name = "scheme", nullable = false, updatable = false, length = 32)
    private String scheme;

    /** Subject, from the security context (ADR-0018). Never client-supplied. */
    @Column(name = "revealed_by", nullable = false, updatable = false, length = 255)
    private String revealedBy;

    /** The roles the subject held, comma-separated and sorted; empty when it held none. */
    @Column(name = "revealed_by_roles", nullable = false, updatable = false, length = 1000)
    private String revealedByRoles;

    /** Kept on {@code REVEALED} rows only; {@code null} on {@code REASON_REJECTED} and {@code UNREADABLE} (V6 CHECK). */
    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;

    @Column(name = "revealed_at", nullable = false, updatable = false)
    private Instant revealedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 16)
    private TaxIdRevealOutcome outcome;
}
