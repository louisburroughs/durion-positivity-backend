package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.shared.id.UUIDv7Id;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * PersonParty entity representing an individual in the CRM system.
 * Distinct from CommercialParty (which represents organizations) and Contact
 * (which links
 * persons to CommercialParties).
 * <p>
 * This entity is the system of record for individual person master data per
 * domain:crm decisions.
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/111">Backend
 *      Issue #111</a>
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

    @NotBlank
    @Schema(description = "Last name of the customer", example = "Doe")
    private String lastName;

    @NotBlank
    @Schema(description = "First name of the customer", example = "John")
    private String firstName;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_contact_method", nullable = false, length = 20)
    @Schema(description = "Preferred method of contact", example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED)
    private PreferredContactMethod preferredContactMethod = PreferredContactMethod.NONE;

    @OneToMany(mappedBy = "person", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Schema(description = "Contact points (emails, phones) for this person")
    private List<ContactPoint> contactPoints = new ArrayList<>();

    /**
     * Returns the full display name of the person.
     *
     * @return formatted full name
     */
    @Transient
    @Schema(description = "Full display name", example = "John Doe")
    public String getDisplayName() {
        return (getFirstName() != null ? getFirstName() : "") + " " + (getLastName() != null ? getLastName() : "");
    }

    @PrePersist
    public void onPersist() {
        validateNames();
    }

    @PreUpdate
    private void onUpdate() {
        validateNames();
    }

    /** Helper method to validate customer names */
    protected void validateNames() {
        if (firstName == null || firstName.isBlank()) {
            throw new IllegalStateException("firstName is required for a customer");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new IllegalStateException("lastName is required for a customer");
        }
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
