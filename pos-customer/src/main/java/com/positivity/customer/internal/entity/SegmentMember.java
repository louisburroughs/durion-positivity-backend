package com.positivity.customer.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Explicit membership row for a {@code STATIC} segment (Story #1137). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "segment_member",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_segment_member",
                        columnNames = {"segment_id", "party_id"}),
        indexes = {@Index(name = "idx_segment_member_segment", columnList = "segment_id")})
public class SegmentMember {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "segment_member_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID segmentMemberId;

    @Column(name = "segment_id", columnDefinition = "UUID", nullable = false)
    private UUID segmentId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    /** Specific contact to address within the party, when the marketer pinned one. */
    @Column(name = "contact_id", columnDefinition = "UUID")
    private UUID contactId;

    @Column(name = "added_by", length = 255)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;
}
