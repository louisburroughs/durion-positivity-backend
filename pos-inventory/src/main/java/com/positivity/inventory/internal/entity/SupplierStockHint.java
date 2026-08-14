package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * What one vendor last said about the availability of one article (CAP-322, #1312).
 *
 * <p>This is <strong>not owned stock</strong>. It never enters inventory valuation (ADR-0048) and
 * no on-hand ATP path reads it; the guarantee is structural rather than procedural, because the
 * hints live in their own table that no valuation or ATP query joins and an ArchUnit rule holds the
 * repository to the supplier-hint classes.
 *
 * <p>One row per {@code (vendorProfileId, identityKind, identityValue)}, latest-only: a newer
 * snapshot supersedes an older one in place. A snapshot that omits a previously reported article
 * does <strong>not</strong> touch its row — the previous hint stands with its own {@code asOf}, so
 * the vendor's silence shows up as growing staleness rather than as an out-of-stock.
 *
 * <p>{@code availableQuantity} carries the producer's three-way distinction verbatim: a stated
 * quantity (of which {@code 0} is an explicit "we have none"), {@code null} for an article the
 * vendor listed without stating a quantity, and — for an article the vendor never mentioned — no
 * row at all.
 */
@Entity
@Table(
        name = "supplier_stock_hint",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_supplier_stock_hint_identity",
                        columnNames = {"vendor_profile_id", "identity_kind", "identity_value"}),
        indexes = {
            @Index(name = "idx_supplier_stock_hint_product", columnList = "resolved_product_id"),
            @Index(name = "idx_supplier_stock_hint_ean", columnList = "article_ean"),
            @Index(name = "idx_supplier_stock_hint_buyer_code", columnList = "buyers_article_id"),
            @Index(name = "idx_supplier_stock_hint_supplier_code", columnList = "supplier_article_code"),
            @Index(name = "idx_supplier_stock_hint_resolution", columnList = "resolution_status"),
            @Index(name = "idx_supplier_stock_hint_snapshot", columnList = "source_snapshot_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class SupplierStockHint {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID hintId;

    /** The vendor whose warehouse this describes. Hints are never aggregated across vendors. */
    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    /** Which of the vendor's identifiers was elected as this row's identity. */
    @Enumerated(EnumType.STRING)
    @Column(name = "identity_kind", nullable = false, length = 20)
    private SupplierHintIdentityKind identityKind;

    /** The elected identifier's value, exactly as the vendor stated it. */
    @Column(name = "identity_value", nullable = false, length = 255)
    private String identityValue;

    /** EAN/GTIN as stated, whether or not it was elected as the identity. */
    @Column(name = "article_ean", length = 64)
    private String articleEan;

    /** The vendor's own article code: an alias in the vendor's namespace, never an id of ours. */
    @Column(name = "supplier_article_code", length = 255)
    private String supplierArticleCode;

    /** Our article code as the vendor holds it. */
    @Column(name = "buyers_article_id", length = 255)
    private String buyersArticleId;

    @Column(name = "description", length = 512)
    private String description;

    /**
     * Quantity the vendor reported. Null means the vendor listed the article and stated no
     * quantity, which is not zero; zero means the vendor explicitly reported none.
     */
    @Column(name = "available_quantity")
    private Integer availableQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 20)
    private SupplierHintResolutionStatus resolutionStatus;

    /** Catalog product this article resolved to, null while unresolved. */
    @Column(name = "resolved_product_id")
    private UUID resolvedProductId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** What produced the resolution, e.g. {@code catalog:EAN}. Null while unresolved. */
    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    /** Snapshot that last wrote this row; joins to the receipt that says whether it completed. */
    @Column(name = "source_snapshot_id", nullable = false)
    private UUID sourceSnapshotId;

    @Column(name = "source_chunk_sequence", nullable = false)
    private int sourceChunkSequence;

    /** Vendor alias at fetch time, descriptive only. */
    @Column(name = "supplier_ref", length = 255)
    private String supplierRef;

    /** The vendor's own statement of when its snapshot was true. Null when the vendor stated none. */
    @Column(name = "snapshot_as_of")
    private Instant snapshotAsOf;

    @Enumerated(EnumType.STRING)
    @Column(name = "as_of_source", nullable = false, length = 10)
    private SupplierHintAsOfSource asOfSource;

    /** When we asked. Supersession is ordered on this — it is the one instant that always exists. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** First time any snapshot mentioned this article; never overwritten by a later snapshot. */
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The instant a caller should judge this hint's age against: the vendor's figure when it stated
     * one, otherwise our fetch clock, which bounds the age from below without establishing it.
     * {@link #getAsOfSource()} says which is in play, so the substitution is never silent.
     */
    public Instant effectiveAsOf() {
        return snapshotAsOf != null ? snapshotAsOf : fetchedAt;
    }
}
