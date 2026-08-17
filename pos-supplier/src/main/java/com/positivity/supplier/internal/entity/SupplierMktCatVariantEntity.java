package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A tread design variant as the MKCAT catalogue last published it (CAP-324 #1230).
 *
 * <h2>Staging, not catalogue</h2>
 *
 * Nothing here is product data. pos-catalog owns what a product is; this records what a manufacturer
 * says about how it markets a design. Keeping the two apart is what stops a supplier fact from being
 * able to redefine a product (ADR-0044 R1).
 *
 * <h2>The content hash is what makes diffing possible</h2>
 *
 * Without it, deciding whether a variant changed since the last run would mean comparing every
 * field, every text and every image reference of every variant on every run — and a catalogue that
 * republishes itself nightly would be indistinguishable from one that changes nightly. The hash
 * covers everything that gets published, so an unchanged republication is recognised and no event
 * goes out.
 */
@Entity
@Table(name = "supplier_mktcat_variant")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierMktCatVariantEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "supplier_mktcat_variant_id")
    private UUID supplierMktCatVariantId;

    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    /** The catalogue's own variant id. A design, not an article — there is no EAN to match on. */
    @Column(name = "vendor_variant_id", nullable = false, length = 100)
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

    /** The published texts, as JSON, so a republication can be rebuilt without re-fetching. */
    @Column(name = "texts_json", nullable = false, columnDefinition = "TEXT")
    private String textsJson;

    /** The published image references, as JSON. */
    @Column(name = "images_json", nullable = false, columnDefinition = "TEXT")
    private String imagesJson;

    /**
     * Whether any artwork is still missing.
     *
     * <p>A column rather than something derived from {@link #imagesJson} at query time: the retry
     * runner selects on it, and a scan that parsed JSON per row to find its work would be the one
     * query guaranteed to get slower as the catalogue grows.
     */
    @Column(name = "has_unresolved_images", nullable = false)
    private boolean hasUnresolvedImages;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_published_at")
    private Instant lastPublishedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
