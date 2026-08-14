package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One PRICAT fetch, and the aggregate of the events it publishes (ADR-0053 §7): the import
 * manifest. Its id is the Kafka record key of every chunk event and of the completion event, so a
 * consumer can order and complete a single import without inspecting its contents.
 *
 * <p>Like the exchange audit, this table carries <strong>no foreign key</strong> to
 * {@code supplier_profile}: ADR-0050 §6/§7 require the received-document record to outlive the
 * configuration that produced it, and admin {@code deleteProfile} is a hard delete. The profile
 * alias is snapshotted for readability and is never a query key — {@link #vendorProfileId} is.
 *
 * <p>The counters are the completeness contract. {@code linesFetched} is what the vendor sent;
 * the other three partition it, so {@code matched + unmatched + duplicate == fetched} holds for
 * every completed import and is asserted by the service that writes them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_pricat_import",
        indexes = {
            @Index(name = "idx_spricat_import_profile_fetched", columnList = "vendor_profile_id, fetched_at"),
            @Index(name = "idx_spricat_import_status", columnList = "status"),
            @Index(name = "idx_spricat_import_correlation", columnList = "correlation_id")
        })
public class PriceCatalogImportEntity {

    /** Import-manifest identity (UUIDv7); the event aggregate id and Kafka record key. */
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "import_manifest_id", updatable = false, nullable = false)
    private UUID importManifestId;

    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** Profile alias as at the time of the fetch; descriptive snapshot, never a query key. */
    @Column(name = "supplier_ref", nullable = false, updatable = false, length = 100)
    private String supplierRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_family", nullable = false, updatable = false, length = 64)
    private ProtocolFamily protocolFamily;

    /** Norm version key; data, not an enum (ADR-0051 §3). */
    @Column(name = "protocol_version", nullable = false, updatable = false, length = 64)
    private String protocolVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PriceCatalogImportStatus status;

    /** Vendor's own document id from the PRICAT envelope header; null when the vendor sent none. */
    @Column(name = "source_document_id", length = 255)
    private String sourceDocumentId;

    /** Vendor document date; a vendor-local date, never a converted instant (ADR-0053 §2). */
    @Column(name = "source_document_date")
    private LocalDate sourceDocumentDate;

    /** Market scope of the fetch (ADR-0053 §3): buyer account, country, currency. */
    @Column(name = "buyer_account_number", nullable = false, length = 64)
    private String buyerAccountNumber;

    @Column(name = "buyer_agency_code", length = 16)
    private String buyerAgencyCode;

    @Column(name = "country_code", length = 8)
    private String countryCode;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "lines_fetched", nullable = false)
    @Builder.Default
    private int linesFetched = 0;

    @Column(name = "lines_matched", nullable = false)
    @Builder.Default
    private int linesMatched = 0;

    @Column(name = "lines_unmatched", nullable = false)
    @Builder.Default
    private int linesUnmatched = 0;

    @Column(name = "lines_duplicate", nullable = false)
    @Builder.Default
    private int linesDuplicate = 0;

    /** Number of chunk events published for this import; 0 until staging commits. */
    @Column(name = "chunk_count", nullable = false)
    @Builder.Default
    private int chunkCount = 0;

    @Column(name = "chunk_size", nullable = false)
    @Builder.Default
    private int chunkSize = 0;

    /**
     * Content checksum over the matched lines, carried on the completion event so a consumer can
     * prove it applied the same set the producer staged.
     */
    @Column(name = "content_checksum", length = 64)
    private String contentChecksum;

    /** Exchange-audit row of the fetch, so an import is traceable to the wire exchange. */
    @Column(name = "exchange_audit_id")
    private UUID exchangeAuditId;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    /**
     * Set when this manifest re-applied quarantined lines of an earlier import (#1310); null for an
     * ordinary vendor fetch.
     *
     * <p>A re-application gets its own manifest rather than editing the original's counters,
     * because those counters record what the vendor sent and how much of it matched <em>at the
     * time</em>. Rewriting them later would destroy that record and contradict the chunk sequencing
     * consumers already applied — chunk 4 of a 3-chunk import is not something a completeness check
     * can accept.
     */
    @Column(name = "reapplied_from_import_id", updatable = false)
    private UUID reappliedFromImportId;

    /** Operator-facing failure summary for a FAILED import; never a credential. */
    @Column(name = "failure_detail", length = 2000)
    private String failureDetail;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 100)
    private String createdBy;
}
