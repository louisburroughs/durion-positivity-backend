package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

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
@Table(name = "merge_audit", indexes = {
        @Index(name = "idx_merge_audit_survivor", columnList = "survivor_party_id"),
        @Index(name = "idx_merge_audit_source", columnList = "source_party_id"),
        @Index(name = "idx_merge_audit_merged_at", columnList = "merged_at")
})
@Schema(description = "Audit record for party merge operations")
public class MergeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "merge_audit_id", updatable = false, nullable = false)
    @Schema(description = "Unique identifier for the merge audit record")
    private UUID mergeAuditId;

    @NotNull
    @Column(name = "survivor_party_id", nullable = false)
    @Schema(description = "ID of the party that survived the merge (kept)")
    private Long survivorPartyId;

    @NotNull
    @Column(name = "source_party_id", nullable = false)
    @Schema(description = "ID of the party that was merged (absorbed)")
    private Long sourcePartyId;

    @NotNull
    @Column(name = "merged_by_user_id", nullable = false)
    @Schema(description = "ID of the user who performed the merge")
    private UUID mergedByUserId;

    @NotBlank
    @Column(name = "merge_reason", nullable = false, length = 1000)
    @Schema(description = "Reason/justification for the merge", example = "Duplicate customer records identified")
    private String mergeReason;

    @CreationTimestamp
    @Column(name = "merged_at", updatable = false, nullable = false)
    @Schema(description = "Timestamp when the merge was performed")
    private Instant mergedAt;

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
    public static MergeAudit create(Long survivorPartyId, Long sourcePartyId, UUID mergedByUserId, String mergeReason) {
        MergeAudit audit = new MergeAudit();
        audit.setSurvivorPartyId(survivorPartyId);
        audit.setSourcePartyId(sourcePartyId);
        audit.setMergedByUserId(mergedByUserId);
        audit.setMergeReason(mergeReason);
        return audit;
    }
}
