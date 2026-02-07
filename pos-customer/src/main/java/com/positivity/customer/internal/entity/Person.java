package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.dto.PreferredContactMethod;
import com.positivity.shared.id.UUIDv7Generator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
@NoArgsConstructor
@Entity
@Table(name = "person", indexes = {
        @Index(name = "idx_person_first_name", columnList = "first_name"),
        @Index(name = "idx_person_last_name", columnList = "last_name")
})
@Schema(description = "Individual person record in the CRM system")
public class Person {

    @Id
    @Column(name = "person_id", columnDefinition = "UUID", updatable = false, nullable = false)
    @Schema(description = "Unique identifier for the person", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID personId;

    @PrePersist
    public void generateId() {
        if (personId == null) {
            personId = UUIDv7Generator.generate();
        }
    }

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
     * Adds a contact point to this person.
     *
     * @param contactPoint the contact point to add
     */
    public void addContactPoint(@NonNull ContactPoint contactPoint) {
        contactPoints.add(contactPoint);
        contactPoint.setPerson(this);
    }

    /**
     * Removes a contact point from this person.
     *
     * @param contactPoint the contact point to remove
     */
    public void removeContactPoint(@NonNull ContactPoint contactPoint) {
        contactPoints.remove(contactPoint);
        contactPoint.setPerson(null);
    }

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
}
