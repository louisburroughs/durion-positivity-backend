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
 * A vendor operation code the feed carried that maps to no Durion operation (#1569, sourcing
 * plan §5.3 step 4). Curation work, not an error: the import skips the line and keeps going,
 * and this queue is what a curator drains by adding {@code service_operation_xref} rows.
 */
@Data
@Entity
@Table(name = "labor_guide_unmapped_operation")
public class LaborGuideUnmappedOperationEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "source_code", nullable = false)
    private String sourceCode;

    @Column(name = "provider_op_code", nullable = false)
    private String providerOpCode;

    @Column(name = "last_manifest_id")
    private UUID lastManifestId;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
