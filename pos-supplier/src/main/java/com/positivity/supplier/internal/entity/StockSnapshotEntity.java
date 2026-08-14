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
 * One vendor stock-report snapshot fetch (CAP-322 #1228): what the vendor said it had, for a whole
 * market, at one moment.
 *
 * <p>Snapshots are append-only and never merged. A later snapshot does not correct an earlier one —
 * it describes a different moment, and both remain true of theirs. That is why "latest" is a query
 * over {@link #snapshotAsOf} rather than an update in place, and why a failed fetch leaves the
 * previous snapshot standing as the last thing the vendor actually said.
 *
 * <p>Like the exchange audit and the PRICAT import, no foreign key to {@code supplier_profile}:
 * ADR-0050 §6/§7 require the received-document record to outlive the configuration that produced it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_stock_snapshot",
        indexes = {
            @Index(name = "idx_sstock_snapshot_profile_asof", columnList = "vendor_profile_id, snapshot_as_of"),
            @Index(name = "idx_sstock_snapshot_status", columnList = "status"),
            @Index(name = "idx_sstock_snapshot_correlation", columnList = "correlation_id")
        })
public class StockSnapshotEntity {

    /** Snapshot identity (UUIDv7); the event aggregate id and Kafka record key. */
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "snapshot_id", updatable = false, nullable = false)
    private UUID snapshotId;

    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** Profile alias as at the fetch; descriptive snapshot only, never a query key. */
    @Column(name = "supplier_ref", nullable = false, updatable = false, length = 100)
    private String supplierRef;

    @Column(name = "protocol_version", nullable = false, updatable = false, length = 64)
    private String protocolVersion;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "document_id", length = 255)
    private String documentId;

    /** Vendor-stated issue date of the document. */
    @Column(name = "issued_on")
    private LocalDate issuedOn;

    /**
     * Vendor-stated snapshot instant. Distinct from {@link #fetchedAt} on purpose: staleness is
     * measured against what the vendor said, not against when we asked.
     */
    @Column(name = "snapshot_as_of")
    private Instant snapshotAsOf;

    @Column(name = "buyer_account_number", nullable = false, length = 64)
    private String buyerAccountNumber;

    @Column(name = "country_code", length = 8)
    private String countryCode;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "lines_reported", nullable = false)
    @Builder.Default
    private int linesReported = 0;

    @Column(name = "lines_rejected", nullable = false)
    @Builder.Default
    private int linesRejected = 0;

    @Column(name = "chunk_count", nullable = false)
    @Builder.Default
    private int chunkCount = 0;

    @Column(name = "chunk_size", nullable = false)
    @Builder.Default
    private int chunkSize = 0;

    @Column(name = "exchange_audit_id")
    private UUID exchangeAuditId;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    /** Operator-facing failure summary for a FAILED snapshot; never a credential. */
    @Column(name = "failure_detail", length = 2000)
    private String failureDetail;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 100)
    private String createdBy;
}
