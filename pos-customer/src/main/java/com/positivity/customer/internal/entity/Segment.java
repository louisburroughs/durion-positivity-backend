package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.SegmentType;
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
 * A saved audience definition (Story #1137).
 *
 * <p>A {@code STATIC} segment carries an explicit member list; a {@code DYNAMIC} one carries a
 * validated predicate evaluated at resolve time. {@code audienceType} is part of the
 * definition rather than a filter applied later, because commercial and individual audiences
 * differ in who consents and what a message may say — a segment that mixes them cannot be
 * addressed correctly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "segment",
        indexes = {@Index(name = "idx_segment_audience_type", columnList = "audience_type")})
public class Segment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "segment_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID segmentId;

    /** Unique case-insensitively via the {@code uq_segment_name} functional index in V18. */
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", columnDefinition = "VARCHAR(20)", nullable = false)
    private AudienceType audienceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", columnDefinition = "VARCHAR(20)", nullable = false)
    private SegmentType type;

    /** Serialized {@code SegmentPredicate} tree; required for DYNAMIC, null for STATIC. */
    @Column(name = "predicate", columnDefinition = "text")
    private String predicate;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
