package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Bookkeeping for one labor-guide feed import (#1569, sourcing plan §5.3; ADR-0053 shape).
 * The primary key is the provider-assigned manifest id — no {@code @GeneratedValue} — which is
 * what makes a re-run of the same revision a recognizable resume/no-op instead of a duplicate.
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "labor_guide_import")
public class LaborGuideImportEntity {

    /** Import completeness — a counted fact, never an assumption. */
    public enum Status {
        APPLYING,
        COMPLETE,
        INCOMPLETE
    }

    @Id
    @AssignedIdentifier("the provider's import manifest id; this row tracks that import, so a"
            + " locally minted id would break the resume/no-op recognition that makes re-running"
            + " a revision safe")
    @Column(name = "import_manifest_id", columnDefinition = "UUID")
    private UUID importManifestId;

    @Column(name = "source_code", nullable = false)
    private String sourceCode;

    @Column(name = "source_revision", nullable = false)
    private String sourceRevision;

    @Column(name = "expected_chunk_count", nullable = false)
    private int expectedChunkCount;

    @Column(name = "expected_line_count", nullable = false)
    private long expectedLineCount;

    @Column(name = "content_checksum", nullable = false)
    private String contentChecksum;

    @Column(name = "chunks_applied", nullable = false)
    private int chunksApplied;

    @Column(name = "lines_applied", nullable = false)
    private long linesApplied;

    @Column(name = "lines_unmapped", nullable = false)
    private long linesUnmapped;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the module's ArchUnit UUIDv7 rule, matching the
     * {@code SupplierPriceImportEntity} idiom: the key IS a UUIDv7, minted by the provider when
     * it opened the feed revision; generating one here would give a single import two
     * identities.
     */
    @jakarta.persistence.Transient
    public Class<?> uuidv7Dependency() {
        return com.positivity.shared.id.UUIDv7Id.class;
    }
}
