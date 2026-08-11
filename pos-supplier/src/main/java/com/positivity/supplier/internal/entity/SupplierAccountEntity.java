package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Commercial account of a vendor profile (ADR-0050 §5): {@code BILLING} is the
 * invoicing/settlement account (one per profile, legal-entity level, no location);
 * {@code DELIVERY} maps one pos-location UUID to its vendor account number. Account numbers
 * are ordinary profile data, distinct from credentials (ADR-0050 §4).
 *
 * <p>The role/location pairing is enforced three times over: the DB CHECK
 * {@code chk_saccount_role_location}, the entity invariant ({@link #validateRoleLocation()}),
 * and the {@code CommercialAccountRequest} record. Uniqueness (one BILLING per profile, one
 * DELIVERY per (profile, location)) is the NULLS NOT DISTINCT unique constraint
 * {@code supplier_account_profile_role_location_key}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_account",
        indexes = {@Index(name = "idx_saccount_profile", columnList = "vendor_profile_id")},
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "supplier_account_profile_role_location_key",
                    columnNames = {"vendor_profile_id", "role", "delivery_location_id"})
        })
@EntityListeners(AuditingEntityListener.class)
public class SupplierAccountEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Owning {@code supplier_profile.vendor_profile_id} (DB FK {@code fk_saccount_profile}). */
    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private SupplierAccountRole role;

    @Column(name = "account_number", nullable = false, length = 100)
    private String accountNumber;

    /** Identification agency code (e.g. EAN/GLN), when the vendor requires one. */
    @Column(name = "agency_code", length = 50)
    private String agencyCode;

    /** pos-location UUID of the receiving location; present exactly for DELIVERY rows. */
    @Column(name = "delivery_location_id")
    private UUID deliveryLocationId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Entity-level guard of the ADR-0050 §5 role/location invariant: DELIVERY requires a
     * location, BILLING forbids one. Mirrors the DB CHECK so a violating entity never reaches
     * the flush.
     */
    @PrePersist
    @PreUpdate
    void validateRoleLocation() {
        if (role == SupplierAccountRole.DELIVERY && deliveryLocationId == null) {
            throw new IllegalStateException("DELIVERY supplier account requires a deliveryLocationId");
        }
        if (role == SupplierAccountRole.BILLING && deliveryLocationId != null) {
            throw new IllegalStateException("BILLING supplier account must not carry a deliveryLocationId");
        }
    }
}
