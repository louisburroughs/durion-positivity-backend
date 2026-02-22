package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.positivity.customer.internal.enums.PartyRelationshipRole;
import com.positivity.shared.id.UUIDv7Generator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Party relationship entity representing the association between a commercial
 * account (Party)
 * and an individual person-party with specific roles and effective dates.
 * <p>
 * Key business rules:
 * - Exactly one primary billing contact per commercial account at a time
 * - Assigning a new primary billing contact atomically demotes any existing
 * primary
 * - Effective dates determine active/inactive status; null end date means
 * active
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/110">Backend
 *      Issue #110</a>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "party_relationship", indexes = {
        @Index(name = "idx_party_rel_from_party", columnList = "from_party_id"),
        @Index(name = "idx_party_rel_to_person", columnList = "to_person_id"),
        @Index(name = "idx_party_rel_primary_billing", columnList = "from_party_id, is_primary_billing_contact")
})
@Schema(description = "Relationship between a commercial account and an individual with roles")
public class PartyRelationship {

    @Id
    @Column(name = "party_relationship_id", columnDefinition = "UUID", updatable = false, nullable = false)
    @Schema(description = "Unique identifier for the party relationship")
    private UUID partyRelationshipId;

    @PrePersist
    public void generateId() {
        if (partyRelationshipId == null) {
            partyRelationshipId = UUIDv7Generator.generate();
        }
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_party_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Schema(description = "The commercial account (organization) in this relationship")
    private CommercialParty fromParty;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_person_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Schema(description = "The individual person in this relationship")
    private PersonParty toPerson;

    @ElementCollection(targetClass = PartyRelationshipRole.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "party_relationship_role", joinColumns = @JoinColumn(name = "party_relationship_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false)
    @Schema(description = "Roles assigned to this relationship")
    private Set<PartyRelationshipRole> roles = new HashSet<>();

    @Column(name = "is_primary_billing_contact", nullable = false)
    @Schema(description = "Whether this contact is the primary billing contact for the account")
    private boolean isPrimaryBillingContact = false;

    @Column(name = "effective_start_date", nullable = false)
    @Schema(description = "Date when this relationship becomes effective", example = "2026-01-01")
    private LocalDate effectiveStartDate;

    @Column(name = "effective_end_date")
    @Schema(description = "Date when this relationship ends (null means currently active)", example = "2026-12-31")
    private LocalDate effectiveEndDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    @Schema(description = "Timestamp when the relationship was created")
    private Instant createdAt;

    @Column(name = "created_by")
    @Schema(description = "User ID who created this relationship")
    private UUID createdBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Timestamp when the relationship was last updated")
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Schema(description = "User ID who last updated this relationship")
    private UUID updatedBy;

    /**
     * Checks if this relationship is currently active.
     *
     * @return true if the relationship is active (no end date or end date is in the
     *         future)
     */
    @Transient
    public boolean isActive() {
        if (effectiveEndDate == null) {
            return true;
        }
        return effectiveEndDate.isAfter(LocalDate.now()) || effectiveEndDate.isEqual(LocalDate.now());
    }

    /**
     * Deactivates this relationship by setting the effective end date to today.
     */
    public void deactivate() {
        this.effectiveEndDate = LocalDate.now();
    }

    /**
     * Checks if this relationship has a specific role.
     *
     * @param role the role to check
     * @return true if the relationship has the specified role
     */
    public boolean hasRole(PartyRelationshipRole role) {
        return roles != null && roles.contains(role);
    }
}
