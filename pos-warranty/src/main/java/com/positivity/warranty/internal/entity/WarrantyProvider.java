package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.ProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
 * Warranty provider — who pays (PRD §3.1). Other services are referenced by bare UUID
 * ({@code apVendorId} → pos-accounting ap_vendor, {@code manufacturerId} → pos-catalog).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "warranty_provider",
        indexes = {
            @Index(name = "idx_wprovider_manufacturer", columnList = "manufacturer_id"),
            @Index(name = "idx_wprovider_ap_vendor", columnList = "ap_vendor_id")
        })
@EntityListeners(AuditingEntityListener.class)
public class WarrantyProvider {

    /** Provider status value: accepts new policies/claims. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Provider status value: retired; existing claims remain readable. */
    public static final String STATUS_INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 32)
    private ProviderType providerType;

    /** Optional link to pos-accounting {@code ap_vendor.vendorId} for credit matching. */
    @Column(name = "ap_vendor_id")
    private UUID apVendorId;

    /** Optional link to pos-catalog {@code ProductEntity.manufacturerId} for policy lookup. */
    @Column(name = "manufacturer_id")
    private UUID manufacturerId;

    @Column(name = "claim_submission_method")
    private String claimSubmissionMethod;

    @Column(name = "portal_url", length = 1024)
    private String portalUrl;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

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
}
