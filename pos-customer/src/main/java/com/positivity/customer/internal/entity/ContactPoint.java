package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.positivity.customer.internal.dto.ContactPointType;
import com.positivity.shared.id.UUIDv7Generator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Contact point entity representing a single contact method (email, phone) for
 * a person.
 * Supports multiple contact points per person with type and primary
 * designation.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/111">Backend
 *      Issue #111</a>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "contact_point", indexes = {
        @Index(name = "idx_contact_point_person", columnList = "person_id"),
        @Index(name = "idx_contact_point_type", columnList = "contact_type"),
        @Index(name = "idx_contact_point_value", columnList = "value")
})
@Schema(description = "Contact point (email, phone) for a person")
public class ContactPoint {

    @Id
    @Column(name = "contact_point_id", columnDefinition = "UUID", updatable = false, nullable = false)
    @Schema(description = "Unique identifier for the contact point")
    private UUID contactPointId;

    @PrePersist
    public void generateId() {
        if (contactPointId == null) {
            contactPointId = UUIDv7Generator.generate();
        }
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Schema(description = "Person this contact point belongs to")
    private Person person;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 20)
    @Schema(description = "Type of contact point", example = "EMAIL")
    private ContactPointType contactType;

    @NotBlank
    @Column(name = "value", nullable = false, length = 255)
    @Schema(description = "The contact value (email address, phone number)", example = "john.doe@example.com")
    private String value;

    @Column(name = "is_primary", nullable = false)
    @Schema(description = "Whether this is the primary contact point of its type", example = "true")
    private boolean isPrimary = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    @Schema(description = "Timestamp when the contact point was created")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "Timestamp when the contact point was last updated")
    private Instant updatedAt;
}
