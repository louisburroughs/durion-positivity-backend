package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.VendorStatus;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The vendor master (#2516, ADR-0070 Decision 2, SPEC §4.9): one row per party the shop buys from
 * or pays, with or without a supplier connection. Connection profiles belong to exactly one vendor.
 *
 * <p>{@code vendorNumber} is set once and never changes (YAML profiles bind to it, people quote it).
 * {@code remitTo} is the <em>approved</em> remit-to only: a later change waits in
 * {@link SupplierVendorRemitChangeEntity} until a second person approves it. A vendor is never
 * deleted. No bank details are held here (OI-14).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier_vendor")
@EntityListeners(AuditingEntityListener.class)
public class SupplierVendorEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_id", updatable = false, nullable = false)
    private UUID vendorId;

    @Column(name = "vendor_number", nullable = false, updatable = false, length = 30)
    private String vendorNumber;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_registrations", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<VendorTaxRegistration> taxRegistrations = new ArrayList<>();

    /** The approved remit-to; {@code null} while {@link #remitToVersion} is 0. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "remit_to", columnDefinition = "jsonb")
    private VendorRemitTo remitTo;

    @Column(name = "remit_to_version", nullable = false)
    private int remitToVersion;

    @Column(name = "remit_to_changed_at")
    private Instant remitToChangedAt;

    @Column(name = "remit_to_requested_by")
    private String remitToRequestedBy;

    @Column(name = "remit_to_approved_by")
    private String remitToApprovedBy;

    /** {@code DUE_ON_RECEIPT} or {@code NET<n>}; {@code null} only on a backfilled vendor. */
    @Column(name = "default_payment_terms", length = 16)
    private String defaultPaymentTerms;

    /** ISO 4217; {@code null} only on a backfilled vendor. */
    @Column(name = "default_currency", length = 3)
    private String defaultCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VendorStatus status;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
