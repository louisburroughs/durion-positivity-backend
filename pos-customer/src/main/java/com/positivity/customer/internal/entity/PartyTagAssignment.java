package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.TagAssignmentSource;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Attachment of a {@link PartyTag} to a party (Story #1136).
 *
 * <p>The {@code (party_id, tag_id)} uniqueness constraint is what makes assignment
 * idempotent: re-assigning an existing tag is a no-op rather than a duplicate row,
 * which matters because campaign and import paths replay.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "party_tag_assignment",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_party_tag_assignment",
                        columnNames = {"party_id", "tag_id"}),
        indexes = {
            @Index(name = "idx_party_tag_assignment_party", columnList = "party_id"),
            @Index(name = "idx_party_tag_assignment_tag", columnList = "tag_id")
        })
public class PartyTagAssignment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "assignment_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID assignmentId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    @Column(name = "tag_id", columnDefinition = "UUID", nullable = false)
    private UUID tagId;

    @Column(name = "assigned_by", length = 255)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", columnDefinition = "VARCHAR(20)", nullable = false)
    @Builder.Default
    private TagAssignmentSource source = TagAssignmentSource.MANUAL;
}
