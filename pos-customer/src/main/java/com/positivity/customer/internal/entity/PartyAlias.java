package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

import com.positivity.shared.id.UUIDv7Id;
/**
 * Party alias entity for ID redirection after merge operations.
 * Ensures that all historical references to merged parties remain resolvable.
 * <p>
 * When a party is merged, a PartyAlias record is created mapping the source
 * party ID
 * to the survivor party ID. All lookups for the source ID should transparently
 * redirect
 * to the survivor.
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/109">Backend
 *      Issue #109</a>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "party_alias")
@EntityListeners(AuditingEntityListener.class)
@Schema(description = "Alias mapping for merged party IDs to ensure ID resolvability")
public class PartyAlias {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "source_party_id", updatable = false, nullable = false)
    @Schema(description = "ID of the party that was merged (source)")
    private UUID sourcePartyId;

    @NotNull
    @Column(name = "target_party_id", nullable = false)
    @Schema(description = "ID of the party to redirect to (survivor)")
    private UUID targetPartyId;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    @Schema(description = "Timestamp when the alias was created")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Timestamp when the alias was last updated")
    private Instant updatedAt;

    /**
     * Creates a party alias for ID redirection.
     *
     * @param sourcePartyId the ID of the merged party
     * @param targetPartyId the ID of the surviving party
     * @return a new PartyAlias instance
     */
    public static PartyAlias create(UUID sourcePartyId, UUID targetPartyId) {
        PartyAlias alias = new PartyAlias();
        alias.setSourcePartyId(sourcePartyId);
        alias.setTargetPartyId(targetPartyId);
        return alias;
    }

    /**
     * Explicit dependency hook for ArchUnit UUIDv7 rule.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return com.positivity.shared.id.UUIDv7Generator.class;
    }
}
