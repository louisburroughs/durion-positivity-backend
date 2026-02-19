package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.positivity.customer.internal.dto.PreferredContactMethod;
import com.positivity.customer.internal.enums.PartyType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Person entity representing an individual in the CRM system.
 * Distinct from Party (which represents organizations) and Contact (which links
 * persons to parties).
 * <p>
 * This entity is the System of Record for individual person master data per
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
    @Column(name = "first_name", nullable = false, length = 100)
    @Schema(description = "First name of the person", example = "John", requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @NotBlank
    @Column(name = "last_name", nullable = false, length = 100)
    @Schema(description = "Last name of the person", example = "Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_contact_method", nullable = false, length = 20)
    @Schema(description = "Preferred method of contact", example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED)
    private PreferredContactMethod preferredContactMethod = PreferredContactMethod.NONE;

    @OneToMany(mappedBy = "person", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Schema(description = "Contact points (emails, phones) for this person")
    private List<ContactPoint> contactPoints = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    @Schema(description = "Timestamp when the person was created")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Timestamp when the person was last updated")
    private Instant updatedAt;

    /**
     * Returns the full display name of the person.
     *
     * @return formatted full name
     */
    @Transient
    @Schema(description = "Full display name", example = "John Doe")
    public String getDisplayName() {
        return (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "");
    }

    @Override
    public PartyType getPartyType() {
        return PartyType.PERSON;
    }

}
