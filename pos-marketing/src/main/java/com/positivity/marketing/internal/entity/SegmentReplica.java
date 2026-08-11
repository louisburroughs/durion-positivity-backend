package com.positivity.marketing.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only replica of an audience segment's <em>definition</em>, fed exclusively by
 * {@code customer.events.v1} (ADR-0044 R3, Story #1137).
 *
 * <p>Metadata only — deliberately never membership. A dynamic segment's membership is derived
 * from party data and changes with no event to hang a replication off, so it is obtained on
 * request via the {@code CUSTOMER_SEGMENT_RESOLVE_REQUESTED} command instead.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_segment")
public class SegmentReplica {

    @Id
    @Column(name = "segment_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID segmentId;

    @Column(name = "name", length = 150)
    private String name;

    @Column(name = "audience_type", length = 20)
    private String audienceType;

    @Column(name = "type", length = 20)
    private String type;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Explicit dependency hooks for the ArchUnit UUIDv7 rules; the id is the owner's key. */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
