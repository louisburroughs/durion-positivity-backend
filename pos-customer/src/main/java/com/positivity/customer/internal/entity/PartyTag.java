package com.positivity.customer.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * A flat, CSR-managed classification label that can be attached to any party
 * (Story #1136).
 *
 * <p>Tags are the lightest segmentation primitive in the CRM: unlike
 * {@code AccountTier} (derived from spend rules) a tag carries no computed
 * semantics, so marketers can coin one without a schema change. The taxonomy is
 * deliberately flat for v1 — {@code category} groups tags for display only and
 * imposes no hierarchy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "party_tag",
        indexes = {@Index(name = "idx_party_tag_category", columnList = "category")})
public class PartyTag {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "tag_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID tagId;

    /**
     * Uniqueness is enforced case-insensitively by the {@code uq_party_tag_name}
     * functional index in V17, which a column-level {@code unique} cannot express.
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "category", length = 100)
    private String category;

    /** Presentation hint for CRM/marketing UIs, e.g. {@code #1f7a4d}. */
    @Column(name = "color", length = 20)
    private String color;

    /**
     * Retired tags stay assignable-for-read so existing assignments and audit
     * trails keep resolving; only new assignments are refused.
     */
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
