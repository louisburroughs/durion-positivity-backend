package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One staged PRICAT line: the received-document record pos-supplier owns (ADR-0053 §1).
 *
 * <p>This is <strong>not</strong> the business price fact — that is the append-only supplier price
 * entry in pos-catalog, applied from the events these rows produce. This table exists so that an
 * import can be audited, re-published, and re-applied after a late catalog fix without asking the
 * vendor for the document again.
 *
 * <p>Rows are immutable and append-only: a new fetch appends a new import's worth of rows and
 * supersedes nothing. "Latest" is a query over {@code effectiveFrom} (ADR-0053 §2), never a
 * mutation, so cost history survives.
 *
 * <p>{@code supplierArticleCode} is stored as an alias for operator display only. It is never an
 * identifier and never participates in matching — ADR-0053 §5 is explicit that a supplier code is
 * not a SKU.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_pricat_entry",
        indexes = {
            @Index(name = "idx_spricat_entry_import", columnList = "import_manifest_id"),
            @Index(
                    name = "idx_spricat_entry_latest",
                    columnList = "vendor_profile_id, matched_product_id, country_code, currency, effective_from"),
            @Index(name = "idx_spricat_entry_ean", columnList = "vendor_profile_id, article_ean")
        })
public class PriceCatalogEntryEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "entry_id", updatable = false, nullable = false)
    private UUID entryId;

    @Column(name = "import_manifest_id", nullable = false, updatable = false)
    private UUID importManifestId;

    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** 1-based line position within the vendor document, so a row can be cited back to the vendor. */
    @Column(name = "position_number", updatable = false)
    private Integer positionNumber;

    /**
     * 1-based chunk event this line was published in, fixed when the import was staged.
     *
     * <p>Stored rather than recomputed because a re-emit (ADR-0044 §4) must reproduce the original
     * chunk boundaries exactly: the consumer deduplicates re-emitted chunks on
     * {@code (importManifestId, chunkSequence)}, so a boundary that moved would make it skip a
     * sequence it already applied and lose the lines the re-emit was sent to deliver.
     */
    @Column(name = "chunk_sequence", nullable = false, updatable = false)
    private int chunkSequence;

    // ── Product identity as the vendor stated it ────────────────────────────────────────────

    @Column(name = "article_ean", updatable = false, length = 64)
    private String articleEan;

    /** Vendor's own article code — an alias for display, never an identifier (ADR-0053 §5). */
    @Column(name = "supplier_article_code", updatable = false, length = 64)
    private String supplierArticleCode;

    /** UPC/EAN cross-reference the vendor supplied; the second deterministic match key. */
    @Column(name = "x_reference_code", updatable = false, length = 64)
    private String xReferenceCode;

    @Column(name = "matched_product_id", updatable = false)
    private UUID matchedProductId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", nullable = false, updatable = false, length = 32)
    private PriceCatalogMatchMethod matchMethod;

    // ── Values, verbatim from the vendor ────────────────────────────────────────────────────

    @Column(name = "suggested_retail_price", updatable = false, precision = 19, scale = 4)
    private BigDecimal suggestedRetailPrice;

    @Column(name = "gross_price", updatable = false, precision = 19, scale = 4)
    private BigDecimal grossPrice;

    @Column(name = "net_price", updatable = false, precision = 19, scale = 4)
    private BigDecimal netPrice;

    @Column(name = "tax_rate", updatable = false, precision = 9, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "recycling_fee", updatable = false, precision = 19, scale = 4)
    private BigDecimal recyclingFee;

    /** Inclusive first day the row applies; a vendor-local date (ADR-0053 §2). */
    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    // ── Market scope (ADR-0053 §3): buyer account and market, never a location ──────────────

    @Column(name = "buyer_account_number", nullable = false, updatable = false, length = 64)
    private String buyerAccountNumber;

    @Column(name = "country_code", nullable = false, updatable = false, length = 8)
    private String countryCode;

    @Column(name = "currency", nullable = false, updatable = false, length = 8)
    private String currency;

    // ── Source identity ─────────────────────────────────────────────────────────────────────

    @Column(name = "source_document_id", updatable = false, length = 255)
    private String sourceDocumentId;

    @Column(name = "source_document_date", updatable = false)
    private LocalDate sourceDocumentDate;

    /** When this import fetched the row; the last tie-break for "latest" (ADR-0053 §2). */
    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
