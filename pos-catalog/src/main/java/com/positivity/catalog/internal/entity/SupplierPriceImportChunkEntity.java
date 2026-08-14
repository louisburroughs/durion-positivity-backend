package com.positivity.catalog.internal.entity;

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
 * Record that one chunk of one PRICAT import has been applied (ADR-0053 §7).
 *
 * <p>Event-id deduplication alone is not enough here. A re-emit requested through
 * {@code supplier.pricecatalog.republish.requested} re-delivers the same chunks as <em>new</em>
 * events with new ids, so an eventId guard would let the producer's recovery write a second copy of
 * every line in the chunk — duplicate {@code supplier_price_entry} rows with identical effective
 * dates and document identity, which the latest-selection tie-break cannot separate, plus an
 * inflated chunk count that could mark a still-incomplete import complete.
 *
 * <p>The natural identity of a chunk is therefore {@code (importManifestId, chunkSequence)}, and
 * that pair is unique here. The row carries its own UUIDv7 id so the platform's identifier rules
 * hold; the uniqueness constraint is what actually guards the apply.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_price_import_chunk",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_supplier_price_import_chunk",
                        columnNames = {"import_manifest_id", "chunk_sequence"}))
public class SupplierPriceImportChunkEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "import_manifest_id", nullable = false, updatable = false)
    private UUID importManifestId;

    /** 1-based sequence the producer stamped on the chunk event. */
    @Column(name = "chunk_sequence", nullable = false, updatable = false)
    private int chunkSequence;

    /** How many price rows this chunk wrote, so a re-delivery cannot inflate the total. */
    @Column(name = "lines_applied", nullable = false, updatable = false)
    private int linesApplied;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;
}
