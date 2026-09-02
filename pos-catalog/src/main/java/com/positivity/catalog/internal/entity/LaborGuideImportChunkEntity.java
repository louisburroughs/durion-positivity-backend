package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * One applied feed chunk (#1569, sourcing plan §5.3). The (manifest, sequence) uniqueness is
 * the idempotency guard: a re-delivered or resumed chunk that already has a row is a no-op,
 * mirroring the supplier-price listener's two-guard scheme.
 */
@Data
@Entity
@Table(name = "labor_guide_import_chunk")
public class LaborGuideImportChunkEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "import_manifest_id", nullable = false)
    private UUID importManifestId;

    @Column(name = "chunk_sequence", nullable = false)
    private int chunkSequence;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;
}
