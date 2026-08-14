package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One applied chunk of a supplier stock snapshot (CAP-322, #1312).
 *
 * <p>The {@code processed_events} guard dedupes a redelivery of the same event; it cannot dedupe a
 * re-emit of the same chunk under a fresh {@code eventId}, which is what this log is for — the same
 * second guard the PRICAT consumer keeps in {@code supplier_price_import_chunk}. It also makes a
 * gap enumerable rather than merely countable: the sequences that arrived are on record, so an
 * operator can say which chunk is missing and not only how many are.
 */
@Entity
@Table(
        name = "supplier_stock_snapshot_chunk",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_supplier_stock_snapshot_chunk_seq",
                        columnNames = {"snapshot_id", "chunk_sequence"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierStockSnapshotChunk {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID chunkId;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    /** 1-based, as the producer emits it. */
    @Column(name = "chunk_sequence", nullable = false)
    private int chunkSequence;

    @Column(name = "lines_in_chunk", nullable = false)
    private int linesInChunk;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;
}
