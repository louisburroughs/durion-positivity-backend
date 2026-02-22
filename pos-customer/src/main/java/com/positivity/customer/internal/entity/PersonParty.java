package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.shared.id.UUIDv7Generator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * PersonParty entity representing an individual in the CRM system.
 * Distinct from Party (which represents organizations) and Contact (which links
 * persons to parties).
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
@Schema(description = "Individual person party in the CRM system")
public class PersonParty extends AbstractParty {

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
    public void generateId() {
        if (getPartyId() == null) {
            setPartyId(UUIDv7Generator.generate());
        }
        validateNames();
        setCreatedAt(Instant.now());
        setModifiedAt(Instant.now());
    }

    @PreUpdate
    private void validateCustomer() {
        validateNames();
        setModifiedAt(Instant.now());

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

    /**
     * Compatibility accessor for person APIs that use personId naming.
     *
     * @return the party ID used as person ID
     */
    @Transient
    public UUID getPersonId() {
        return getPartyId();
    }

    /**
     * Compatibility mutator for person APIs that use personId naming.
     *
     * @param personId the ID value to set
     */
    public void setPersonId(UUID personId) {
        setPartyId(personId);
    }

    /**
     * Compatibility accessor for APIs expecting updatedAt.
     *
     * @return last modified timestamp
     */
    @Transient
    public Instant getUpdatedAt() {
        return getModifiedAt();
    }

}
