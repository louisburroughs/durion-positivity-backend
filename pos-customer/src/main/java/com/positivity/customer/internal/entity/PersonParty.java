package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.shared.id.UUIDv7Id;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * PersonParty entity representing an individual in the CRM system.
 * Distinct from CommercialParty (which represents organizations).
 * <p>
 * This is a thin link to the canonical person in pos-people (ADR-0015 I2):
 * identity (name) and contact points live solely in pos-people and are read via
 * {@code personId}. pos-customer keeps only the link plus CRM-local state
 * (customer number, preferred contact method, status/tier, vehicles).
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/684">Backend
 *      Issue #684</a>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "person_party")
@EntityListeners(AuditingEntityListener.class)
@Schema(description = "Individual person party in the CRM system")
public class PersonParty extends AbstractParty {

    @Column(name = "person_id", nullable = false, unique = true, columnDefinition = "UUID")
    @Schema(description = "Canonical person ID from pos-people", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_contact_method", nullable = false, length = 20)
    @Schema(description = "Preferred method of contact", example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED)
    private PreferredContactMethod preferredContactMethod = PreferredContactMethod.NONE;

    /**
     * Display name for this party. Sourced from pos-people (ADR-0015 I2); pos-customer
     * no longer keeps a local name copy, so this returns {@code null}. Callers needing
     * a name resolve it from pos-people via {@link #getPersonId()}.
     */
    @Override
    @Transient
    @Schema(description = "Display name (sourced from pos-people)")
    public @Nullable String getDisplayName() {
        return null;
    }

    /** No local name to validate — identity lives in pos-people (ADR-0015 I2). */
    @Override
    protected void validateNames() {
        // intentionally empty
    }

    @Override
    public PartyType getPartyType() {
        return PartyType.PERSON;
    }

    @Transient
    @Schema(description = "Primary key for person party record in pos-customer")
    public UUID getPersonPartyId() {
        return getPartyId();
    }

    public void setPersonPartyId(UUID personPartyId) {
        setPartyId(personPartyId);
    }

    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
