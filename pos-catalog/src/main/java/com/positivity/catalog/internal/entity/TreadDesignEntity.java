package com.positivity.catalog.internal.entity;

import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A manufacturer tread design, as MKCAT last published it (CAP-324 #1352, ADR-0044 R1).
 *
 * <h2>A design, not a product</h2>
 *
 * One tread design spans many sizes, each a distinct catalog product with its own EAN — MKCAT
 * carries none of them. This row is the design MKCAT described; {@code product.tread_design_id}
 * (nullable) is how zero or more catalog products get associated with it, never the reverse. Nothing
 * here may alter a product's identity or structure: no dimensions, no load index, no article code,
 * no price. A supplier fact that could redefine a product would hand a vendor edit rights over the
 * catalogue.
 *
 * <h2>Keyed for redelivery, not for ordering</h2>
 *
 * {@code (vendorProfileId, vendorVariantId)} is the identity a redelivery must resolve to the same
 * row. {@code SupplierCatalogUpdatedV1} carries no per-variant version — pos-supplier always
 * publishes {@code aggregateVersion=0} — so staleness here is judged by {@code contentHash}, not by
 * a monotonic counter: an unchanged republication is a no-op, and any changed one is applied,
 * last-write-wins. That is safe because content, unlike a price or a quantity, has no ordering
 * requirement a stale write could violate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "tread_design",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_tread_design_vendor_variant",
                        columnNames = {"vendor_profile_id", "vendor_variant_id"}))
public class TreadDesignEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** Profile alias as at the last applied event; descriptive snapshot only. */
    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    @Column(name = "vendor_variant_id", nullable = false, updatable = false, length = 100)
    private String vendorVariantId;

    @Column(name = "brand", length = 200)
    private String brand;

    @Column(name = "tread_design", length = 200)
    private String treadDesign;

    @Column(name = "tread_design_2", length = 200)
    private String treadDesign2;

    @Column(name = "product_name", length = 300)
    private String productName;

    @Column(name = "vehicle_type", length = 100)
    private String vehicleType;

    @Column(name = "seasonality", length = 100)
    private String seasonality;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** Whether any artwork on this design is still missing and awaiting retry (AC: "not as none"). */
    @Column(name = "has_unresolved_images", nullable = false)
    private boolean hasUnresolvedImages;

    /**
     * Where this design stands in the review cycle (#1645). Never null: a design that has only just
     * been applied is {@code UNMATCHED}, which is a state, not an absence of one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_state", nullable = false, length = 20)
    private TreadDesignMatchState matchState;

    /** When {@link #matchState} last changed — the age a review worklist sorts and ages out on. */
    @Column(name = "match_state_at", nullable = false)
    private Instant matchStateAt;

    /** The reviewer who last resolved this design; null while no person has ruled on it. */
    @Column(name = "resolved_by", length = 200)
    private String resolvedBy;

    /** Free text a reviewer left with that ruling. */
    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    /** Optional date a {@code DEFERRED} design should come back to a reviewer's attention. */
    @Column(name = "defer_until")
    private Instant deferUntil;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
