package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Accounting's copy of a pos-supplier vendor (CAP:550 S24, #2517; SPEC-accounting-workspace §4.9 "Accounting's copy";
 * ADR-0044 R1, R3). Written only by the {@code supplier.vendor.updated} branch of {@code SupplierEventsListener}, under
 * {@code ReplicaVersionGuard}; it holds every vendor, in either status. Bills, AP payments and the vendor reads name the
 * vendor by {@link #vendorId}, the pos-supplier id.
 *
 * <p><b>Tax registrations</b> are kept as the {@code schemaVersion} 2 fact carries them, {@code [{scheme, region,
 * last4}]}: no full registration number is ever received or stored (Security ruling #2617, ADR-0072). {@code last4} is
 * CONFIDENTIAL, so {@link #toString()} leaves the registrations out, and no log line names them.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString(exclude = {"taxRegistrations", "remitTo"})
@Entity
@Table(name = "ext_supplier_vendor")
public class ExtSupplierVendor extends TenantScopedEntity {

    /** {@code status} of a vendor that takes new bills, payments and purchase orders. */
    public static final String ACTIVE = "ACTIVE";

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("pos-supplier's vendorId, the vendor fact's aggregateId")
    @Column(name = "vendor_id", nullable = false, columnDefinition = "UUID")
    private UUID vendorId;

    /** The tenant-unique reference people quote, e.g. {@code V-000123}. */
    @Column(name = "vendor_number", length = 50, nullable = false)
    private String vendorNumber;

    @Column(name = "display_name", length = 200, nullable = false)
    private String displayName;

    /** {@code ACTIVE} or {@code INACTIVE}, as the fact carries it. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /** The approved remit-to address as the fact carries it; null while the vendor has none. Never bank details. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "remit_to")
    private Map<String, Object> remitTo;

    /** 0 with no remit-to, then +1 per approved change; what payment compares with the bill's approved version. */
    @Column(name = "remit_to_version", nullable = false)
    private int remitToVersion;

    @Column(name = "remit_to_changed_at")
    private Instant remitToChangedAt;

    @Column(name = "remit_to_requested_by", length = 255)
    private String remitToRequestedBy;

    @Column(name = "remit_to_approved_by", length = 255)
    private String remitToApprovedBy;

    @Column(name = "default_payment_terms", length = 20)
    private String defaultPaymentTerms;

    @Column(name = "default_currency", length = 3)
    private String defaultCurrency;

    /** {@code [{scheme, region, last4}]}; never a full number. Excluded from {@link #toString()}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_registrations", nullable = false)
    private List<Map<String, String>> taxRegistrations = new ArrayList<>();

    /** The principal name of who created the vendor, in the form approval fields record (rule 9). */
    @Column(name = "created_by", length = 255, nullable = false)
    private String createdBy;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Whether the vendor takes new bills, payments and purchase orders (AW23). */
    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
