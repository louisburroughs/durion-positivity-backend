package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One article's reported availability within a stock snapshot (CAP-322 #1228).
 *
 * <p>{@code availableQuantity} is nullable and the nullability is the point: null means the vendor
 * reported the article without stating a quantity, zero means it explicitly reported none. The
 * column is therefore deliberately NOT declared NOT NULL with a zero default — a default would
 * silently convert "the vendor said nothing" into "the vendor said none", which is the single
 * mistake this feed most needs to avoid.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_stock_snapshot_line",
        indexes = {
            @Index(name = "idx_sstock_line_snapshot", columnList = "snapshot_id"),
            @Index(name = "idx_sstock_line_ean", columnList = "vendor_profile_id, article_ean")
        })
public class StockSnapshotLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "line_id", updatable = false, nullable = false)
    private UUID lineId;

    @Column(name = "snapshot_id", nullable = false, updatable = false)
    private UUID snapshotId;

    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** Vendor's own line id within the document, when stated. */
    @Column(name = "vendor_line_id", updatable = false, length = 64)
    private String vendorLineId;

    @Column(name = "article_ean", updatable = false, length = 64)
    private String articleEan;

    /** Vendor's own article code — an alias for display, never an identifier. */
    @Column(name = "supplier_article_code", updatable = false, length = 64)
    private String supplierArticleCode;

    /** The buyer's own code as the vendor holds it, when stated. */
    @Column(name = "buyers_article_id", updatable = false, length = 64)
    private String buyersArticleId;

    @Column(name = "description", updatable = false, length = 512)
    private String description;

    /** Null means the vendor stated no quantity; zero means it reported none. */
    @Column(name = "available_quantity", updatable = false)
    private Integer availableQuantity;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
