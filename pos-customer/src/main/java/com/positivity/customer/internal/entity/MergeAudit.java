package com.positivity.customer.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Audit record for party merge operations.
 * Provides an immutable log of all merge operations for compliance and
 * traceability.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/109">Backend
 *      Issue #109</a>
 */
@Data
@NoArgsConstructor
@Entity
@Table(
        name = "merge_audit",
        indexes = {
            @Index(name = "idx_merge_audit_survivor", columnList = "survivor_party_id"),
            @Index(name = "idx_merge_audit_source", columnList = "source_party_id"),
            @Index(name = "idx_merge_audit_merged_at", columnList = "merged_at")
        })
@EntityListeners(AuditingEntityListener.class)
@Schema(description = "Audit record for party merge operations")
public class MergeAudit {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "merge_audit_id", columnDefinition = "UUID", updatable = false, nullable = false)
    @Schema(description = "Unique identifier for the merge audit record")
    private UUID mergeAuditId;

    @NotNull
    @Column(name = "survivor_party_id", nullable = false)
    @Schema(description = "ID of the party that survived the merge (kept)")
    private UUID survivorPartyId;

    @NotNull
    @Column(name = "source_party_id", nullable = false)
    @Schema(description = "ID of the party that was merged (absorbed)")
    private UUID sourcePartyId;

    @NotNull
    @Column(name = "merged_by_user_id", nullable = false)
    @Schema(description = "ID of the user who performed the merge")
    private UUID mergedByUserId;

    @NotBlank
    @Column(name = "merge_reason", nullable = false, length = 1000)
    @Schema(description = "Reason/justification for the merge", example = "Duplicate customer records identified")
    private String mergeReason;

    @CreatedDate
    @Column(name = "merged_at", updatable = false, nullable = false)
    @Schema(description = "Timestamp when the merge audit record was created")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Timestamp when the merge audit record was last updated")
    private Instant updatedAt;

    @Column(name = "contacts_transferred")
    @Schema(description = "Number of contacts transferred from source to survivor")
    private Integer contactsTransferred;

    @Column(name = "vehicles_transferred")
    @Schema(description = "Number of vehicles transferred from source to survivor")
    private Integer vehiclesTransferred;

    @Column(name = "external_ids_merged")
    @Schema(description = "Number of external identifiers merged")
    private Integer externalIdsMerged;

    /**
     * Creates a merge audit record.
     *
     * @param survivorPartyId the ID of the surviving party
     * @param sourcePartyId   the ID of the source party being merged
     * @param mergedByUserId  the ID of the user performing the merge
     * @param mergeReason     the reason for the merge
     * @return a new MergeAudit instance
     */
    public static MergeAudit create(UUID survivorPartyId, UUID sourcePartyId, UUID mergedByUserId, String mergeReason) {
        MergeAudit audit = new MergeAudit();
        audit.setSurvivorPartyId(survivorPartyId);
        audit.setSourcePartyId(sourcePartyId);
        audit.setMergedByUserId(mergedByUserId);
        audit.setMergeReason(mergeReason);
        return audit;
    }

    @Transient
    public Instant getMergedAt() {
        return createdAt;
    }
}
