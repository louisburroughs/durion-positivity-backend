package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The vendor's own article code for one product at one vendor, current state (CAP-320 #1347,
 * ADR-0053 §5).
 *
 * <p>{@link SupplierPriceEntryEntity} already carries this field, but as append-only history — one
 * row per PRICAT import line, never queryable as "the code right now" without picking a tie-break.
 * This table is that current-state projection, upserted from the same PRICAT ingestion, and is
 * what {@link com.positivity.catalog.internal.config.CatalogFactPublisher} reads to publish
 * {@code catalog.supplier_article_code.updated} for pos-order's purchase-order transmission
 * (CAP-320 #1330), the same way {@code ProductEntity} is the current-state row that feeds
 * {@code catalog.product.updated}.
 *
 * <h2>Sticky, not cleared</h2>
 *
 * A row is written only when an import line states a non-blank code; a later import that omits one
 * for the same vendor and product leaves the last-known code standing. A vendor PRICAT fetch is
 * one page of the vendor's catalogue at one moment, not a full snapshot the way
 * {@code catalog.product.updated} is — a line's absence this cycle is not the vendor unsetting a
 * code (mirrors {@code SupplierPriceCatalogEventsListener}'s "nothing here deletes" rule for the
 * append-only entries this table is derived from).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_article_code",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_supplier_article_code_vendor_product",
                        columnNames = {"vendor_profile_id", "product_id"}),
        indexes = @Index(name = "idx_supplier_article_code_product", columnList = "product_id"))
public class SupplierArticleCodeEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Vendor profile identity from pos-supplier (ADR-0050 §1); canonical key with {@code productId}. */
    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** Profile alias as at the last applied import; the join key pos-order's purchase orders carry. */
    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "supplier_article_code", nullable = false, length = 64)
    private String supplierArticleCode;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
