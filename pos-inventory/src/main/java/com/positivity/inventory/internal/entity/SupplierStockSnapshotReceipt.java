package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
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
 * How much of one supplier stock snapshot actually arrived (CAP-322, #1312).
 *
 * <p>{@code supplier.stockreport.updated} has no terminal event and no republish command — unlike
 * the PRICAT feed, which carries both — and an empty, rejected or failed snapshot publishes no
 * events at all. Completeness is therefore established in band: every chunk states its
 * {@code chunkSequence} and the snapshot's {@code chunkCount}, so counting what arrived against
 * what was promised is the whole mechanism.
 *
 * <p>Incomplete is the resting state, which is what makes a lost chunk safe to detect: nothing has
 * to fire on a timer for a gap to be visible, the receipt simply never reaches
 * {@link #isComplete()} and every hint sourced from that snapshot reads as coming from a partial
 * snapshot.
 */
@Entity
@Table(
        name = "supplier_stock_snapshot_receipt",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_supplier_stock_snapshot_receipt_snapshot",
                        columnNames = {"snapshot_id"}),
        indexes =
                @Index(
                        name = "idx_supplier_stock_snapshot_receipt_vendor",
                        columnList = "vendor_profile_id, fetched_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class SupplierStockSnapshotReceipt {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID receiptId;

    /** The producer's snapshot aggregate id, which is also the Kafka key of every chunk. */
    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    @Column(name = "supplier_ref", length = 255)
    private String supplierRef;

    @Column(name = "document_id", length = 255)
    private String documentId;

    @Column(name = "buyer_account_number", length = 64)
    private String buyerAccountNumber;

    /** Total chunks the producer says this snapshot was split into. */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** Distinct chunk sequences applied so far. Equal to chunkCount exactly when complete. */
    @Column(name = "chunks_applied", nullable = false)
    private int chunksApplied;

    /**
     * Producer-stated line total across all chunks, counting decoded lines only — lines the codec
     * rejected are excluded upstream. A cross-check on completeness, not an authoritative total.
     */
    @Column(name = "lines_reported", nullable = false)
    private int linesReported;

    /** Lines we actually applied from the chunks that arrived. */
    @Column(name = "lines_applied", nullable = false)
    private int linesApplied;

    @Column(name = "snapshot_as_of")
    private Instant snapshotAsOf;

    @Enumerated(EnumType.STRING)
    @Column(name = "as_of_source", nullable = false, length = 10)
    private SupplierHintAsOfSource asOfSource;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** True once every promised chunk has been applied. */
    @Column(name = "complete", nullable = false)
    private boolean complete;

    @Column(name = "first_chunk_at", nullable = false)
    private Instant firstChunkAt;

    @Column(name = "last_chunk_at", nullable = false)
    private Instant lastChunkAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
