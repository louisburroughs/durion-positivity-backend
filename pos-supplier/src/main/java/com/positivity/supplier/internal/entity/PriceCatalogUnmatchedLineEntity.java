package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
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
 * A PRICAT line that could not be attached to a catalog product: the unmatched-lines quarantine
 * (ADR-0053 §5).
 *
 * <p>This table is what makes "zero silent drops" a fact rather than an intention. Every line the
 * vendor sent is either a {@link PriceCatalogEntryEntity} or a row here with a reason, and the two
 * counts plus duplicates reconcile against the import's {@code linesFetched}.
 *
 * <p>Rows carry the line's own values, not just its identifiers, so that a later catalog fix can be
 * re-applied straight from here — ADR-0053 §5 requires re-application without a vendor re-fetch.
 * {@link #resolvedAt} and {@link #resolvedProductId} are the only mutable fields: a quarantined
 * line is closed by being matched, never by being edited.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_pricat_unmatched_line",
        indexes = {
            @Index(name = "idx_spricat_unmatched_import", columnList = "import_manifest_id"),
            @Index(name = "idx_spricat_unmatched_open", columnList = "vendor_profile_id, resolved_at"),
            @Index(name = "idx_spricat_unmatched_reason", columnList = "reason")
        })
public class PriceCatalogUnmatchedLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "unmatched_line_id", updatable = false, nullable = false)
    private UUID unmatchedLineId;

    @Column(name = "import_manifest_id", nullable = false, updatable = false)
    private UUID importManifestId;

    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    @Column(name = "position_number", updatable = false)
    private Integer positionNumber;

    @Column(name = "article_ean", updatable = false, length = 64)
    private String articleEan;

    @Column(name = "supplier_article_code", updatable = false, length = 64)
    private String supplierArticleCode;

    @Column(name = "x_reference_code", updatable = false, length = 64)
    private String xReferenceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 48)
    private UnmatchedLineReason reason;

    /** Operator-facing detail for the reason; never a credential and never a whole payload. */
    @Column(name = "reason_detail", updatable = false, length = 1000)
    private String reasonDetail;

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

    @Column(name = "effective_from", updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "buyer_account_number", nullable = false, updatable = false, length = 64)
    private String buyerAccountNumber;

    @Column(name = "country_code", updatable = false, length = 8)
    private String countryCode;

    @Column(name = "currency", updatable = false, length = 8)
    private String currency;

    @Column(name = "source_document_id", updatable = false, length = 255)
    private String sourceDocumentId;

    @Column(name = "source_document_date", updatable = false)
    private LocalDate sourceDocumentDate;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;

    /** Set when a later import or re-application matched the line; null while quarantined. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_product_id")
    private UUID resolvedProductId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
