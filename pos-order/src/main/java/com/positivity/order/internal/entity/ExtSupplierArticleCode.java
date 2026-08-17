package com.positivity.order.internal.entity;

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
 * The vendor's own article code for one product at one vendor, replicated from
 * {@code catalog.supplier_article_code.updated} (CAP-320 #1347, ADR-0044 R3).
 *
 * <p>This is what lets a purchase-order line name an article to a vendor that identifies its
 * catalogue by its own codes rather than by EAN — without it that vendor cannot be transmitted to
 * at all, every line reporting {@code ARTICLE_NOT_IDENTIFIABLE} (CAP-320 #1330).
 *
 * <h2>Keyed by {@code supplierRef}, not {@code vendorProfileId}</h2>
 *
 * The published fact is keyed by {@code (vendorProfileId, productId)}, pos-catalog's canonical
 * identity for a vendor. {@code PurchaseOrderEntity} carries only {@code supplierRef} — the
 * descriptive alias — never the vendor profile id, and ADR-0044 R1 forbids resolving one from the
 * other synchronously across the domain wall. This replica is therefore keyed the way
 * {@code PurchaseOrderTransmissionService} already looks vendors up everywhere else in this module:
 * by {@code supplierRef}. {@code vendorProfileId} is kept alongside for traceability only.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "ext_supplier_article_code",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_ext_supplier_article_code_ref_product",
                        columnNames = {"supplier_ref", "product_id"}),
        indexes = @Index(name = "idx_ext_supplier_article_code_product", columnList = "product_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtSupplierArticleCode {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The alias this replica is looked up by; see class javadoc for why. */
    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    /** Kept for traceability only; never the lookup key in this module (see class javadoc). */
    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "supplier_article_code", nullable = false, length = 64)
    private String supplierArticleCode;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
