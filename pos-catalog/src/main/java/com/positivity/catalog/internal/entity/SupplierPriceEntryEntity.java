package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * One vendor price for one product in one market, as at one effective date (ADR-0053 §1–§2).
 *
 * <p>This is the business fact pos-catalog owns, applied from {@code supplier.pricecatalog.updated}
 * events. It replaces {@code supplier_item_cost}, which held one mutable row per
 * {@code (supplierId, itemId)} with no effective dates, no source identity and no market scope — so
 * a vendor price change destroyed the previous one and nothing could say where a cost came from.
 *
 * <h2>Append-only</h2>
 *
 * Rows are immutable. A new vendor price appends a new row; nothing here is ever updated in place,
 * which is what makes cost history a first-class query rather than an audit reconstruction.
 * Effective windows are half-open {@code [effectiveFrom, nextEffectiveFrom)} per
 * {@code (vendorProfileId, productId, countryCode, currency)}.
 *
 * <h2>Not a sell price</h2>
 *
 * Nothing readable by a sell-price resolver may consult this table (ADR-0053 §4, ADR-0054). The
 * suggested retail price is vendor reference data, displayable when labelled with its PRICAT
 * source, and never an applied price.
 *
 * <h2>Supplier code is an alias</h2>
 *
 * {@code supplierArticleCode} exists for operator display and vendor correspondence. It is never an
 * identifier, never a SKU, and never a match key (ADR-0053 §5) — matching happened upstream and the
 * result is {@code productId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_price_entry",
        indexes = {
            @Index(
                    name = "idx_supplier_price_entry_latest",
                    columnList = "product_id, vendor_profile_id, country_code, currency, effective_from"),
            @Index(name = "idx_supplier_price_entry_import", columnList = "import_manifest_id"),
            @Index(name = "idx_supplier_price_entry_product", columnList = "product_id")
        })
public class SupplierPriceEntryEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Vendor profile identity from pos-supplier (ADR-0050 §1); the key for every query. */
    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** Profile alias as at the import; descriptive snapshot only, never a join or filter key. */
    @Column(name = "supplier_ref", nullable = false, updatable = false, length = 100)
    private String supplierRef;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /** Which identifier matched upstream: {@code EAN} or {@code UPC_XREFERENCE}. */
    @Column(name = "match_method", nullable = false, updatable = false, length = 32)
    private String matchMethod;

    @Column(name = "article_ean", updatable = false, length = 64)
    private String articleEan;

    /** Vendor's own article code — display alias only (ADR-0053 §5). */
    @Column(name = "supplier_article_code", updatable = false, length = 64)
    private String supplierArticleCode;

    @Column(name = "x_reference_code", updatable = false, length = 64)
    private String xReferenceCode;

    // ── Market scope (ADR-0053 §3): buyer account and market, never a location ──────────────

    @Column(name = "buyer_account_number", nullable = false, updatable = false, length = 64)
    private String buyerAccountNumber;

    @Column(name = "country_code", nullable = false, updatable = false, length = 8)
    private String countryCode;

    @Column(name = "currency", nullable = false, updatable = false, length = 8)
    private String currency;

    // ── Values, verbatim from the vendor. Null means "not stated", never zero ───────────────

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

    /** Inclusive first day the values apply; a vendor-local calendar date (ADR-0053 §2). */
    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    // ── Source identity: which document, which import, when fetched ─────────────────────────

    @Column(name = "source_document_id", updatable = false, length = 255)
    private String sourceDocumentId;

    @Column(name = "source_document_date", updatable = false)
    private LocalDate sourceDocumentDate;

    @Column(name = "import_manifest_id", nullable = false, updatable = false)
    private UUID importManifestId;

    /** Last tie-break for "latest" when effective and document dates are equal (ADR-0053 §2). */
    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
