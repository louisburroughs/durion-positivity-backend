package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Consumer-side bookkeeping for one PRICAT import: how much of it this module has applied, and
 * whether the producer's completion event agrees (ADR-0053 §7, ADR-0044 §4).
 *
 * <p>Chunks and the completion event arrive independently and at-least-once, so "have I applied all
 * of it?" cannot be answered from the price rows alone — a missing chunk looks exactly like a
 * vendor that sent fewer lines. This row is the answer: chunks applied are counted as they land,
 * the completion event supplies the expected {@code chunkCount} and checksum, and the comparison
 * decides whether the import is {@link #status} {@code COMPLETE} or {@code INCOMPLETE}.
 *
 * <p>An {@code INCOMPLETE} import is a request for the owner to re-emit, published on the command
 * topic (ADR-0044 §4) — never a synchronous call back to pos-supplier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_price_import",
        indexes = {
            @Index(name = "idx_supplier_price_import_profile", columnList = "vendor_profile_id"),
            @Index(name = "idx_supplier_price_import_status", columnList = "status")
        })
public class SupplierPriceImportEntity {

    /** Applying chunks in flight; no completion event seen yet. */
    public static final String STATUS_APPLYING = "APPLYING";

    /** Completion event seen and every chunk it named has been applied. */
    public static final String STATUS_COMPLETE = "COMPLETE";

    /** Completion event seen but chunks are missing; a re-emit has been requested. */
    public static final String STATUS_INCOMPLETE = "INCOMPLETE";

    @Id
    @AssignedIdentifier("the producer's import manifest id; this row tracks that import, so a"
            + " locally minted id would break the only join that matters")
    @Column(name = "import_manifest_id", nullable = false)
    private UUID importManifestId;

    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    @Column(name = "supplier_ref", length = 100)
    private String supplierRef;

    /** Chunks applied so far. Counted per applied chunk event, deduped by the event log. */
    @Column(name = "chunks_applied", nullable = false)
    @Builder.Default
    private int chunksApplied = 0;

    /** Price rows written so far across those chunks. */
    @Column(name = "lines_applied", nullable = false)
    @Builder.Default
    private int linesApplied = 0;

    /** Expected chunk total; null until the completion event arrives. */
    @Column(name = "expected_chunk_count")
    private Integer expectedChunkCount;

    /** Matched-line total the producer reported; null until completion. */
    @Column(name = "expected_line_count")
    private Integer expectedLineCount;

    /** Producer's content checksum over the published lines; null until completion. */
    @Column(name = "content_checksum", length = 64)
    private String contentChecksum;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the module's ArchUnit UUIDv7 rule, matching the idiom
     * {@code ProcessedEvent} uses: the key IS a UUIDv7, minted by pos-supplier when it opened the
     * import. Generating one here would give a single import two identities and break the only join
     * that matters.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
